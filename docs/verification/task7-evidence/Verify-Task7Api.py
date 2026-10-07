"""Task7 真实验证器：读取 Spring、新 Agent 与 MySQL，账号及原完整事实仅保存在忽略目录。

仅新建任务账号和两套餐可写，真实购买通过报价/创建/支付接口；原订单、券、库存与售后资格只读保护。
公开证据记录资源、精确金额、权限和只读增量，不保存密码、JWT 或内部确认凭证。
"""
import argparse
import base64
import hashlib
import json
import os
import secrets
import uuid
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

import httpx
import pymysql

# 两仓职责固定：业务事实在手机 Spring 库，新 Agent 仅经已有工具读取。
ROOT = Path('C:/fdz-build')
PRIVATE = ROOT / '.runtime/task7'
PUBLIC = ROOT / 'docs/verification/task7-evidence'
TABLES = ('stores', 'deal_packages', 'group_buy_orders', 'vouchers', 'refund_requests', 'store_reviews')


def save(path, value):
    """序列化采集结果，敏感原始响应只允许调用方传入 PRIVATE；金额按 Decimal 原值保留。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str), encoding='utf-8')


def rows(connection, sql, values=()):
    """执行参数化数据库查询，资源身份不能拼接到 SQL；返回真实完整行。"""
    with connection.cursor() as cursor:
        cursor.execute(sql, values)
        return cursor.fetchall()


def digest(value):
    """对完整事实作稳定摘要，时间戳和金额字符串保持数据库原意。"""
    return hashlib.sha256(json.dumps(value, sort_keys=True, default=str, separators=(',', ':')).encode()).hexdigest()


def check(label, condition, detail=None):
    """每项实际断言立即追加留档，失败保留并停止，不覆盖旧失败或算作跳过通过。"""
    path = PUBLIC / 'api-checks.json'
    data = json.loads(path.read_text(encoding='utf-8')) if path.exists() else []
    data.append(dict(label=label, passed=bool(condition), at=datetime.now(timezone.utc).isoformat(), detail=detail))
    save(path, data)
    if not condition:
        raise AssertionError(label)


def login(client, account):
    """只登录本任务随机账号，令牌及口令不输出；正常 JWT 实际期限另作脱敏采集。"""
    assert account['username'].startswith('task7_')
    data = client.post('/auth/login', json={k: account[k] for k in ('username', 'password')}).raise_for_status().json()
    client.headers['Authorization'] = 'Bearer ' + data['token']
    claims = json.loads(base64.urlsafe_b64decode(data['token'].split('.')[1] + '==='))
    save(PUBLIC / 'jwt-lifetime.json', dict(lifetimeSeconds=claims['exp'] - claims['iat']))
    return data


def setup(client, connection):
    """先保存全部原业务行，再注册两个随机账号、新套餐并执行真实购买；不会读取旧账号凭据。"""
    if (PRIVATE / 'account.json').exists():
        raise RuntimeError('任务账号已存在，禁止重做 setup 或覆盖开始事实')
    original = {table: rows(connection, 'SELECT * FROM `' + table + '` ORDER BY id') for table in TABLES}
    save(PRIVATE / 'original-facts.json', original)
    save(PUBLIC / 'original-facts-before.json', {k: dict(count=len(v), sha256=digest(v)) for k, v in original.items()})
    for role in ('account', 'outsider'):
        account = dict(username='task7_' + role + '_' + uuid.uuid4().hex[:10], password=secrets.token_urlsafe(24))
        auth = client.post('/auth/register', json={**account, 'email': account['username'] + '@example.invalid'}).raise_for_status().json()
        account['userId'] = auth['user']['id']
        save(PRIVATE / (role + '.json'), account)
    # 新套餐继承现有门店外键和审核图片；仅复制到新 UUID 行，不修改任何原套餐价格/库存。
    seeds = rows(connection, 'SELECT * FROM deal_packages WHERE active=1 ORDER BY image_url IS NULL,id LIMIT 2')
    resources = []
    for index, source in enumerate(seeds):
        value = {k: v for k, v in source.items() if k != 'id'}
        value.update(public_id=str(uuid.uuid4()), title='查询任务套餐' + ('甲' if index == 0 else '乙'),
                     description='Task7 新隔离套餐，只读查询验证；使用范围为所属门店，核销暂未开放。',
                     inventory=40, purchase_limit=3, active=1, version=1,
                     price_amount=Decimal('71.23') if index == 0 else Decimal('96.47'),
                     original_price_amount=Decimal('101.23') if index == 0 else Decimal('126.47'),
                     price_cents=None, original_price_cents=None)
        if index == 1:
            value.update(image_url=None, image_alt=None)
        fields = list(value)
        with connection.cursor() as cursor:
            cursor.execute('INSERT INTO deal_packages (' + ','.join('`' + k + '`' for k in fields) + ') VALUES (' + ','.join(['%s'] * len(fields)) + ')', tuple(value[k] for k in fields))
        value['storePublicId'] = rows(connection, 'SELECT public_id FROM stores WHERE id=%s', (value['store_id'],))[0]['public_id']
        resources.append(value)
    save(PRIVATE / 'resources.json', resources)
    save(PUBLIC / 'resources.json', [{k: r[k] for k in ('public_id', 'title', 'storePublicId', 'price_amount', 'inventory', 'image_url')} for r in resources])
    account = json.loads((PRIVATE / 'account.json').read_text(encoding='utf-8'))
    login(client, account)
    orders = []
    for index, quantity, pay in ((0, 2, True), (1, 1, True), (0, 1, False)):
        session = str(uuid.uuid4())
        quote = client.post('/purchases/quotes', json=dict(dealId=resources[index]['public_id'], quantity=quantity, sessionId=session)).raise_for_status().json()
        order = client.post('/group-orders', json=dict(quoteId=quote['quoteId'], sessionId=session,
                            confirmationGrant=quote['confirmationGrant'], idempotencyKey='task7-' + uuid.uuid4().hex)).raise_for_status().json()
        if pay:
            order = client.post('/group-orders/' + order['orderId'] + '/mock-pay', json=dict(idempotencyKey='task7-pay-' + uuid.uuid4().hex)).raise_for_status().json()
        orders.append(order)
    save(PRIVATE / 'orders.json', orders)
    save(PUBLIC / 'real-purchases.json', orders)
    check('隔离真实购买两已支付一待支付', [o['status'] for o in orders] == ['PAID', 'PAID', 'PENDING_PAYMENT'])
    print('setup: new accounts/resources; real purchases complete')


def reads(client, connection, account):
    """实际核对本人列表、不同资源、快照与跨用户/游客权限，读取不得改变订单/券/售后行。"""
    before = {t: rows(connection, 'SELECT * FROM `' + t + '` ORDER BY id') for t in TABLES}
    orders = client.get('/group-orders').raise_for_status().json()
    vouchers = client.get('/vouchers').raise_for_status().json()
    # 页面成功接线的适用回归可能再购买一单；预期身份取本账号数据库事实而非固定演示数量。
    order_ids = {r['public_id'] for r in rows(connection, 'SELECT public_id FROM group_buy_orders WHERE user_id=%s', (account['userId'],))}
    voucher_ids = {r['public_id'] for r in rows(connection, 'SELECT public_id FROM vouchers WHERE user_id=%s', (account['userId'],))}
    check('本人订单券与DB数量一致', len(orders) == len(order_ids) and len(vouchers) == len(voucher_ids), dict(orders=len(orders), vouchers=len(vouchers)))
    check('所有订单及券公开身份独立', {o['orderId'] for o in orders} == order_ids and {v['publicId'] for v in vouchers} == voucher_ids and len({o['orderId'] for o in orders}) == len(orders) and len({v['publicId'] for v in vouchers}) == len(vouchers))
    checked = []
    for order in orders:
        detail = client.get('/group-orders/' + order['orderId']).raise_for_status().json()
        check('列表详情同一事实-' + order['orderId'], detail == order and order['quantity'] == order['voucherCount'] == len(order['vouchers']))
        fact = rows(connection, 'SELECT * FROM group_buy_orders WHERE public_id=%s AND user_id=%s', (order['orderId'], account['userId']))[0]
        check('订单金额与精确DB一致-' + order['orderId'], order['totalCents'] == int(fact['total_amount'] * 100))
        if fact['paid_total_amount'] is None:
            check('待支付无实付或资格-' + order['orderId'], order['paidTotalCents'] is None and order['paidAt'] is None and not order['canReview'] and all(v['amountCents'] is None and not v['canRefund'] for v in order['vouchers']))
        else:
            check('实付快照与单券分摊-' + order['orderId'], order['paidTotalCents'] == int(fact['paid_total_amount'] * 100) == sum(v['amountCents'] for v in order['vouchers']))
        for voucher in order['vouchers']:
            detail_v = client.get('/vouchers/' + voucher['publicId']).raise_for_status().json()
            check('每券详情归属和购买上下文-' + voucher['publicId'], all(detail_v[k] == voucher[k] for k in voucher) and detail_v['orderQuantity'] == order['quantity'] and detail_v['orderCreatedAt'] == order['createdAt'])
            check('只读响应无核销令牌-' + voucher['publicId'], not any(k in detail_v for k in ('token', 'qrSecret', 'redemptionToken')))
        checked.append(dict(order=order, database={k: fact[k] for k in ('public_id', 'quantity', 'status', 'total_amount', 'paid_total_amount', 'paid_at')}))
    outsider = json.loads((PRIVATE / 'outsider.json').read_text(encoding='utf-8'))
    with httpx.Client(base_url='http://127.0.0.1:8080/api', trust_env=False, timeout=120) as other:
        login(other, outsider)
        check('新空账号无订单', other.get('/group-orders').raise_for_status().json() == [])
        for prefix, resource in (('/group-orders/', orders[0]['orderId']), ('/vouchers/', vouchers[0]['publicId'])):
            denied = other.get(prefix + resource)
            tourist = httpx.get('http://127.0.0.1:8080/api' + prefix + resource, trust_env=False)
            check('其他用户拒绝-' + prefix, denied.status_code in (400, 403, 404), dict(http=denied.status_code))
            check('游客拒绝-' + prefix, tourist.status_code == 401, dict(http=tourist.status_code))
    after = {t: rows(connection, 'SELECT * FROM `' + t + '` ORDER BY id') for t in TABLES}
    check('所有只读权限查询业务事实无变化', digest(before) == digest(after))
    save(PUBLIC / 'api-database-reads.json', checked)
    print('reads: ownership, distinct IDs, exact payment facts verified')


def agent(client, connection, account):
    """经 Spring 公开入口运行真实模型及新 Agent，检查只读工具、卡片与无订单/售后写入。"""
    before = {t: rows(connection, 'SELECT * FROM `' + t + '` ORDER BY id') for t in TABLES}
    expected = [('orders', '查询我的团购订单', 'list_user_orders', 'order'),
                ('vouchers', '查询我的团购券', 'list_user_vouchers', 'voucher')]
    for label, query, tool, kind in expected:
        session = str(uuid.uuid4())
        response = client.post('/agent/sessions/' + session + '/messages', json=dict(text=query, mode='CONFIRM')).raise_for_status().json()
        save(PRIVATE / ('agent-' + label + '.json'), response)
        save(PUBLIC / ('agent-' + label + '.json'), response)
        check('在线只读完成-' + label, response['status'] == 'COMPLETED', dict(status=response['status'], message=response.get('message')))
        cards = response.get('cards', [])
        check('在线卡片类型与真实资源-' + label, len(cards) == (3 if kind == 'order' else 4) and all(c['type'] == kind for c in cards))
        calls = rows(connection, 'SELECT tool_name FROM agent_tool_executions WHERE session_id=%s', (session,))
        check('在线只执行读取工具-' + label, [r['tool_name'] for r in calls] == [tool])
        check('在线响应不包含私有令牌-' + label, 'confirmationGrant' not in json.dumps(response) and 'redemptionToken' not in json.dumps(response))
    after = {t: rows(connection, 'SELECT * FROM `' + t + '` ORDER BY id') for t in TABLES}
    check('真实Agent查询未改变订单券售后事实', digest(before) == digest(after))
    print('agent: live model/read tools/cards verified')


def protect(connection, release=False):
    """重读开始时每一完整原行；收尾仅停用本任务新增套餐，不删除订单或恢复库存。"""
    original = json.loads((PRIVATE / 'original-facts.json').read_text(encoding='utf-8'))
    evidence = {}
    for table, expected in original.items():
        current = {r['id']: r for r in rows(connection, 'SELECT * FROM `' + table + '` ORDER BY id')}
        selected = [current[r['id']] for r in expected]
        check('原业务完整行保护-' + table, digest(selected) == digest(expected))
        evidence[table] = dict(count=len(selected), before=digest(expected), after=digest(selected))
    save(PUBLIC / 'original-facts-after.json', evidence)
    if release:
        resources = json.loads((PRIVATE / 'resources.json').read_text(encoding='utf-8'))
        for resource in resources:
            with connection.cursor() as cursor:
                cursor.execute('UPDATE deal_packages SET active=0 WHERE public_id=%s AND description=%s', (resource['public_id'], resource['description']))
                check('只停用本任务套餐-' + resource['public_id'], cursor.rowcount == 1)
        save(PUBLIC / 'resources-released.json', [rows(connection, 'SELECT public_id,price_amount,version,inventory,active FROM deal_packages WHERE public_id=%s', (r['public_id'],))[0] for r in resources])


def identity(client, connection, account):
    """同会话真实两轮查询后回放SSE；对照原事件身份，数据库游标不能冒充原sequence。"""
    session = str(uuid.uuid4())
    replies = []
    for text in ('查询我的团购订单', '查询我的团购券'):
        replies.append(client.post('/agent/sessions/' + session + '/messages', json=dict(text=text, mode='CONFIRM')).raise_for_status().json())
    # 会话已经由本人POST创建，再回放真实事件；短读超时只结束采集，不计业务通过或失败。
    frames = []
    frame = []
    try:
        with client.stream('GET', '/agent/sessions/' + session + '/events', headers={'Last-Event-ID': '0'}, timeout=5) as stream:
            stream.raise_for_status()
            for line in stream.iter_lines():
                if line:
                    frame.append(line)
                elif frame:
                    data = '\n'.join(item[5:].strip() for item in frame if item.startswith('data:'))
                    if data:
                        frames.append(json.loads(data))
                    frame = []
    except httpx.ReadTimeout:
        pass
    save(PRIVATE / 'identity-sse.json', frames)
    save(PUBLIC / 'identity-http.json', replies)
    # 只比较携带原eventId的原事件；Spring兼容记录可以没有该身份，不能补造。
    expected = [e['payload'] for reply in replies for e in reply['events'] if e.get('payload', {}).get('eventId')]
    observed = [f['payload'] for f in frames if isinstance(f.get('payload'), dict) and f['payload'].get('eventId')]
    fields = ('eventId', 'sequence', 'runId', 'traceId', 'status')
    project = lambda items: [{k: item.get(k) for k in fields} for item in items]
    check('真实同会话HTTP与SSE原身份完全一致', project(expected) == project(observed), dict(httpOriginalEvents=len(expected), sseOriginalEvents=len(observed), separateRuns=len({e['runId'] for e in expected})))
    check('同会话不同轮原事件不重复', len({e['eventId'] for e in expected}) == len(expected) and len({e['runId'] for e in expected}) == 2)
    save(PUBLIC / 'identity-match.json', dict(sessionId=session, http=project(expected), sse=project(observed), replayFrames=len(frames)))
    print('identity: same-session original HTTP/SSE identities verified')


def main():
    """按显式阶段执行，凭据来自已批准环境；异常保留第一次断言与日志，不自行跳过。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('phase', choices=('setup', 'reads', 'agent', 'identity', 'protect', 'release'))
    phase = parser.parse_args().phase
    with pymysql.connect(host='127.0.0.1', user=os.environ['DB_USERNAME'], password=os.environ['DB_PASSWORD'],
            database='fandazi_groupbuy_dev', charset='utf8mb4', autocommit=True, cursorclass=pymysql.cursors.DictCursor) as connection, \
            httpx.Client(base_url='http://127.0.0.1:8080/api', trust_env=False, timeout=140) as client:
        if phase == 'setup':
            setup(client, connection)
        elif phase in ('protect', 'release'):
            protect(connection, phase == 'release')
        else:
            account = json.loads((PRIVATE / 'account.json').read_text(encoding='utf-8'))
            login(client, account)
            {'reads': reads, 'agent': agent, 'identity': identity}[phase](client, connection, account)


if __name__ == '__main__':
    main()

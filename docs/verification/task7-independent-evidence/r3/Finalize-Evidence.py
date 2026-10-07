"""R3独立收尾证据汇总：读取Maestro状态、本人预览身份、原私有指纹及公开秘密扫描，不运行产品写接口。

所有输出写入本轮独立目录；原命令树、凭据和完整业务行不导出，不覆盖R0/R1/R2首次结果。
"""
import hashlib
import json
import os
import re
import sys
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path
import pymysql

ROOT = Path('C:/fdz-build')
BASE = ROOT / 'docs/verification/task7-independent-evidence'
PUBLIC = BASE / 'r3'
PRIVATE = ROOT / '.runtime/task7-independent'


def load(path):
    """按JSON结构读取证据，兼容PowerShell UTF8 BOM；不向终端输出原始秘密。"""
    return json.loads(path.read_text(encoding='utf-8-sig'))


def sha(path):
    """计算完整字节摘要，源保护不采用内容清洗。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(name, value):
    """本轮结果只能首次创建，后续失败修复必须换新名，禁止覆盖。"""
    path = PUBLIC / name
    assert not path.exists(), '禁止覆盖首次结果'
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def collect():
    """汇总两个已结束UI流程，并以本人预览的资源/金额证明工作区注册路线原身份。"""
    runs = []
    for label in ('r3-workspace-query', 'r3-unregistered'):
        result = load(BASE / ('maestro-' + label) / 'result.json')
        files = list((PRIVATE / ('maestro-' + label)).rglob('commands.json'))
        assert len(files) == 1
        commands = load(files[0])
        counts = dict(Counter(x.get('metadata', {}).get('status', 'UNKNOWN') for x in commands))
        shots = [{'path': p.relative_to(BASE).as_posix(), 'sha256': sha(p)}
                 for p in (BASE / ('maestro-' + label)).rglob('*.png')]
        runs.append({**result, 'label': label, 'commands': len(commands), 'statuses': counts, 'screenshots': shots})
        assert result['exitCode'] == 0 and counts.get('FAILED', 0) == 0
    counts_result = {'runs': runs, 'totalCommands': sum(x['commands'] for x in runs),
         'totalScreenshots': sum(len(x['screenshots']) for x in runs)}
    # 首次汇总已落盘而后续解析失败时，只核对原结果相等，不覆盖已完成证据。
    if (PUBLIC / 'maestro-counts.json').exists():
        assert load(PUBLIC / 'maestro-counts.json') == counts_result
    else:
        save('maestro-counts.json', counts_result)
    account = load(PRIVATE / 'account.json')
    orders = load(PRIVATE / 'orders.json')
    expected = orders[0]['vouchers'][1]['publicId']
    # 只选择当前Task本人预览，不读令牌、会话或旧Task申请；预览签发不等于提交退款。
    with pymysql.connect(host='127.0.0.1', user=os.environ['DB_USERNAME'], password=os.environ['DB_PASSWORD'],
            database='fandazi_groupbuy_dev', charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor) as db:
        with db.cursor() as cursor:
            cursor.execute('SELECT resource_id, amount, consumed, expires_at FROM confirmation_grants WHERE user_id=%s AND action=%s ORDER BY id DESC LIMIT 1',
                           (account['userId'], 'REQUEST_REFUND'))
            latest = cursor.fetchone()
    # PowerShell保留七位小数，Python3.10只支持六位微秒；仅规范化精度，不改变时区或秒值。
    started = datetime.fromisoformat(re.sub(r'(\.\d{6})\d+', r'\1', runs[0]['startedAt'])).astimezone(timezone.utc).replace(tzinfo=None)
    finished = datetime.fromisoformat(re.sub(r'(\.\d{6})\d+', r'\1', runs[0]['finishedAt'])).astimezone(timezone.utc).replace(tzinfo=None)
    valid = latest is not None and latest['resource_id'] == expected and str(latest['amount']) == '71.23' and not latest['consumed']
    valid = valid and started <= latest['expires_at'] <= finished + timedelta(minutes=15)
    identity = {'passed': bool(valid), 'expectedVoucherId': expected, 'orderId': orders[0]['orderId'],
        'orderTailShown': orders[0]['orderId'][-8:], 'resourceId': latest['resource_id'] if latest else None,
        'amount': str(latest['amount']) if latest else None, 'consumed': bool(latest['consumed']) if latest else None,
        'previewExpiresAtUtc': latest['expires_at'].isoformat() if latest else None,
        'note': '已注册原页面会POST prepare签发未消费预览凭证；没有提交退款，不能描述为纯GET链'}
    save('registered-route-identity.json', identity)
    assert valid, '注册路线预览未确认原券身份'
    print('R3 UI汇总', sum(x['commands'] for x in runs), '命令；注册身份与未消费预览一致')


def protect():
    """比较原Task2/5/6/8私有JSON指纹；不解析旧账号或资源内容。"""
    runtime = ROOT / '.runtime'
    paths = []
    for item in runtime.iterdir():
        if re.match(r'^task(2|5|6|8)', item.name):
            paths.extend(item.rglob('*.json') if item.is_dir() else ([item] if item.suffix == '.json' else []))
    current = {str(p.relative_to(runtime)): {'bytes': p.stat().st_size, 'sha256': sha(p)} for p in paths}
    old = {x['path']: {'bytes': x['bytes'], 'sha256': x['sha256']} for x in load(BASE / 'protected-after.json')}
    changed = [p for p in current if current[p] != old.get(p)]
    missing = [p for p in old if p not in current]
    result = {'count': len(current), 'changedCount': len(changed), 'missingCount': len(missing),
              'changedPaths': changed, 'missingPaths': missing, 'passed': not changed and not missing}
    save('private-release.json', result)
    assert result['passed'], '原私有JSON变化'
    print('R3私有保护', len(current), '项不变')


def scan():
    """报告完成后扫描公开秘密并确认R0/R1历史摘要；不输出任何匹配内容。"""
    report = ROOT / 'docs/verification/2026-10-02-task7-independent-acceptance.md'
    body = report.read_text(encoding='utf-8-sig')
    r0 = body.split('## R1 独立受影响复验进度')[0].rstrip() + '\n'
    r1 = body.split('## R2 独立定向验收进度')[0].rstrip() + '\n'
    old = load(BASE / 'r2/history-preserved.json')
    history = {'r0Unchanged': hashlib.sha256(r0.encode()).hexdigest().upper() == old['r0SectionSha256'],
        'r0R1Unchanged': hashlib.sha256(r1.encode()).hexdigest().upper() == old['r0R1PrefixSha256']}
    save('history-preserved.json', history)
    assert all(history.values()), '历史报告变化'
    secrets = [load(PRIVATE / (role + '.json'))['password'] for role in ('account', 'outsider', 'empty')]
    secrets += [v for k, v in os.environ.items() if any(w in k.upper() for w in ('PASSWORD', 'SECRET', 'API_KEY', 'INTERNAL_TOKEN'))]
    failures = []
    checked = 0
    for path in list(BASE.rglob('*')) + [report]:
        if not path.is_file() or path.suffix.lower() not in ('.json', '.xml', '.md', '.ps1', '.py', '.java', '.yaml', '.txt'):
            continue
        text = path.read_text(encoding='utf-8-sig', errors='replace')
        known = any(v and len(v) >= 8 and v in text for v in secrets)
        jwt = bool(re.search(r'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}', text))
        if known or jwt:
            failures.append({'path': str(path), 'knownSecret': known, 'jwtShape': jwt})
        checked += 1
    save('secret-scan.json', {'checkedTextFiles': checked, 'failures': failures, 'passed': not failures})
    assert not failures, '公开证据秘密扫描失败'
    print('R3公开扫描', checked, '文本，秘密/JWT匹配0；R0/R1原文不变')


if __name__ == '__main__':
    {'collect': collect, 'protect': protect, 'scan': scan}[sys.argv[1]]()

"""R3真实只读接口与业务保护：仅登录本Task随机账号，读取本人/他人/游客结果，不创建售后申请。

依赖冻结验证器的结构化DB行摘要。完整业务行留在既有私有目录，本脚本公开仅计数、公开身份和SHA。
"""
import importlib.util
import json
import os
import sys
from datetime import datetime
from pathlib import Path
import httpx
import pymysql

ROOT = Path('C:/fdz-build')
PRIVATE = ROOT / '.runtime/task7-independent'
PUBLIC = ROOT / 'docs/verification/task7-independent-evidence/r3'
spec = importlib.util.spec_from_file_location('frozen_reader', ROOT / 'docs/verification/task7-evidence/Verify-Task7Api.py')
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)


def main():
    """按resume/final读保护摘要；api另执行HTTP权限检查。任一断言失败保留结果后抛错。"""
    phase = sys.argv[1]
    output = PUBLIC / ('read-api-' + phase + '.json')
    assert not output.exists(), '禁止覆盖首次结果'
    checks = []
    result = {'phase': phase, 'at': datetime.now().astimezone().isoformat(), 'checks': checks}
    try:
        # 先规范化基线：按原行ID比较完整内容，不能用行数不变掩盖原事实被修改。
        original = json.loads((PRIVATE / 'original-facts.json').read_text(encoding='utf-8'))
        baseline = json.loads((PRIVATE / 'r1/before-facts.json').read_text(encoding='utf-8'))
        with pymysql.connect(host='127.0.0.1', user=os.environ['DB_USERNAME'], password=os.environ['DB_PASSWORD'],
                database='fandazi_groupbuy_dev', charset='utf8mb4', autocommit=True,
                cursorclass=pymysql.cursors.DictCursor) as db:
            current = {t: reader.rows(db, 'SELECT * FROM `' + t + '` ORDER BY id') for t in reader.TABLES}
        tables = {}
        for table, before in original.items():
            by_id = {r['id']: r for r in current[table]}
            selected = [by_id[r['id']] for r in before]
            tables[table] = {'originalCount': len(before), 'currentCount': len(current[table]),
                'originalUnchanged': reader.digest(before) == reader.digest(selected), 'currentSha': reader.digest(current[table])}
        result['protection'] = {'tables': tables, 'originalTotal': sum(map(len, original.values())),
            'currentTotal': sum(map(len, current.values())), 'sameCompleteFactsAsR1': reader.digest(current) == reader.digest(baseline)}
        assert all(t['originalUnchanged'] for t in tables.values()), '原业务事实变化'
        assert result['protection']['sameCompleteFactsAsR1'], '本Task业务事实相对暂停基线变化'
        if phase == 'api':
            accounts = {role: json.loads((PRIVATE / (role + '.json')).read_text(encoding='utf-8')) for role in ('account', 'outsider')}
            orders = json.loads((PRIVATE / 'orders.json').read_text(encoding='utf-8'))
            vouchers = [v['publicId'] for order in orders for v in order['vouchers']]
            headers = {}
            with httpx.Client(base_url='http://127.0.0.1:8080/api', trust_env=False, timeout=20) as client:
                for role, account in accounts.items():
                    assert account['username'].startswith('task7_'), '禁止登录原Task账号'
                    auth = client.post('/auth/login', json={'username': account['username'], 'password': account['password']}).raise_for_status().json()
                    headers[role] = {'Authorization': 'Bearer ' + auth['token']}
                # 已有申请仅从本Task本人券范围查找，不读取原Task8身份、不提交退款制造200。
                own_requests = [r for r in current['refund_requests'] if r['user_id'] == accounts['account']['userId']]
                result['liveExisting200'] = {'ownExistingCount': len(own_requests), 'covered': False,
                    'boundary': '本Task无安全已有申请则不创建；200身份已有H2持久化及合成MVC证据'}
                for voucher in vouchers:
                    detail = client.get('/vouchers/' + voucher, headers=headers['account'])
                    checks.append({'case': '本人原券仍可读', 'voucherId': voucher, 'status': detail.status_code})
                    assert detail.status_code == 200 and detail.json()['publicId'] == voucher
                    for role, expected in (('account', 404), ('outsider', 400), ('guest', 401)):
                        response = client.get('/refunds/by-voucher/' + voucher, headers=headers.get(role, {}))
                        checks.append({'case': role, 'voucherId': voucher, 'status': response.status_code, 'expected': expected})
                        assert response.status_code == expected, '真实只读权限/404不符合预期'
                        if role == 'outsider':
                            assert response.json().get('code') == 'DOMAIN_ERROR' and '团购券不存在' in response.json().get('error', '')
                assert not own_requests, '本人已有申请，需追加安全200读覆盖'
        result['passed'] = True
    except Exception as error:
        result['passed'] = False
        result['errorType'] = type(error).__name__
        raise
    finally:
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print('R3只读', phase, 'PASS' if result.get('passed') else 'FAIL', 'HTTP检查', len(checks))


if __name__ == '__main__':
    main()

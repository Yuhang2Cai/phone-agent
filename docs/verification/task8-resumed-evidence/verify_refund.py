"""Task8 真实退款接口验证：使用本轮随机账号核对进度、分摊金额、权限和资格拒绝。

不会提交新退款；界面写入由 Maestro 执行，本脚本只查询及验证无资格准备请求被拒绝。
"""
import json
from pathlib import Path
import httpx

# 私有清单只用于登录和定位本轮资源，公开结果不含账号、令牌及确认凭证。
ROOT = Path('C:/fdz-build')
SCENARIOS = {v['scenario']: v for v in json.loads((ROOT / '.runtime/task8-resumed/fixtures-ui.json').read_text(encoding='utf-8'))['scenarios']}
checks = []


def check(name, actual, expected):
    """记录每个真实断言，失败也落盘，以免将半程运行误算通过。"""
    checks.append({'name': name, 'actual': actual, 'expected': expected, 'passed': actual == expected})
    (ROOT / 'docs/verification/task8-resumed-evidence/refund-api.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding='utf-8')
    assert actual == expected, name


def login(client, scenario):
    """登录本轮账号并覆盖认证头，不打印返回令牌。"""
    account = SCENARIOS[scenario]
    data = client.post('/auth/login', json={k: account[k] for k in ('username', 'password')}).raise_for_status().json()
    client.headers['Authorization'] = 'Bearer ' + data['token']


def main():
    """逐项验证已申请真实状态及拒绝路径，不改原夹具或旧业务事实。"""
    with httpx.Client(base_url='http://127.0.0.1:8080/api', trust_env=False, timeout=30) as client:
        login(client, 'refund_requested')
        item = SCENARIOS['refund_requested']
        response = client.get('/refunds/by-voucher/' + item['voucherIds'][0])
        check('本人按券读取真实已有申请', response.status_code, 200)
        data = response.json()
        check('申请身份与夹具领域服务写入一致', data['requestId'], item['requestId'])
        check('真实待处理而非到账', data['status'], 'REQUESTED')
        check('按申请编号与按券读取相同结果', client.get('/refunds/' + item['requestId']).json(), data)
        # 未付款、已使用、已申请、已过期均不能取得新确认；拒绝不能靠客户端按钮禁用代替。
        for scenario in ('pending_payment', 'reviewable', 'refund_requested', 'no_candidates'):
            login(client, scenario)
            reply = client.post('/refunds/prepare', json={'sessionId': 'task8-negative', 'voucherId': SCENARIOS[scenario]['voucherIds'][0]})
            check(scenario + '资格拒绝', reply.status_code, 400)
        login(client, 'no_orders')
        check('他人不可读取申请', client.get('/refunds/by-voucher/' + item['voucherIds'][0]).status_code, 400)
        client.headers.pop('Authorization')
        check('游客不可读取申请', client.get('/refunds/' + item['requestId']).status_code, 401)
        login(client, 'refundable')
        # 此券由完整 UI 流程申请；真实分摊为 3334 分，不能使用当前套餐价或整单金额。
        response = client.get('/refunds/by-voucher/' + SCENARIOS['refundable']['voucherIds'][0])
        check('Maestro 提交后申请持久化', response.status_code, 200)
        check('单券精确分摊金额', response.json()['amountCents'], 3334)
        check('界面选择原因与说明已保存', response.json()['reason'], '行程变更：Task8 schedule change')
    print(f'真实退款接口 {len(checks)} 项通过')


if __name__ == '__main__':
    main()

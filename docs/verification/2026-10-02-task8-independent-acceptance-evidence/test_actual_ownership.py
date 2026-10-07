"""独立真实接口权限补充：使用本次七场景资源检查游客、其他账号和保留券事实。

依赖正在运行的8080与本次私有清单；仅执行只读或预期拒绝的准备/写入请求。
日志与JUnit由运行命令定向至私有目录，不输出登录名、密码或令牌。
"""
import json
import uuid
from pathlib import Path

import httpx


def test_other_owner_and_guest_cannot_prepare_or_write_after_sale():
    """其他账号无法关联本人订单或评价；游客无法准备/提交，历史动作未消费第三张券。"""
    entries = {row['scenario']: row for row in json.loads(Path(
        'C:/fdz-build/.runtime/task8-resumed/fixtures-independent-final-r0.json'
    ).read_text(encoding='utf-8-sig'))['scenarios']}
    with httpx.Client(base_url='http://127.0.0.1:8080/api', trust_env=False, timeout=30) as client:
        # 空订单账号用作其他所有者，所有资源均取本批次清单，不枚举既有业务数据。
        stranger = entries['no_orders']
        login = client.post('/auth/login', json={key: stranger[key] for key in ('username', 'password')})
        assert login.status_code == 200
        other_headers = {'Authorization': 'Bearer ' + login.json()['token']}
        session = str(uuid.uuid4())
        order = entries['refundable']['orderId']
        assert client.post('/complaints/prepare', headers=other_headers,
            json={'sessionId': session, 'orderId': order}).status_code == 400
        assert client.post('/complaints', headers=other_headers, json={'sessionId': session,
            'orderId': order, 'subject': '归属拒绝验收', 'description': '本次请求不得产生工单',
            'confirmationGrant': str(uuid.uuid4())}).status_code == 400
        assert client.post('/group-reviews', headers=other_headers,
            json={'orderId': entries['reviewable']['orderId'], 'rating': 5, 'content': '越权请求必须拒绝'}).status_code == 400
        # 游客请求在认证层拒绝，不能借准备接口得知订单、券资格或创建业务记录。
        for path, payload in [('/complaints/prepare', {'sessionId': session, 'orderId': order}),
                              ('/complaints', {'sessionId': session}),
                              ('/refunds/prepare', {'sessionId': session, 'voucherId': entries['refundable']['voucherIds'][2]}),
                              ('/refunds', {'sessionId': session}),
                              ('/group-reviews', {'orderId': order, 'rating': 5, 'content': '游客拒绝'})]:
            assert client.post(path, json=payload).status_code == 401
        # 历史动作续段只准备并取消第三张券；重启恢复之后也不得出现第三条退款。
        owner = entries['refundable']
        login = client.post('/auth/login', json={key: owner[key] for key in ('username', 'password')})
        assert login.status_code == 200
        client.headers['Authorization'] = 'Bearer ' + login.json()['token']
        assert client.get('/refunds/by-voucher/' + owner['voucherIds'][2]).status_code == 404
        vouchers = client.get('/group-orders/' + order).raise_for_status().json()['vouchers']
        reserved = next(row for row in vouchers if row['publicId'] == owner['voucherIds'][2])
        assert reserved['canRefund'] is True

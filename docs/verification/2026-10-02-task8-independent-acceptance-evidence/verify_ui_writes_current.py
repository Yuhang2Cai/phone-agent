"""独立验收写入复核：复用原断言，仅绑定新独占夹具和新证据路径，不覆盖开发事实。"""
import json
import os
from pathlib import Path

import httpx
import pymysql


def main():
    """从私有清单取得本轮资源，检查持久化事实及重复评价拒绝，只公开断言名称。"""
    root = Path('C:/fdz-build')
    entries = {v['scenario']: v for v in json.loads(
        (root / '.runtime/task8-resumed/fixtures-independent-final-r0.json').read_text(encoding='utf-8-sig'))['scenarios']}
    passed = []
    with pymysql.connect(host='127.0.0.1', user=os.environ['DB_USERNAME'], password=os.environ['DB_PASSWORD'],
                         database='fandazi_groupbuy_dev', charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor) as db:
        with db.cursor() as cursor:
            # 取消后再确认仅新增一张申请；其他历史事实不计入这组新账号的预期。
            cursor.execute('SELECT r.status,r.reason,r.amount FROM refund_requests r JOIN app_users u ON r.user_id=u.id WHERE u.username=%s',
                           (entries['refundable']['username'],))
            refunds = cursor.fetchall()
            assert len(refunds) == 2 and all(r['status'] == 'REQUESTED' for r in refunds)
            assert sum(r['reason'] == '行程变更' for r in refunds) == 1
            passed.append('普通页面与聊天各一条真实待处理退款，取消未写入')
            # 同一订单只允许一条评价，核对原文本和评分而不是只看成功提示。
            cursor.execute('SELECT r.rating,r.content FROM store_reviews r JOIN group_buy_orders o ON r.order_id=o.id WHERE o.public_id=%s',
                           (entries['reviewable']['orderId'],))
            reviews = cursor.fetchall()
            assert len(reviews) == 1 and int(reviews[0]['rating']) == 5
            assert reviews[0]['content'] == 'Task8 review: good meal and service.'
            passed.append('页面评价实际保存五星及原文且仅一条')
            for name in ('refundable', 'no_candidates'):
                cursor.execute('SELECT c.subject,c.description,c.status FROM complaint_tickets c JOIN app_users u ON c.user_id=u.id WHERE u.username=%s',
                               (entries[name]['username'],))
                complaints = cursor.fetchall()
                assert len(complaints) == 1 and complaints[0]['status'] == 'OPEN'
                if name == 'no_candidates':
                    assert complaints[0]['subject'] == '支付问题'
                    assert complaints[0]['description'] == 'Task8 agent payment display mismatch, please verify.'
                passed.append(name + '投诉实际保存一条待处理工单')
    # 重复评价属于拒绝测试，不会新增成功业务记录；身份取真实登录，令牌仅驻留内存。
    with httpx.Client(base_url='http://127.0.0.1:8080/api', trust_env=False, timeout=20) as client:
        account = entries['reviewable']
        token = client.post('/auth/login', json={k: account[k] for k in ('username', 'password')}).raise_for_status().json()['token']
        client.headers['Authorization'] = 'Bearer ' + token
        reply = client.post('/group-reviews', json={'orderId': account['orderId'], 'rating': 5, 'content': '禁止重复评价'})
        assert reply.status_code == 400
        assert client.get('/group-orders/' + account['orderId']).raise_for_status().json()['canReview'] is False
        passed.append('后端拒绝重复评价且本人入口资格已刷新')
    output = Path(__file__).parent / 'ui-writes-current.json'
    assert not output.exists(), '不覆盖历史核对结果'
    output.write_text(json.dumps({'passed': passed, 'count': len(passed)}, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'真实 UI 写入核对 {len(passed)} 项通过')


if __name__ == '__main__':
    main()

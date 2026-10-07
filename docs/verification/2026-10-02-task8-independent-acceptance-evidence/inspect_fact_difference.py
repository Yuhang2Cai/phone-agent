"""独立验收事实差异只读诊断：完整旧行差异仅入私有目录，公开结果只列字段与计数。

依赖既有事实保护序列化器和共享数据库环境；不更新库存、不回滚订单、不改原始基线。
"""
import importlib.util
import json
import os
from pathlib import Path

import pymysql

ROOT = Path('C:/fdz-build')
PRIVATE = ROOT / '.runtime/task8-independent-final'
spec = importlib.util.spec_from_file_location('guard_helper', ROOT / 'docs/verification/task7-evidence/Verify-Task7Api.py')
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


def main():
    """逐行比较全部保护表并关联本轮新增订单；仅执行SELECT，保留失败而不修复数据。"""
    original = json.loads((ROOT / '.runtime/task8-resumed/facts-start.json').read_text(encoding='utf-8'))
    private_path = PRIVATE / 'fact-difference.json'
    assert not private_path.exists(), '禁止覆盖首次差异证据'
    differences, summary = {}, {}
    with pymysql.connect(host='127.0.0.1', user=os.environ['DB_USERNAME'], password=os.environ['DB_PASSWORD'],
                         database='fandazi_groupbuy_dev', charset='utf8mb4', autocommit=True,
                         cursorclass=pymysql.cursors.DictCursor) as connection:
        # 当前值先用同一序列化方式规范化；避免Decimal、日期对象与JSON字符串造成伪差异。
        for table, before in original.items():
            current = json.loads(json.dumps(helper.rows(connection, 'SELECT * FROM `' + table + '` ORDER BY id'), default=str))
            by_id = {row['id']: row for row in current}
            changed = []
            for row in before:
                after = by_id.get(row['id'])
                fields = list(row) if after is None else [key for key in row if row[key] != after.get(key)]
                if fields:
                    changed.append(dict(id=row['id'], fields=fields, before=row, after=after))
            differences[table] = changed
            summary[table] = dict(originalCount=len(before), currentCount=len(current), changedOriginalRows=len(changed),
                                  changedFields=sorted({field for row in changed for field in row['fields']}))
        # 新订单与发生变化的套餐关联只在私有证据中保留，公开输出不泄漏账号或资源编号。
        related = []
        for row in differences.get('deal_packages', []):
            related.extend(helper.rows(connection,
                'SELECT o.*, u.username FROM group_buy_orders o JOIN app_users u ON u.id=o.user_id '
                'WHERE o.deal_id=%s AND o.id>%s ORDER BY o.id',
                (row['id'], max(item['id'] for item in original['group_buy_orders']))))
    helper.save(private_path, dict(differences=differences, newOrdersOnChangedDeals=related))
    # 只记录数量和测试账号命名空间是否匹配，实际登录名始终留在.runtime。
    result = dict(passed=not any(differences.values()), tables=summary,
                  newOrdersOnChangedDeals=len(related),
                  allRelatedOrdersFromIndependentBoundaryTest=bool(related) and all(row['username'].startswith('ind8_') for row in related))
    helper.save(Path(__file__).with_name('fact-difference-summary.json'), result)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()

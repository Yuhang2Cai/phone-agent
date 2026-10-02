"""Task8 业务事实保护：只读采集与核对旧行，允许新隔离账号产生自己的新事实。

依赖 pymysql 和 Task7 稳定序列化辅助；完整内容仅保存在忽略目录，公开摘要不含隐私。
"""
import importlib.util
import json
import os
import sys
from pathlib import Path

import pymysql

# 延用上一任务同一表集和金额序列化规则，避免摘要变化源于格式而非事实。
ROOT = Path('C:/fdz-build')
PRIVATE = ROOT / '.runtime/task8-resumed'
PUBLIC = ROOT / 'docs/verification/task8-resumed-evidence'
spec = importlib.util.spec_from_file_location('task7_guard', ROOT / 'docs/verification/task7-evidence/Verify-Task7Api.py')
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


def main():
    """接收 start 或唯一复核标签；旧行缺失或内容变化即失败，不覆盖原证据。"""
    phase = sys.argv[1]
    assert phase.replace('-', '').isalnum(), '阶段名只能使用字母、数字和连字符'
    result = PUBLIC / f'facts-{phase}.json'
    assert not result.exists(), '禁止覆盖事实保护证据'
    with pymysql.connect(host='127.0.0.1', user=os.environ['DB_USERNAME'], password=os.environ['DB_PASSWORD'], database='fandazi_groupbuy_dev', charset='utf8mb4', autocommit=True, cursorclass=pymysql.cursors.DictCursor) as connection:
        current = {table: helper.rows(connection, 'SELECT * FROM `' + table + '` ORDER BY id') for table in helper.TABLES}
    before = PRIVATE / 'facts-start.json'
    if phase == 'start':
        # 首次记录不可重复，防止用修改后的数据替换原始事实掩盖测试副作用。
        assert not before.exists(), '开始事实已存在'
        helper.save(before, current)
    original = json.loads(before.read_text(encoding='utf-8'))
    summary = {}
    for table, expected in original.items():
        # 按原 ID 比对完整行，新账号的新行不被当作违规，但原行变更必须暴露。
        by_id = {row['id']: row for row in current[table]}
        selected = [by_id[row['id']] for row in expected]
        assert helper.digest(selected) == helper.digest(expected), f'{table} 原事实变化'
        summary[table] = {'originalCount': len(expected), 'currentCount': len(current[table]), 'originalSha': helper.digest(selected)}
    helper.save(result, {'phase': phase, 'passed': True, 'tables': summary})
    print(f'原业务事实保护 {phase} 通过')


if __name__ == '__main__':
    main()

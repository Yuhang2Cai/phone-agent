"""R2阻塞收尾事实保护：只读核对714原完整行业务事实，并比对R1全部事实，不创建或消费售后资源。"""
import importlib.util
import json
import os
from pathlib import Path
import pymysql

ROOT = Path('C:/fdz-build')
PRIVATE = ROOT / '.runtime/task7-independent'
PUBLIC = ROOT / 'docs/verification/task7-independent-evidence/r2'
spec = importlib.util.spec_from_file_location('guard', ROOT / 'docs/verification/task7-evidence/Verify-Task7Api.py')
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


def main():
    """只使用行摘要证据；检查R0原714行，公开仅输出计数/SHA，不输出原个人数据。"""
    original = json.loads((PRIVATE / 'original-facts.json').read_text(encoding='utf-8'))
    r1_before = json.loads((PRIVATE / 'r1/before-facts.json').read_text(encoding='utf-8'))
    with pymysql.connect(host='127.0.0.1', user=os.environ['DB_USERNAME'], password=os.environ['DB_PASSWORD'],
                         database='fandazi_groupbuy_dev', charset='utf8mb4', autocommit=True,
                         cursorclass=pymysql.cursors.DictCursor) as connection:
        current = {table: guard.rows(connection, 'SELECT * FROM `' + table + '` ORDER BY id') for table in guard.TABLES}
    facts = {}
    for table, expected in original.items():
        # 原行按ID比完整字节，新增行单独计数，不能只比数量掩盖被改的原事实。
        by_id = {row['id']: row for row in current[table]}
        selected = [by_id[row['id']] for row in expected]
        unchanged = guard.digest(selected) == guard.digest(expected)
        facts[table] = {'originalCount': len(expected), 'currentCount': len(current[table]),
                        'originalUnchanged': unchanged, 'originalSha': guard.digest(selected), 'currentSha': guard.digest(current[table])}
    result = {'originalTotal': sum(len(x) for x in original.values()), 'currentTotal': sum(len(x) for x in current.values()),
              'originalUnchanged': all(x['originalUnchanged'] for x in facts.values()),
              'sameCompleteBusinessFactsAsR1': guard.digest(current) == guard.digest(r1_before), 'tables': facts,
              'note': 'R2发现B2即中止新场景；本次是只读收尾核对，不冒称已亲跑R2真实已有申请'}
    guard.save(PUBLIC / 'facts-release.json', result)
    assert result['originalUnchanged'], '原事实保护失败'
    print('R2原714行保护通过；当前完整行', result['currentTotal'], '与R1一致', result['sameCompleteBusinessFactsAsR1'])


if __name__ == '__main__':
    main()

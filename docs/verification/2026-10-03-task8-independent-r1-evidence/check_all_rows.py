"""R1定向复验数据保护：只读采集两库全部基础表，比较本轮开始时的所有既有行。

本轮快照仅测量修复测试的新副作用，不替换历史基线；完整行、账号及SQL关联只写.runtime。
依赖共享开发数据库环境和pymysql。允许本轮新增行，禁止既有行删除或任何字段变化。
"""
import argparse
import hashlib
import json
import os
from collections import Counter
from datetime import datetime
from pathlib import Path
from urllib.parse import urlsplit

import pymysql

ROOT = Path('C:/fdz-build')
PRIVATE = ROOT / '.runtime/task8-independent-final/r1'
PUBLIC = Path(__file__).parent
DATABASES = ('fandazi_groupbuy_dev', 'dianping_intent')


def canonical(value):
    """保持日期、Decimal与字节的稳定表示，输出用于比较的规范JSON而非公开业务内容。"""
    return json.dumps(value, sort_keys=True, ensure_ascii=False, default=str, separators=(',', ':'))


def save(path, value):
    """首次独占创建证据，禁止覆盖此前通过、失败或历史基线。"""
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, default=str)


def capture():
    """读取已确认本机两库的全部基础表及主键；一致性事务只读，不执行DDL或业务写入。"""
    url = urlsplit(os.environ['DB_URL'].removeprefix('jdbc:'))
    assert url.hostname in ('localhost', '127.0.0.1') and url.path == '/fandazi_groupbuy_dev'
    result = {}
    for database in DATABASES:
        with pymysql.connect(host=url.hostname, port=url.port or 3306, user=os.environ['DB_USERNAME'],
                             password=os.environ['DB_PASSWORD'], database=database, charset='utf8mb4',
                             cursorclass=pymysql.cursors.DictCursor, autocommit=False) as connection:
            with connection.cursor() as cursor:
                cursor.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY')
                cursor.execute("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME", (database,))
                tables = [row['TABLE_NAME'] for row in cursor.fetchall()]
                result[database] = {}
                for name in tables:
                    # 表名仅取服务器元数据，引用前转义反引号；查询参数不包含用户输入。
                    cursor.execute("SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=%s AND TABLE_NAME=%s AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION", (database, name))
                    keys = [row['COLUMN_NAME'] for row in cursor.fetchall()]
                    cursor.execute('SELECT * FROM `' + name.replace('`', '``') + '`')
                    rows = json.loads(canonical(cursor.fetchall()))
                    rows.sort(key=canonical)
                    result[database][name] = dict(primaryKeys=keys, rows=rows)
            connection.rollback()
    return result


def compare(before, after):
    """按完整主键检查既有行；无主键表按完整行多重集，仍不允许旧行丢失或字段改变。"""
    summary, details = {}, {}
    for database, tables in before.items():
        summary[database], details[database] = {}, {}
        assert set(tables) == set(after[database]), '本轮表结构集合变化'
        for name, original in tables.items():
            current = after[database][name]
            assert original['primaryKeys'] == current['primaryKeys'], '主键结构变化'
            keys = original['primaryKeys']
            changes, missing = [], []
            if keys:
                indexed = {canonical([row[key] for key in keys]): row for row in current['rows']}
                old_keys = set()
                for row in original['rows']:
                    identity = canonical([row[key] for key in keys])
                    old_keys.add(identity)
                    value = indexed.get(identity)
                    if value is None:
                        missing.append(row)
                    elif value != row:
                        changes.append(dict(before=row, after=value, fields=[key for key in set(row) | set(value) if row.get(key) != value.get(key)]))
                added = [row for key, row in indexed.items() if key not in old_keys]
            else:
                old_counts, new_counts = Counter(map(canonical, original['rows'])), Counter(map(canonical, current['rows']))
                missing = list((old_counts - new_counts).elements())
                added = list((new_counts - old_counts).elements())
            summary[database][name] = dict(beforeRows=len(original['rows']), afterRows=len(current['rows']), changedExisting=len(changes), missingExisting=len(missing), addedRows=len(added))
            details[database][name] = dict(changes=changes, missing=missing, added=added)
    return summary, details


def main():
    """before只采集，after进行差异与克隆审计；两种模式均保留71→70历史事故不作豁免。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('phase', choices=('before', 'after'))
    phase = parser.parse_args().phase
    current = capture()
    save(PRIVATE / f'all-rows-{phase}.json', current)
    if phase == 'before':
        save(PUBLIC / 'rows-before-summary.json', {'at': datetime.now().astimezone().isoformat(),
             'purpose': 'R1增量测量，不替换原事实基线', 'tables': {db: {name: len(value['rows']) for name, value in tables.items()} for db, tables in current.items()}})
        print('两库全部既有行已只读采集，原历史基线未变。')
        return
    before = json.loads((PRIVATE / 'all-rows-before.json').read_text(encoding='utf-8'))
    summary, details = compare(before, current)
    save(PRIVATE / 'all-rows-difference.json', details)
    passed = all(row['changedExisting'] == 0 and row['missingExisting'] == 0 for tables in summary.values() for row in tables.values())
    # 本轮新增克隆应只消费一份并下架；订单、券、退款保持关联且不删除，完整身份仅留私有差异。
    business = details['fandazi_groupbuy_dev']
    clones = business['deal_packages']['added']
    orders = business['group_buy_orders']['added']
    refunds = business['refund_requests']['added']
    clone_ok = (len(clones) == 1 and clones[0]['title'].startswith('Task8隔离边界-')
                and clones[0]['inventory'] == 1 and not clones[0]['active']
                and len(orders) == 1 and orders[0]['deal_id'] == clones[0]['id']
                and len(refunds) == 1)
    # 再与事故前的不可变原始基线比较，明确历史失败仍存在；这里只读，绝不写回。
    historical = json.loads((ROOT / '.runtime/task8-resumed/facts-start.json').read_text(encoding='utf-8'))
    history_changes = []
    for name, rows in historical.items():
        indexed = {row['id']: row for row in current['fandazi_groupbuy_dev'][name]['rows']}
        for row in rows:
            after = indexed.get(row['id'])
            if after != row:
                history_changes.append(dict(table=name, before=row, after=after))
    save(PRIVATE / 'historical-difference.json', history_changes)
    history_unchanged = (len(history_changes) == 1 and history_changes[0]['table'] == 'deal_packages'
                        and history_changes[0]['before']['inventory'] == 71 and history_changes[0]['after']['inventory'] == 70
                        and {key for key in history_changes[0]['before'] if history_changes[0]['before'][key] != history_changes[0]['after'].get(key)} == {'inventory'})
    result = dict(at=datetime.now().astimezone().isoformat(), incrementalExistingRowsUnchanged=passed,
                  newCloneAndOrderAuditPassed=clone_ok, tables=summary,
                  historicalGuardPassed=False, historical71to70StillPresentAlone=history_unchanged,
                  incidentDisposition='待用户确认，不关闭T8-IND-01')
    save(PUBLIC / 'rows-after-summary.json', result)
    print(json.dumps({key: value for key, value in result.items() if key != 'tables'}, ensure_ascii=False))
    assert passed and clone_ok and history_unchanged, 'R1增量或历史事故审计不符合预期，见私有差异'


if __name__ == '__main__':
    main()

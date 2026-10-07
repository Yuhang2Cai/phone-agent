"""Task8 R2只读审计关闭：验证产品/测试源复用条件和精确事故范围，不实施任何业务写入。

复用R1只读采集器，完整行与关联证据仅保存独立验收.runtime/r2；公开输出只有摘要与计数。
用户保留决定由最终报告引用，本脚本不把原guard失败改写为通过，也不覆盖任何历史基线。
"""
import hashlib
import importlib.util
import json
import subprocess
from datetime import datetime
from pathlib import Path

ROOT = Path('C:/fdz-build')
PRIVATE = ROOT / '.runtime/task8-independent-final/r2'
PUBLIC = Path(__file__).parent
spec = importlib.util.spec_from_file_location('r1_rows', ROOT / 'docs/verification/2026-10-03-task8-independent-r1-evidence/check_all_rows.py')
rows = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rows)


def read(path):
    """读取结构化既有证据，不修改其编码或原始字节。"""
    return json.loads(path.read_text(encoding='utf-8-sig'))


def sha(path):
    """对文件原字节取摘要；返回哈希而不公开凭据或业务原文。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(root, *args):
    """执行只读Git查询；不更新索引、不提交、不导出覆盖已有快照。"""
    return subprocess.check_output(['git', '-C', str(root), *args]).decode('utf-8').strip()


def main():
    """校验固定交接版本，读取两库并精确比对，所有失败原样保留且立即阻止关闭。"""
    assert not PRIVATE.exists(), '禁止覆盖R2证据'
    PRIVATE.mkdir()
    r1 = read(ROOT / 'docs/verification/2026-10-03-task8-independent-r1-evidence/source-closure.json')
    expected_trees = {'C:/fdz-build': '642e90cd5a6750df42886c08fc6cbf4b952a0d12',
                      'D:/project/-Demo-master': '42e45ad09c35e37beadb46769deb10538104574b'}
    allowed_docs = {'docs/superpowers/plans/2026-09-29-group-buying-agent-refactor-implementation.md',
                    'docs/verification/2026-10-01-task-sequence-progress.md',
                    'docs/verification/2026-10-02-task8-resumed-development.md',
                    'docs/experiments/intent-routing-experiments.md'}
    repositories = []
    for repo in r1['repositories']:
        root = Path(repo['repository'])
        tree = git(root, 'write-tree')
        assert tree == expected_trees[repo['repository']] and git(root, 'rev-parse', 'HEAD') == repo['head']
        changes = git(root, 'diff', '--name-only', repo['tree'], tree).splitlines()
        assert set(changes) <= allowed_docs, '产品、测试或其他冻结文件发生变化'
        repositories.append(dict(repository=repo['repository'], head=repo['head'], r1Tree=repo['tree'],
                                 currentTree=tree, changedPaths=changes, productAndTestBlobsUnchanged=True))
    # 在线工作产品沿原冻结摘要核对；拟提交测试则核对R1留下的原始工作字节与索引blob。
    freeze = read(ROOT / 'docs/verification/task8-resumed-evidence/freeze-preacceptance.json')
    working_count = 0
    for repo in freeze['repositories']:
        for entry in repo['files']:
            if not entry['path'].startswith('docs/'):
                assert sha(Path(repo['repository']) / entry['path']).lower() == entry['workingSha256'].lower()
                working_count += 1
    for entry in r1['tests']:
        assert sha(ROOT / entry['path']).lower() == entry['workingSha256'].lower()
        assert git(ROOT, 'rev-parse', ':' + entry['path']) == entry['blob']
    unsafe = ROOT / 'docs/verification/2026-10-02-task8-independent-acceptance-evidence/test_live_write_boundaries_current.py'
    assert sha(unsafe).lower() == r1['historicalFailedCopySha256'].lower()
    originals = read(ROOT / '.runtime/task8-resumed/old-json-start.json')
    assert all(sha(Path(entry['path'])).lower() == entry['sha256'].lower() for entry in originals)
    reports = [ROOT / 'docs/verification/2026-10-02-task8-independent-acceptance.md',
               ROOT / 'docs/verification/2026-10-03-task8-independent-r1.md']
    report_hashes = {str(path): sha(path) for path in reports}

    # R1结束事实仅用于检测之后是否又有变化；原事故前基线继续单独校验，不能替换。
    current = rows.capture()
    rows.save(PRIVATE / 'all-rows-current.json', current)
    before = read(ROOT / '.runtime/task8-independent-final/r1/all-rows-after.json')
    summary, details = rows.compare(before, current)
    rows.save(PRIVATE / 'since-r1-difference.json', details)
    unchanged = all(item['changedExisting'] == 0 and item['missingExisting'] == 0 and item['addedRows'] == 0
                    for tables in summary.values() for item in tables.values())
    historical = read(ROOT / '.runtime/task8-resumed/facts-start.json')
    differences = []
    for name, old_rows in historical.items():
        indexed = {row['id']: row for row in current['fandazi_groupbuy_dev'][name]['rows']}
        for row in old_rows:
            after = indexed.get(row['id'])
            if after != row:
                differences.append(dict(table=name, before=row, after=after))
    rows.save(PRIVATE / 'original-baseline-difference.json', differences)
    incident = read(ROOT / '.runtime/task8-independent-final/r1/historical-difference.json')
    exact_incident = differences == incident and len(differences) == 1
    assert exact_incident and differences[0]['table'] == 'deal_packages'
    assert differences[0]['before']['inventory'] == 71 and differences[0]['after']['inventory'] == 70
    # 精确核对R0关联事故订单仍保留；用户只接受该笔事实，不允许套用到其他资源。
    r0 = read(ROOT / '.runtime/task8-independent-final/fact-difference.json')
    incident_orders = r0['newOrdersOnChangedDeals']
    indexed_orders = {row['id']: row for row in current['fandazi_groupbuy_dev']['group_buy_orders']['rows']}
    retained = len(incident_orders) == 1 and all(
        indexed_orders.get(row['id']) == {key: value for key, value in row.items() if key != 'username'}
        for row in incident_orders)
    result = dict(at=datetime.now().astimezone().isoformat(), repositories=repositories,
                  unchangedWorkingProductFiles=working_count, unchangedR1TestFiles=2,
                  dangerousHistoricalCopyUnchanged=True, dangerousHistoricalCopyRunnableEntry=False,
                  oldPrivateJsonUnchanged=len(originals), unchangedHistoricalReportHashes=report_hashes,
                  allRowsSinceR1Unchanged=unchanged, tables=summary,
                  originalGuardPassed=False, originalBaselineUnmodified=True,
                  exactPreviouslyDisclosedIncidentOnly=exact_incident, incidentOrderRetainedUnchanged=retained,
                  exceptionDecision='用户明确决定保留已披露订单及库存71至70，作为审计例外；不回填、不删除、不改失败历史')
    rows.save(PUBLIC / 'audit.json', result)
    assert unchanged and retained, 'R1后出现额外差异或事故订单变化，不得关闭'
    print(json.dumps({key: value for key, value in result.items() if key not in ('tables', 'repositories', 'unchangedHistoricalReportHashes')}, ensure_ascii=False))


if __name__ == '__main__':
    main()

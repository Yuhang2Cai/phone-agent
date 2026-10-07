"""R2公开证据保密及历史保持检查：不运行测试、不触碰业务数据，仅核验文本、摘要与冻结索引。

私有快照只提供内存匹配候选，任何命中只输出路径；原报告必须保持原样或严格追加。
"""
import importlib.util
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path('C:/fdz-build')
PUBLIC = Path(__file__).parent
PRIVATE = ROOT / '.runtime/task8-independent-final/r2'
spec = importlib.util.spec_from_file_location('r1_scan', ROOT / 'docs/verification/2026-10-03-task8-independent-r1-evidence/scan_r1_public.py')
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


def main():
    """验证报告未被重写与索引未变，随后检查公开文本，输出不含秘密的收尾证据。"""
    import hashlib
    audit = json.loads((PUBLIC / 'audit.json').read_text(encoding='utf-8'))
    for path, expected in audit['unchangedHistoricalReportHashes'].items():
        assert hashlib.sha256(Path(path).read_bytes()).hexdigest() == expected
    history = ROOT / 'docs/verification/task8-acceptance.md'
    assert history.read_bytes().startswith((PRIVATE / 'task8-acceptance-before-final-append.md').read_bytes())
    for repo in audit['repositories']:
        assert subprocess.check_output(['git', '-C', repo['repository'], 'write-tree']).decode().strip() == repo['currentTree']
    # 候选仅来自运行私有资料，不从源码资源审计中的token字段提取，避免把颜色名误判为密钥。
    candidates = set()
    paths = list((ROOT / '.runtime/task8-resumed').rglob('*.json')) + list(PRIVATE.glob('*.json')) + [ROOT / '.runtime/service-secrets.json']
    for path in paths:
        try:
            value = json.loads(path.read_text(encoding='utf-8-sig'))
            helper.scanner.collect(value, candidates)
            helper.collect_names(value, candidates)
        except (UnicodeError, ValueError):
            continue
    for name in ('DB_PASSWORD', 'JWT_SECRET', 'AGENT_INTERNAL_TOKEN', 'OPENAI_API_KEY'):
        value = os.environ.get(name)
        if value and len(value) >= 8:
            candidates.add(value)
    counts, hits = {'stagedTexts': 0, 'r2Texts': 0}, []

    def inspect(path, data, category):
        """只报告命中类别与路径，禁止把候选原文带入公开结果。"""
        if b'\0' in data:
            return
        content = data.decode('utf-8', errors='replace')
        counts[category] += 1
        if any(value in content for value in candidates) or re.search(r'eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}', content):
            hits.append({'path': str(path), 'category': 'privateValueOrJwt'})

    for root in helper.scanner.REPOS:
        staged = subprocess.check_output(['git', 'diff', '--cached', '--name-only', '-z', '--diff-filter=ACMR'], cwd=root).decode().split('\0')
        for path in filter(None, staged):
            inspect(root / path, subprocess.check_output(['git', 'show', ':' + path], cwd=root), 'stagedTexts')
    for path in list(PUBLIC.glob('*')) + [PUBLIC.parent / '2026-10-03-task8-independent-final.md', history]:
        if path.is_file() and path.name != 'secret-scan.json':
            inspect(path, path.read_bytes(), 'r2Texts')
    result = dict(**counts, passed=not hits, hits=hits, historicalReportsUnchanged=True,
                  historicalSummaryAppendOnly=True, indexTreesUnchanged=True, secretsPrinted=False)
    with (PUBLIC / 'secret-scan.json').open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False))
    assert not hits, '公开文本存在保密问题，不能释放最终通过'


if __name__ == '__main__':
    main()

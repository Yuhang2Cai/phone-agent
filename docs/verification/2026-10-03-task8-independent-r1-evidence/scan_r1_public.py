"""R1公开证据保密检查：从真实私有夹具和本轮数据库快照提取候选，只扫描本轮公开文本及暂存源。

不将源码内的资源token当作秘密；命中仅报告路径和类别，原值不输出、不提交。
"""
import importlib.util
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path('C:/fdz-build')
PUBLIC = Path(__file__).parent
PRIVATE = ROOT / '.runtime/task8-independent-final/r1'
spec = importlib.util.spec_from_file_location('scanner', ROOT / 'docs/verification/task8-resumed-evidence/Scan-StagedSecrets.py')
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)


def collect_names(value, candidates):
    """递归采集登录名作为不可公开值，协议字段名和一般描述不参与匹配。"""
    if isinstance(value, dict):
        for key, item in value.items():
            if key.lower() == 'username' and isinstance(item, str) and len(item) >= 8:
                candidates.add(item)
            collect_names(item, candidates)
    elif isinstance(value, list):
        for item in value:
            collect_names(item, candidates)


def main():
    """扫描实际私有数据候选与JWT格式；保存一次安全摘要，任何命中则失败。"""
    candidates, hits = set(), []
    private_files = list((ROOT / '.runtime/task8-resumed').rglob('*.json')) + list(PRIVATE.glob('*.json')) + [ROOT / '.runtime/service-secrets.json']
    for path in private_files:
        try:
            value = json.loads(path.read_text(encoding='utf-8-sig'))
            scanner.collect(value, candidates)
            collect_names(value, candidates)
        except (UnicodeError, ValueError):
            continue
    for name in ('DB_PASSWORD', 'JWT_SECRET', 'AGENT_INTERNAL_TOKEN', 'OPENAI_API_KEY'):
        value = os.environ.get(name)
        if value and len(value) >= 8:
            candidates.add(value)
    counts = dict(stagedTexts=0, r1Texts=0)

    def inspect(path, data, category):
        """只对文本进行内存匹配，不把秘密放入错误消息或公开扫描结果。"""
        if b'\0' in data:
            return
        counts[category] += 1
        content = data.decode('utf-8', errors='replace')
        if any(value in content for value in candidates):
            hits.append(dict(path=str(path), category='privateValue'))
        if re.search(r'eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}', content):
            hits.append(dict(path=str(path), category='jwtPattern'))

    for root in scanner.REPOS:
        paths = subprocess.check_output(['git', 'diff', '--cached', '--name-only', '-z', '--diff-filter=ACMR'], cwd=root).decode().split('\0')
        for path in filter(None, paths):
            inspect(root / path, subprocess.check_output(['git', 'show', ':' + path], cwd=root), 'stagedTexts')
    for path in list(PUBLIC.glob('*')) + [PUBLIC.parent / '2026-10-03-task8-independent-r1.md']:
        if path.is_file() and path.name != 'secret-scan.json':
            inspect(path, path.read_bytes(), 'r1Texts')
    result = dict(**counts, passed=not hits, hits=hits, secretsPrinted=False)
    with (PUBLIC / 'secret-scan.json').open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False))
    assert not hits, 'R1公开内容存在敏感候选命中'


if __name__ == '__main__':
    main()

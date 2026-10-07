"""独立证据保密检查：只读扫描两仓暂存文本和本次公开新增文本。

候选秘密与测试登录名仅在内存中匹配；公开结果只记录数量、命中类别与路径，不写任何秘密。
"""
import importlib.util
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path('C:/fdz-build')
PUBLIC = Path(__file__).parent
spec = importlib.util.spec_from_file_location('scanner', ROOT / 'docs/verification/task8-resumed-evidence/Scan-StagedSecrets.py')
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)


def login_names(value, candidates):
    """只收集明示的登录字段，不把商户名、业务状态或普通代码标识当作凭据。"""
    if isinstance(value, dict):
        for key, item in value.items():
            if key.lower() == 'username' and isinstance(item, str) and len(item) >= 8:
                candidates.add(item)
            login_names(item, candidates)
    elif isinstance(value, list):
        for item in value:
            login_names(item, candidates)


def main():
    """采集私有候选后按字节解码文本；只输出安全摘要，任何命中即返回失败。"""
    candidates = set()
    for directory in (ROOT / '.runtime/task8-resumed', ROOT / '.runtime/task8-independent-final'):
        for path in directory.rglob('*.json'):
            # 快照中的公开资源审计也有token键，含义是颜色资源名而非秘密；只保留其.runtime运行产物。
            relative = path.relative_to(directory).parts
            snapshot_source = relative[0] == 'phone' and (len(relative) < 2 or relative[1] != '.runtime')
            if snapshot_source or any(part in ('node_modules', 'build', 'target', '-Demo-master') for part in path.parts):
                continue
            try:
                value = json.loads(path.read_text(encoding='utf-8-sig'))
                scanner.collect(value, candidates)
                login_names(value, candidates)
            except (UnicodeError, ValueError):
                continue
    for name in ('DB_PASSWORD', 'JWT_SECRET', 'AGENT_INTERNAL_TOKEN', 'OPENAI_API_KEY'):
        value = os.environ.get(name)
        if value and len(value) >= 8:
            candidates.add(value)
    hits, counts = [], {'stagedTexts': 0, 'independentTexts': 0}

    def inspect(path, data, category):
        """检查单份文本的候选秘密和JWT模式；报告绝不包含命中原文。"""
        if b'\0' in data:
            return
        content = data.decode('utf-8', errors='replace')
        counts[category] += 1
        if any(secret in content for secret in candidates):
            hits.append(dict(path=str(path), category='privateValue'))
        if re.search(r'eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}', content):
            hits.append(dict(path=str(path), category='jwtPattern'))

    for root in scanner.REPOS:
        paths = subprocess.check_output(['git', 'diff', '--cached', '--name-only', '-z', '--diff-filter=ACMR'], cwd=root).decode().split('\0')
        for path in filter(None, paths):
            inspect(root / path, subprocess.check_output(['git', 'show', ':' + path], cwd=root), 'stagedTexts')
    for path in list(PUBLIC.glob('*')) + [PUBLIC.parent / '2026-10-02-task8-independent-acceptance.md', PUBLIC.parent / 'task8-acceptance.md']:
        if path.is_file() and path.name != 'independent-secret-scan.json':
            inspect(path, path.read_bytes(), 'independentTexts')
    result = dict(**counts, passed=not hits, hits=hits, secretsPrinted=False)
    # 首轮将公开资源token误收为秘密的证据不覆盖，本次只更正候选来源范围。
    target = PUBLIC / 'independent-secret-scan-r1.json'
    assert not target.exists(), '禁止覆盖独立保密检查证据'
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))
    assert not hits, '公开文本存在敏感值命中'


if __name__ == '__main__':
    main()

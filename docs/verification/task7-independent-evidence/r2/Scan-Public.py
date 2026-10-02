"""R2公开证据秘密扫描：只读本轮凭据检查报告/文本，不轮换账号，保留R0正文及R1历史摘要。"""
import hashlib
import json
import os
import re
from pathlib import Path

ROOT = Path('C:/fdz-build')
PUBLIC = ROOT / 'docs/verification/task7-independent-evidence'
PRIVATE = ROOT / '.runtime/task7-independent'


def main():
    """扫描已知秘密和JWT形态；失败仅输出文件路径，不暴露任何值。"""
    report = ROOT / 'docs/verification/2026-10-02-task7-independent-acceptance.md'
    body = report.read_text(encoding='utf-8-sig')
    r0 = body.split('## R1 独立受影响复验进度')[0].rstrip() + '\n'
    r1 = body.split('## R2 独立定向验收进度')[0].rstrip() + '\n'
    sha = hashlib.sha256(r0.encode('utf-8')).hexdigest().upper()
    preserved = {'r0SectionSha256': sha, 'r0Unchanged': sha == '5CFD97D99619C4B7623823DEA31FD73543A77D9F2832421F2CB501E357042C2F',
                 'r0R1PrefixSha256': hashlib.sha256(r1.encode('utf-8')).hexdigest().upper(), 'method': '仅追加R2，R0摘要与原终版比较；R0/R1合并摘要供后续复验保护'}
    (PUBLIC / 'r2/history-preserved.json').write_text(json.dumps(preserved, ensure_ascii=False, indent=2), encoding='utf-8')
    assert preserved['r0Unchanged'], 'R0正文被改'
    secrets = [json.loads((PRIVATE / (role + '.json')).read_text(encoding='utf-8'))['password'] for role in ('account', 'outsider', 'empty')]
    secrets += [value for key, value in os.environ.items() if any(word in key.upper() for word in ('PASSWORD', 'SECRET', 'API_KEY'))]
    checked = 0
    failures = []
    # 私有原始行和命令树不导出；扫描范围只含公开文本以及独立报告。
    for path in list(PUBLIC.rglob('*')) + [report]:
        if not path.is_file() or path.suffix.lower() not in ('.json', '.xml', '.md', '.ps1', '.py', '.txt'):
            continue
        text = path.read_text(encoding='utf-8-sig', errors='replace')
        known = any(value and len(value) >= 8 and value in text for value in secrets)
        jwt = bool(re.search(r'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}', text))
        if known or jwt:
            failures.append({'path': str(path), 'knownSecret': known, 'jwtShape': jwt})
        checked += 1
    result = {'checkedTextFiles': checked, 'secretMatches': sum(x['knownSecret'] for x in failures),
              'jwtMatches': sum(x['jwtShape'] for x in failures), 'failures': failures}
    (PUBLIC / 'r2/secret-scan.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    assert not failures, '公开证据秘密检查失败'
    print('R2公开扫描', checked, '文本，秘密/JWT匹配0')


if __name__ == '__main__':
    main()

"""R1独立公开证据脱敏检查：只读已知本轮秘密，检查所有公开文本，不轮换账号或写业务库。"""
import hashlib
import json
import os
import re
from pathlib import Path

ROOT = Path('C:/fdz-build')
PUBLIC = ROOT / 'docs/verification/task7-independent-evidence'
PRIVATE = ROOT / '.runtime/task7-independent'


def main():
    """无参数；输出检查计数，不输出凭据，发现匹配时保留失败路径并抛出异常。"""
    secrets = [json.loads((PRIVATE / (role + '.json')).read_text(encoding='utf-8'))['password']
               for role in ('account', 'outsider', 'empty')]
    secrets += [value for key, value in os.environ.items()
                if any(word in key.upper() for word in ('PASSWORD', 'SECRET', 'API_KEY'))]
    report = ROOT / 'docs/verification/2026-10-02-task7-independent-acceptance.md'
    # 只允许R1追加，按R0原始UTF8/LF边界重建摘要，避免把原FAIL或首次失败历史悄然覆盖。
    r0_text = report.read_text(encoding='utf-8-sig').split('## R1 独立受影响复验进度')[0].rstrip() + '\n'
    actual_sha = hashlib.sha256(r0_text.encode('utf-8')).hexdigest().upper()
    expected_sha = '5CFD97D99619C4B7623823DEA31FD73543A77D9F2832421F2CB501E357042C2F'
    preserved = {'actualR0SectionSha': actual_sha, 'expectedR0ReportSha': expected_sha,
                 'unchanged': actual_sha == expected_sha}
    (PUBLIC / 'r1/r0-report-final-preserved.json').write_text(json.dumps(preserved, indent=2), encoding='utf-8')
    assert preserved['unchanged'], 'R0报告正文变化，见保留摘要'
    checked = 0
    failures = []
    # 检查R0与新增R1公开文本；私有日志/命令树不复制，图片内既不包含登录口令也不导出JWT。
    for path in list(PUBLIC.rglob('*')) + [report]:
        if not path.is_file() or path.suffix.lower() not in ('.json', '.xml', '.md', '.ps1', '.py', '.txt'):
            continue
        text = path.read_text(encoding='utf-8-sig', errors='replace')
        secret_match = any(value and len(value) >= 8 and value in text for value in secrets)
        jwt_match = bool(re.search(r'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}', text))
        if secret_match or jwt_match:
            failures.append({'path': str(path), 'knownSecret': secret_match, 'jwtShape': jwt_match})
        checked += 1
    output = {'checkedTextFiles': checked, 'secretMatches': sum(x['knownSecret'] for x in failures),
              'jwtMatches': sum(x['jwtShape'] for x in failures), 'failures': failures}
    (PUBLIC / 'r1/secret-scan.json').write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding='utf-8')
    assert not failures, '公开证据秘密检查失败，见路径记录'
    print(f'独立R1公开秘密扫描：{checked}文本、秘密/JWT匹配0')


if __name__ == '__main__':
    main()

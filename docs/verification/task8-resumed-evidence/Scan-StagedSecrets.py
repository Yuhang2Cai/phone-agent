"""Task8 提交前保密检查：扫描两仓暂存文本，不读取混合工作区作为交付源。

私有夹具只用于提取敏感值，报告仅记录命中路径与类别，不公开任何凭据。
"""
import json
import os
import re
import subprocess
from pathlib import Path


ROOT = Path('C:/fdz-build')
REPOS = (ROOT, Path('D:/project/-Demo-master'))
SECRET_KEYS = {'password', 'token', 'confirmationgrant', 'confirmationgrantid',
               'grant_id', 'grantid', 'internaltoken', 'agentinternaltoken',
               'jwtsecret', 'jwt_secret', 'dbpassword', 'apikey', 'db_password'}


def collect(value, candidates):
    """递归提取明确敏感字段；非敏感业务 ID 不作为凭据，避免误报。"""
    if isinstance(value, dict):
        for key, item in value.items():
            if key.lower() in SECRET_KEYS and isinstance(item, str) and len(item) >= 8:
                candidates.add(item)
            collect(item, candidates)
    elif isinstance(value, list):
        for item in value:
            collect(item, candidates)


def main():
    """读取私有材料后扫描暂存 blob；有命中即失败，输出不含秘密的摘要。"""
    candidates = set()
    for path in (ROOT / '.runtime/task8-resumed').rglob('*.json'):
        try:
            collect(json.loads(path.read_text(encoding='utf-8-sig')), candidates)
        except (UnicodeError, ValueError):
            # 调试产物可能不是有效 JSON；不修改原文件，继续检查其他私有材料。
            continue
    for name in ('DB_PASSWORD', 'JWT_SECRET', 'AGENT_INTERNAL_TOKEN', 'OPENAI_API_KEY'):
        value = os.environ.get(name)
        if value and len(value) >= 8:
            candidates.add(value)
    hits = []
    scanned = 0
    for root in REPOS:
        paths = subprocess.check_output(['git', 'diff', '--cached', '--name-only', '-z',
                                         '--diff-filter=ACMR'], cwd=root).decode('utf-8').split('\0')
        for path in filter(None, paths):
            data = subprocess.check_output(['git', 'show', ':' + path], cwd=root)
            if b'\0' in data:
                continue
            content = data.decode('utf-8', errors='replace')
            scanned += 1
            # 仅报告类别与相对路径，避免检测工具把发现的秘密再次写入公开日志。
            if any(secret in content for secret in candidates):
                hits.append({'repo': root.name, 'path': path, 'category': 'privateCredential'})
            if re.search(r'eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}', content):
                hits.append({'repo': root.name, 'path': path, 'category': 'jwtPattern'})
    result = {'scannedFiles': scanned, 'candidateCount': len(candidates), 'hits': hits,
              'passed': not hits, 'secretsPrinted': False}
    target = ROOT / 'docs/verification/task8-resumed-evidence/staged-secret-scan.json'
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))
    assert not hits, '暂存内容发现敏感值，必须处理后重新检查'


if __name__ == '__main__':
    main()

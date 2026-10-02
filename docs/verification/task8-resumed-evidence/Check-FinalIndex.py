"""Task8 精确提交检查：核对已验收产品 blob，并扫描两仓实际暂存文本中的私有值。

只读 Git 与私有材料，公开输出只含计数、路径和类别；不操作设备、服务或数据库。
"""
import importlib.util
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path('C:/fdz-build')
SPEC = importlib.util.spec_from_file_location('r1_scanner', ROOT / 'docs/verification/2026-10-03-task8-independent-r1-evidence/scan_r1_public.py')
HELPER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPER)
# 这些树由原验收者在最终 R2 只读检查中确认；文档可追加，产品与测试不能无复验变更。
AUDITED = {ROOT: '642e90cd5a6750df42886c08fc6cbf4b952a0d12',
           Path('D:/project/-Demo-master'): '42e45ad09c35e37beadb46769deb10538104574b'}


def main():
    """参数为唯一报告名；任何源码越界或敏感值命中立即失败，不覆盖旧证据。"""
    label = sys.argv[1]
    assert re.fullmatch(r'[a-z0-9-]+', label)
    output = ROOT / f'docs/verification/task8-resumed-evidence/{label}.json'
    assert not output.exists(), '禁止覆盖旧保密检查记录'
    candidates, hits, count = set(), [], 0
    files = list((ROOT / '.runtime/task8-resumed').rglob('*.json'))
    private = ROOT / '.runtime/task8-independent-final'
    files += list(private.glob('*.json')) + list((private / 'r1').glob('*.json')) + list((private / 'r2').glob('*.json'))
    files.append(ROOT / '.runtime/service-secrets.json')
    for path in files:
        try:
            data = json.loads(path.read_text(encoding='utf-8-sig'))
            HELPER.scanner.collect(data, candidates)
            HELPER.collect_names(data, candidates)
        except (UnicodeError, ValueError):
            # 原始调试材料有时不是合法 JSON；保留原文件，继续处理其余实际私有候选。
            continue
    for key in ('DB_PASSWORD', 'JWT_SECRET', 'AGENT_INTERNAL_TOKEN', 'OPENAI_API_KEY'):
        value = os.environ.get(key)
        if value and len(value) >= 8:
            candidates.add(value)
    for root, tree in AUDITED.items():
        changed = subprocess.check_output(['git', 'diff', '--cached', tree, '--name-only', '-z'], cwd=root).decode().split('\0')
        assert all(not path or path.startswith('docs/') for path in changed), '已验收产品或测试源发生变化'
        paths = subprocess.check_output(['git', 'diff', '--cached', '--name-only', '-z', '--diff-filter=ACMR'], cwd=root).decode().split('\0')
        for path in filter(None, paths):
            assert '.runtime/' not in path and '__pycache__' not in path
            assert not path.endswith('test_live_write_boundaries_current.py'), '危险历史副本只允许以禁用文本归档'
            data = subprocess.check_output(['git', 'show', ':' + path], cwd=root)
            if b'\0' in data:
                continue
            count += 1
            content = data.decode('utf-8', errors='replace')
            # 不公开匹配内容，防止检查本身泄漏凭据；JWT 模式补充未知令牌检测。
            if any(secret in content for secret in candidates) or re.search(r'eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}', content):
                hits.append({'repo': root.name, 'path': path, 'category': 'privateValueOrJwt'})
    result = {'passed': not hits, 'stagedTextCount': count, 'hits': hits,
              'auditedProductUnchanged': True, 'secretsPrinted': False}
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))
    assert not hits, '暂存内容存在敏感值，不能提交'


if __name__ == '__main__':
    main()

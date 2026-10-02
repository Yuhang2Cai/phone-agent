"""独立验收的真实数据复核入口，复用既有断言并绑定本次独占夹具。

依赖 httpx、pymysql 和历史验收辅助模块；不修改产品代码，不覆盖旧清单。
退款完整响应只在内存比较，公开证据仅保存检查名称和布尔结果。
"""
import argparse
import importlib.util
import json
from pathlib import Path

ROOT = Path('C:/fdz-build')
PUBLIC = Path(__file__).parent
PRIVATE = ROOT / '.runtime/task8-independent-final'
MANIFEST = ROOT / '.runtime/task8-resumed/fixtures-independent-final-r0.json'


def main():
    """选择只读退款复核阶段；输出已验证计数，失败仍保留已执行断言且不继续写业务。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('phase', choices=['refund'])
    parser.parse_args()
    output = PUBLIC / 'refund-api-current.json'
    assert not output.exists(), '禁止覆盖本次复核证据'
    spec = importlib.util.spec_from_file_location('historical_refund', ROOT / 'docs/verification/task8-resumed-evidence/verify_refund.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    # 替换夹具数据源，不改原断言和运行接口；旧开发清单不作为本次 UI 写入证据。
    module.SCENARIOS = {row['scenario']: row for row in json.loads(MANIFEST.read_text(encoding='utf-8-sig'))['scenarios']}
    checks = []

    def record(name, actual, expected):
        """完整值只在内存校验，避免订单编号、原因和接口响应写入公开报告。"""
        checks.append({'name': name, 'passed': actual == expected})
        output.write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding='utf-8')
        assert actual == expected, name

    module.check = record
    module.checks = checks
    module.main()


if __name__ == '__main__':
    main()

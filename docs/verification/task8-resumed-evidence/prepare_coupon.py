"""Task8 新批次八券夹具入口：复用既有校验器，只改变新私有清单位置，不覆盖旧日期或领取事实。"""
import argparse
import importlib.util
import subprocess
import sys
from pathlib import Path


def main():
    """校验唯一批次名和忽略路径后执行既有真实创建/核验流程；凭据不输出。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('name')
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    assert args.name and all(c.isascii() and (c.isalnum() or c == '-') for c in args.name)
    root = Path('C:/fdz-build')
    manifest = root / '.runtime/task8-resumed' / ('coupons-' + args.name + '.json')
    # 只使用新任务的忽略目录；首写保护仍由原校验器实施，日期变化必须创建新批次而非修改旧券。
    assert subprocess.run(['git', 'check-ignore', '--quiet', str(manifest)], cwd=root).returncode == 0
    directory = root / 'docs/verification/task8-service-evidence'
    sys.path.insert(0, str(directory))
    spec = importlib.util.spec_from_file_location('coupon_fixture', directory / 'create_coupon_ui_fixture.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.MANIFEST = manifest
    sys.argv = [str(directory / 'create_coupon_ui_fixture.py')] + (['--verify-only'] if args.verify_only else [])
    return module.main()


if __name__ == '__main__':
    raise SystemExit(main())

"""R3隔离审计入口：复用已校正的只读Git/blob审计逻辑，独立保存新树证据，不覆盖R2历史。"""
import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path('C:/fdz-build')
PUBLIC = ROOT / 'docs/verification/task7-independent-evidence/r3'
spec = importlib.util.spec_from_file_location('stable_auditor', ROOT / 'docs/verification/task7-independent-evidence/r2/Audit-Snapshot.py')
auditor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(auditor)
auditor.META = ROOT / 'docs/verification/task7-r3-evidence'
auditor.PUBLIC = PUBLIC


def main():
    """输入start-retry/end；文件名沿用旧验证器约定，R3首跑没有发生重试或掩盖首次失败。"""
    auditor.main()
    if sys.argv[1] == 'start-retry':
        old = json.loads((ROOT / 'docs/verification/task7-independent-evidence/r2/snapshot-end.json').read_text(encoding='utf-8-sig'))
        new = json.loads((PUBLIC / 'snapshot-start-retry.json').read_text(encoding='utf-8-sig'))
        # 比较所有Android tree文件，而非只比较拟提交增量，确认构建配置、测试与资源全部未变。
        old_app = {x['path']: x['blob'] for x in old['repos']['phone']['files'] if x['path'].startswith('android-app/')}
        new_app = {x['path']: x['blob'] for x in new['repos']['phone']['files'] if x['path'].startswith('android-app/')}
        result = {'passed': old_app == new_app, 'androidTreeFiles': len(new_app), 'r2AndroidTestsReused': 69,
                  'r3PhoneTree': new['repos']['phone']['tree'], 'r2PhoneTree': old['repos']['phone']['tree']}
        (PUBLIC / 'android-reuse.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        assert result['passed'], 'Android源变化，不可复用69项'
        print('R3 Android完整tree源与R2一致，复用69项')


if __name__ == '__main__':
    main()

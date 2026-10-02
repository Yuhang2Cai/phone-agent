"""R3 Agent环境修复副本审计：同一index tree改用原测试要求的目录名，无源码或断言修改。"""
import importlib.util
from pathlib import Path

ROOT = Path('C:/fdz-build')
spec = importlib.util.spec_from_file_location('same_tree_auditor', ROOT / 'docs/verification/task7-independent-evidence/r2/Audit-Snapshot.py')
auditor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(auditor)
auditor.META = ROOT / 'docs/verification/task7-r3-evidence'
auditor.PUBLIC = ROOT / 'docs/verification/task7-independent-evidence/r3/agent-environment-validation'
auditor.SNAPSHOTS['agent'] = ROOT / '.runtime/task7-independent/r3/agent-source/-Demo-master'

if __name__ == '__main__':
    auditor.main()

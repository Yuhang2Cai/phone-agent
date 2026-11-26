"""AG07双应用发布编排：受信适配器提供事实，先维护排空，再配对切换及业务回读。

默认没有生产适配器，不执行任何外部命令。适配器必须限定自有资源、使用单调截止时间，
并将无法证明的事实报告为失败；本模块不把缺失计数、健康200或强杀当安全完成。
"""
from dataclasses import dataclass
import re
import weakref
from operations.release_manifest import CheckedRelease, validate_release, digest


_ISSUED = weakref.WeakKeyDictionary()
"""维护凭证只能来自本进程真实编排，弱引用避免弃用凭证持续占用内存。"""
DRAIN_SECONDS = 120
"""单次排空上限；超时保持入口关闭，禁止自动强杀或再开一轮。"""


@dataclass(frozen=True, eq=False)
class MaintenanceReceipt:
    """仅限同一受信后端、同一精确计划的一次停写凭证，序列化副本不可用于执行。"""
    plan_sha256: str
    stopped_at: float
    observed_sha256: str


def _revalidate(plan, backend):
    """每个有副作用阶段前重读产物/目标，拒绝伪造检查对象及检查后漂移。"""
    if not isinstance(plan, CheckedRelease):
        raise ValueError('checked_release_required')
    current = validate_release(plan.manifest(), backend.observe())
    if current != plan:
        raise ValueError('release_observation_changed')


def _before_deadline(backend, deadline):
    """适配器调用前后均核期限，避免返回过期成功后继续开放。"""
    if backend.clock() >= deadline:
        raise TimeoutError('release_deadline_exceeded')


def enter_maintenance(plan, backend):
    """关闭新普通输入、保留核查通路并排空，正常停止写者后签发一次凭证。

失败不恢复入口，不切换产物；后端负责每次有界操作的真实资源隔离和原子性。
"""
    _revalidate(plan, backend)
    deadline = backend.clock() + DRAIN_SECONDS
    proof = backend.close_ingress(plan)
    if (not isinstance(proof, dict) or proof.get('owner') != plan.manifest()['owner']
            or any(proof.get(k) is not True for k in
                   ('ordinary_closed', 'control_open', 'exclusive_caller'))):
        raise ValueError('ingress_isolation_unproven')
    backend.maintenance(True)
    # 未知写必须先按原执行键核实，不能靠继续等待或重发业务解决。
    fields = {'ordinary', 'control', 'sync_workers', 'unknown_writes', 'background_writers'}
    while True:
        _before_deadline(backend, deadline)
        state = backend.drain_state()
        if (not isinstance(state, dict) or set(state) != fields
                or any(type(v) is not int or v < 0 for v in state.values())):
            raise ValueError('drain_state_incomplete')
        if state['unknown_writes']:
            raise ValueError('unknown_write_requires_original_receipt')
        if not any(state.values()):
            break
        backend.sleep(min(1, deadline - backend.clock()))
    # 排空不等于写者永久停止；关闭正常写者后才允许建立备份水位。
    stopped = backend.stop_writers(deadline)
    _before_deadline(backend, deadline)
    if not isinstance(stopped, dict) or stopped.get('stopped') is not True or stopped.get('forced') is not False:
        raise ValueError('writers_not_gracefully_stopped')
    receipt = MaintenanceReceipt(digest(plan.manifest()), backend.clock(), plan.observed_sha256)
    _ISSUED[receipt] = backend
    return receipt


def apply_release(plan, maintenance_receipt, backend):
    """消费一次停写凭证并切换固定产物；失败保持关闭，绝不自动重试或数据库降级。"""
    receipt = maintenance_receipt
    if (not isinstance(receipt, MaintenanceReceipt) or _ISSUED.get(receipt) is not backend
            or receipt.plan_sha256 != digest(plan.manifest())):
        raise ValueError('invalid_maintenance_receipt')
    # 在任何可能失败的外部动作前消费凭证；部分切换失败也不能重复使用同一轮。
    del _ISSUED[receipt]
    _revalidate(plan, backend)
    _before_deadline(backend, receipt.stopped_at + DRAIN_SECONDS)
    _require_stopped_state(backend)
    backup = backend.backup_receipt()
    if (not isinstance(backup, dict) or backup.get('owner') != plan.manifest()['owner']
            or backup.get('verified') is not True or backup.get('source_unchanged') is not True
            or not isinstance(backup.get('set_sha256'), str)
            or re.fullmatch('[0-9a-f]{64}', backup['set_sha256']) is None):
        raise ValueError('verified_consistent_backup_required')
    _before_deadline(backend, receipt.stopped_at + DRAIN_SECONDS)
    _require_stopped_state(backend)
    deadline = backend.clock() + DRAIN_SECONDS
    switched = backend.switch_pair(plan, deadline)
    _before_deadline(backend, deadline)
    if (not isinstance(switched, dict) or switched.get('agent') is not True
            or switched.get('spring') is not True or switched.get('database_downgrade') is not False):
        raise ValueError('paired_switch_incomplete')
    verified = backend.verify_pair(plan, deadline)
    _before_deadline(backend, deadline)
    checks = ('ready', 'original_receipt', 'unchanged_business',
              'confirmation_not_revived', 'recovery_budget_preserved')
    if not isinstance(verified, dict) or any(verified.get(k) is not True for k in checks):
        raise ValueError('release_business_verification_failed')
    # 只有真实业务核查全部通过才恢复准入；恢复失败仍不能输出发布成功。
    backend.maintenance(False)
    backend.open_ingress(plan)
    return {'status': 'released', 'release_id': plan.manifest()['release_id'],
            'plan_sha256': digest(plan.manifest()), 'backup_sha256': backup['set_sha256'],
            'database_downgrade': False}


def _require_stopped_state(backend):
    """停写凭证不豁免后续事实核查；备份前后所有写者必须持续为零。"""
    state=backend.drain_state()
    fields={'ordinary','control','sync_workers','unknown_writes','background_writers'}
    if (not isinstance(state,dict) or set(state)!=fields
            or any(type(value) is not int or value!=0 for value in state.values())):
        raise ValueError('maintenance_no_longer_stopped')


def require_maintenance(receipt, backend):
    """备份过程借用未消费的同后端停写凭证，不赋予发布或源库覆盖权限。"""
    if not isinstance(receipt,MaintenanceReceipt) or _ISSUED.get(receipt) is not backend:
        raise ValueError('invalid_maintenance_receipt')
    if digest(backend.observe())!=receipt.observed_sha256:
        raise ValueError('maintenance_release_identity_changed')
    _before_deadline(backend,receipt.stopped_at+DRAIN_SECONDS)
    _require_stopped_state(backend)


def rollback_release(checked_plan, previous_release, backend):
    """显式回到已修锁的AG06配对产物；前进版本只作关联，不自动回退数据库。

后端观察必须针对所选旧产物且确认当前版本为候选；原确认/执行键/预算的回读仍强制执行。
"""
    from operations.release_manifest import AG06_COMMITS
    if not isinstance(checked_plan, CheckedRelease) or not isinstance(previous_release, CheckedRelease):
        raise ValueError('checked_pair_required')
    current, old = checked_plan.manifest(), previous_release.manifest()
    if (old['release_id'] != 'ag06-stable' or old['previous_release'] != current['release_id']
            or any(old['sources'][role]['commit'] != commit for role, commit in AG06_COMMITS.items())):
        raise ValueError('unsafe_rollback_baseline')
    receipt = enter_maintenance(previous_release, backend)
    result = apply_release(previous_release, receipt, backend)
    return {**result, 'status': 'rolled_back', 'from_release': current['release_id']}


def main(argv=None):
    """只读预览CLI；文件观察不是现场证明，因此不提供隐式执行或生产启动选项。"""
    import argparse
    import json
    from pathlib import Path
    parser=argparse.ArgumentParser(description='AG07配对发布只读预览')
    parser.add_argument('--manifest',type=Path,required=True)
    parser.add_argument('--observed',type=Path,required=True)
    args=parser.parse_args(argv)
    try:
        # 有界解析只包含公开身份；秘密正文不允许进入任一文件。
        if any(not p.is_file() or p.stat().st_size>1024*1024 for p in (args.manifest,args.observed)):
            raise ValueError('preview_input_invalid')
        plan=validate_release(json.loads(args.manifest.read_text(encoding='utf-8')),
                              json.loads(args.observed.read_text(encoding='utf-8')))
        print(json.dumps({'status':'preview_valid','execution_allowed':False,
                          'release_id':plan.manifest()['release_id'],'plan_sha256':digest(plan.manifest())}))
        return 0
    except (ValueError,OSError,TypeError):
        # 不打印可能含路径、连接配置或用户字段的原异常。
        print(json.dumps({'status':'preview_rejected','execution_allowed':False}))
        return 2


if __name__=='__main__':
    raise SystemExit(main())

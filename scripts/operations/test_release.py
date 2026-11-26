"""AG07发布编排守卫：模拟适配器调用序列，不把驱动绿测当真实发布/回退通过。"""
import importlib
from pathlib import Path
import pytest
from tests.test_ag07_release_manifest import fixture
from operations.release_manifest import validate_release


def api():
    """缺实现应是指定断言；无权限或缺依赖不能当有效红测。"""
    assert Path('scripts/operations/release.py').exists(), '缺少维护与配对发布编排'
    return importlib.import_module('scripts.operations.release')


class Backend:
    """合成后端仅记录步骤，真实进程/容器身份及停写事实必须在07适配器取证。"""
    def __init__(self,observed):
        self.observed=observed;self.calls=[];self.now=0;self.busy=False;self.unknown=False;self.ready=True
    def observe(self):
        """只读返回当前受信观察，测试可模拟目标漂移。"""
        return self.observed
    def clock(self):
        """确定性单调时钟，不等待真实两分钟。"""
        return self.now
    def sleep(self,seconds):
        """模拟流逝，记录排空不能无限轮询。"""
        self.now+=seconds
    def close_ingress(self,plan):
        """只关闭新业务入口，核查通路保持；返回自有调用者隔离证明。"""
        self.calls.append('close_ingress');return dict(owner='ag07-20261027',ordinary_closed=True,control_open=True,exclusive_caller=True)
    def maintenance(self,enabled):
        """两侧受信准入开关，不能来自聊天字段。"""
        self.calls.append(('maintenance',enabled))
    def drain_state(self):
        """显式未知写或实际同步工作不能被请求取消掩盖。"""
        return dict(ordinary=int(self.busy),control=0,sync_workers=0,unknown_writes=int(self.unknown),background_writers=0)
    def stop_writers(self,deadline):
        """在规定截止前正常停止所有写者；真实后端不允许用强杀伪造该结果。"""
        self.calls.append('stop_writers');return dict(stopped=True,forced=False,deadline=deadline)
    def backup_receipt(self):
        """测试提供独立已验证备份点；未备份不得部署。"""
        return dict(verified=True,owner='ag07-20261027',set_sha256='f'*64,source_unchanged=True)
    def switch_pair(self,plan,deadline):
        """只有排空和备份证明通过后才调用实际切换。"""
        self.calls.append('switch_pair');return dict(agent=True,spring=True,database_downgrade=False)
    def verify_pair(self,plan,deadline):
        """实际适配器须核原键和状态；单纯健康200不足以返回这些证明。"""
        self.calls.append('verify_pair');return dict(ready=self.ready,original_receipt=True,
            unchanged_business=True,confirmation_not_revived=True,recovery_budget_preserved=True)
    def open_ingress(self,plan):
        """全部证明通过才开放，失败仍保留维护。"""
        self.calls.append('open_ingress')


def test_safe_release_drains_and_checks_before_open(tmp_path):
    """合法双应用成功路径按顺序执行，不增加额外重试或数据库回滚。"""
    m,o=fixture(tmp_path);backend=Backend(o);module=api();plan=validate_release(m,o)
    receipt=module.enter_maintenance(plan,backend)
    result=module.apply_release(plan,receipt,backend)
    assert result['status']=='released' and backend.calls.index('stop_writers')<backend.calls.index('switch_pair')
    assert backend.calls[-1]=='open_ingress'


@pytest.mark.parametrize('fault',['busy','unknown','backup','identity','readback','forged'])
def test_failure_keeps_ingress_closed_without_auto_retry(tmp_path,fault):
    """排空超时、未知写、错目标或缺业务回读均阻止开放；不能自动再次切换求绿。"""
    m,o=fixture(tmp_path);backend=Backend(o);module=api();plan=validate_release(m,o)
    if fault=='busy':backend.busy=True
    if fault=='unknown':backend.unknown=True
    if fault=='backup':backend.backup_receipt=lambda:dict(verified=False)
    if fault=='identity':backend.observed={**o,'config_sha256':'0'*64}
    if fault=='readback':backend.ready=False
    with pytest.raises((ValueError,TimeoutError)):
        receipt=module.enter_maintenance(plan,backend)
        if fault=='forged':receipt=None
        module.apply_release(plan,receipt,backend)
    assert 'open_ingress' not in backend.calls
    assert backend.calls.count('switch_pair')<=int(fault=='readback')
    assert backend.now<=120


def test_receipt_cannot_be_reused_after_failed_switch(tmp_path):
    """失败不能重复消费停写凭证，否则部分切换会被隐式重复执行。"""
    m,o=fixture(tmp_path);backend=Backend(o);module=api();plan=validate_release(m,o)
    receipt=module.enter_maintenance(plan,backend);backend.ready=False
    with pytest.raises(ValueError):module.apply_release(plan,receipt,backend)
    backend.ready=True
    with pytest.raises(ValueError):module.apply_release(plan,receipt,backend)
    assert backend.calls.count('switch_pair')==1 and 'open_ingress' not in backend.calls


def test_rollback_requires_exact_safe_baseline(tmp_path):
    """回退只接受已修锁的AG06成对版本；不执行数据库降级。"""
    from operations.release_manifest import AG06_COMMITS
    m,o=fixture(tmp_path);module=api()
    assert hasattr(module,'rollback_release'), '缺少显式配对回退入口'
    candidate=validate_release(m,o)
    import copy
    old=copy.deepcopy(m);old['release_id']='ag06-stable';old['previous_release']='candidate-r1'
    for role in old['sources']:
        old['sources'][role]['commit']=AG06_COMMITS[role];old['flags'][role]['admission']=False
    observed=copy.deepcopy(o);observed['sources']=old['sources'];observed['previous_release']='candidate-r1';observed['flags']=old['flags']
    baseline=validate_release(old,observed);backend=Backend(observed)
    result=module.rollback_release(candidate,baseline,backend)
    assert result['status']=='rolled_back' and result['database_downgrade'] is False
    with pytest.raises(ValueError):module.rollback_release(candidate,candidate,Backend(o))


def test_cli_defaults_to_preview_and_rejects_execution(tmp_path,capsys):
    """文件输入仅用于预览，不能把用户JSON当受信观测触发真实服务变更。"""
    import json
    m,o=fixture(tmp_path);module=api()
    assert hasattr(module,'main'), '缺少只读发布预览入口'
    for name,value in [('manifest',m),('observed',o)]:
        (tmp_path/(name+'.json')).write_text(json.dumps(value),encoding='utf-8')
    args=['--manifest',str(tmp_path/'manifest.json'),'--observed',str(tmp_path/'observed.json')]
    assert module.main(args)==0
    result=json.loads(capsys.readouterr().out)
    assert result['execution_allowed'] is False and result['status']=='preview_valid'
    with pytest.raises(SystemExit):module.main(args+['--apply'])


@pytest.mark.parametrize('fault',['late_writer','expired','backup_changes_state'])
def test_stale_maintenance_proof_never_switches(tmp_path,fault):
    """拿到凭证后写者恢复或备份阶段发生漂移，旧停写证明必须失效。"""
    m,o=fixture(tmp_path);module=api();backend=Backend(o);plan=validate_release(m,o)
    receipt=module.enter_maintenance(plan,backend)
    if fault=='late_writer':backend.busy=True
    elif fault=='expired':backend.now=121
    else:
        original=backend.backup_receipt
        def backup():
            """模拟备份期间后台写者重新出现。"""
            backend.busy=True
            return original()
        backend.backup_receipt=backup
    with pytest.raises((ValueError,TimeoutError)):module.apply_release(plan,receipt,backend)
    assert 'switch_pair' not in backend.calls

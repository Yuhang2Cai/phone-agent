"""AG07双来源备份守卫：实际本地归档文件配合合成服务，不替代07真实MySQL恢复。"""
import copy
import importlib
import io
import json
from pathlib import Path
import tarfile
import pytest
from scripts.operations.test_release import Backend as ReleaseBackend
from scripts.operations.test_release import fixture as release_fixture
from scripts.operations.release import enter_maintenance
from operations.release_manifest import validate_release


@pytest.fixture(autouse=True)
def synthetic_archive_permissions(monkeypatch):
    """这些文件仅合成内容，隔离ACL由既有local_files真实测试及07私有根前置核查负责。"""
    if Path('scripts/operations/backup.py').exists():
        module=importlib.import_module('scripts.operations.backup')
        monkeypatch.setattr(module,'verify_private',lambda root:None)


def api():
    """明确缺少备份模块的失败断言，不把文件系统或数据库异常算红测。"""
    assert Path('scripts/operations/backup.py').exists(), '缺少双来源备份恢复模块'
    return importlib.import_module('scripts.operations.backup')


class Backend(ReleaseBackend):
    """只模拟数据库和索引边界；归档写入、摘要校验和拒绝决策均由产品代码执行。"""
    def __init__(self,observed):
        super().__init__(observed)
        self.sources={role:dict(id=letter*64,owner='ag07-20261027',database=name,image='sha256:'+'e'*64)
            for role,letter,name in [('agent','1','dianping_intent'),('spring','2','ag07_business'),('chroma','3','index')]}
        self.facts={role:dict(tables={'ledger':{'rows':1,'sha256':'a'*64}},flyway=[],
            business_keys_sha256='b'*64,recovery_budget_sha256='c'*64,engines=['InnoDB'])
            for role in ('agent','spring')}
        self.facts['chroma']={'volume_sha256':'d'*64,'collections':1}
        self.fresh={role:{**row,'id':str(i+4)*64,'database':'ag07_restore_'+role,'exists':False}
                    for i,(role,row) in enumerate(self.sources.items())}
        self.restore_calls=[];self.changed=False;self.snapshot_count=0
    def observe_sources(self):
        """返回不同来源的固定身份，不能把同一库冒充双库。"""
        return copy.deepcopy(self.sources)
    def snapshot_sources(self):
        """返回逐表与业务键摘要；可注入第二次水位改变。"""
        self.snapshot_count+=1
        result=copy.deepcopy(self.facts)
        if self.changed and self.snapshot_count>1:result['agent']['tables']['ledger']['rows']=2
        return result
    def dump_chunks(self,role):
        """模拟mysqldump字节；不从秘密argv传入连接信息。"""
        yield b'CREATE TABLE ledger (id INT);\nINSERT INTO ledger VALUES (1);\n'
    def index_chunks(self):
        """真实构造一个无链接归档；代表自有索引字节，不生成embedding。"""
        stream=io.BytesIO()
        with tarfile.open(fileobj=stream,mode='w') as archive:
            info=tarfile.TarInfo('index/chroma.sqlite3');info.size=4
            archive.addfile(info,io.BytesIO(b'data'))
        yield stream.getvalue()
    def observe_fresh(self,targets):
        """实际适配器必须查询目标不存在，合成后端返回测试控制的观察。"""
        return copy.deepcopy(self.fresh)
    def restore_file(self,role,path,target,deadline):
        """只记录恢复请求；本测试不声称执行了数据库导入。"""
        self.restore_calls.append(role)
    def verify_restored(self,targets,deadline):
        """模拟完整结果，用于验证缺失一库或原键时不返回成功。"""
        return copy.deepcopy(self.facts)


def setup(tmp_path):
    """建立同一后端的真实编排维护凭证；不能直接手写verified=true跳过停写。"""
    root=tmp_path/'artifacts';root.mkdir();m,o=release_fixture(root)
    backend=Backend(o);plan=validate_release(m,o);receipt=enter_maintenance(plan,backend)
    return backend,receipt,tmp_path/'backup'


def test_complete_local_archive_and_restore_verification(tmp_path):
    """两份dump、索引及一致水位齐备后才接受归档与恢复，不丢原执行键和预算。"""
    module=api();backend,receipt,output=setup(tmp_path)
    saved=module.create_backup_set(backend.sources,receipt,output,backend)
    assert set(json.loads((output/'manifest.json').read_text())['files'])=={'agent','spring','chroma'}
    result=module.restore_backup_set(saved,backend.fresh,backend)
    assert result['status']=='verified' and backend.restore_calls==['agent','spring','chroma']


@pytest.mark.parametrize('fault',['missing_source','same_source','unowned','no_receipt','watermark','redirect','index_missing'])
def test_bad_backup_cannot_be_declared_verified(tmp_path,fault):
    """来源、停写、水位或文件缺失均阻止有效备份，保留失败文件而不碰源。"""
    module=api();backend,receipt,output=setup(tmp_path);sources=copy.deepcopy(backend.sources)
    if fault=='missing_source':sources.pop('spring')
    if fault=='same_source':sources['spring']['id']=sources['agent']['id']
    if fault=='unowned':sources['agent']['owner']='other'
    if fault=='no_receipt':receipt=None
    if fault=='watermark':backend.changed=True
    if fault=='redirect':backend.dump_chunks=lambda role:iter([b'/*!50000 USE production */;'])
    if fault=='index_missing':backend.index_chunks=lambda:iter([])
    with pytest.raises((ValueError,TimeoutError)):module.create_backup_set(sources,receipt,output,backend)
    assert backend.restore_calls==[]


@pytest.mark.parametrize('fault',['same_target','existing','wrong_image','corrupt','manifest','readback'])
def test_restore_rejects_unsafe_targets_or_incomplete_facts(tmp_path,fault):
    """导入前验证全套摘要与新目标；结果缺行不得开放，不能用某一库成功代表整套。"""
    module=api();backend,receipt,output=setup(tmp_path)
    saved=module.create_backup_set(backend.sources,receipt,output,backend)
    targets=copy.deepcopy(backend.fresh)
    if fault=='same_target':targets['agent']['id']=backend.sources['agent']['id']
    if fault=='existing':targets['agent']['exists']=True
    if fault=='wrong_image':targets['spring']['image']='sha256:'+'f'*64
    if fault=='corrupt':(output/'agent.sql').write_bytes(b'bad')
    if fault=='manifest':(output/'manifest.json').write_text('{}')
    if fault=='readback':backend.verify_restored=lambda targets,deadline:{'agent':backend.facts['agent']}
    with pytest.raises(ValueError):module.restore_backup_set(saved,targets,backend)
    if fault!='readback':assert backend.restore_calls==[]


@pytest.mark.parametrize('payload',[b'CREATE\nDATABASE bad;',b'CREATE/**/DATABASE bad;',
    b'/*!50000 CREATE\nDATABASE bad */;'])
def test_multiline_redirect_is_rejected(tmp_path,payload):
    """跨行或注释不能绕过重定向守卫，不能误恢复回原业务库。"""
    module=api();backend,receipt,output=setup(tmp_path)
    backend.dump_chunks=lambda role:iter([payload])
    with pytest.raises(ValueError):module.create_backup_set(backend.sources,receipt,output,backend)


def test_backup_records_configuration_and_contract_identity(tmp_path):
    """备份不仅有数据摘要，还必须明确可读取它的配对配置和契约版本。"""
    module=api();backend,receipt,output=setup(tmp_path)
    module.create_backup_set(backend.sources,receipt,output,backend)
    manifest=json.loads((output/'manifest.json').read_text())
    assert manifest.get('contracts_sha256')==backend.observed['contracts_sha256']
    assert manifest.get('config_sha256')==backend.observed['config_sha256']


def test_dump_command_cannot_redirect_or_expose_credentials(tmp_path):
    """命令只用显式Docker路径/完整ID和受控库标识，密码只在容器私有环境内展开。"""
    module=api();backend,_,_=setup(tmp_path)
    assert hasattr(module,'mysql_command'), '缺少固定MySQL导入导出命令适配'
    command=module.mysql_command('C:/tools/docker.exe',backend.sources['agent'])
    assert command[:3]==['C:/tools/docker.exe','exec',backend.sources['agent']['id']]
    assert '--databases' not in ' '.join(command) and '--single-transaction' in ' '.join(command)
    assert not any(x.startswith('--password=') for x in command)
    for database in ['production; DROP DATABASE x','../source','source-name']:
        with pytest.raises(ValueError):module.mysql_command('C:/tools/docker.exe',{**backend.sources['agent'],'database':database})
    with pytest.raises(ValueError):module.mysql_command('C:/tools/docker.exe',backend.sources['agent'],restore_database='dianping_intent')


def test_table_digest_is_order_independent_and_bounded():
    """复用历史排序摘要语义，但行数/字节超限应阻塞，不能无界fetchall或只取前N行。"""
    module=api()
    assert hasattr(module,'table_digest'), '缺少有界逐表摘要'
    left=module.table_digest([(2,'b'),(1,'a')])
    assert left==module.table_digest([(1,'a'),(2,'b')]) and left['rows']==2
    with pytest.raises(ValueError):module.table_digest([(1,),(2,)],max_rows=1)
    with pytest.raises(ValueError):module.table_digest([('long',)],max_bytes=2)


@pytest.mark.parametrize('fault',['size','timeout','index_link','index_escape','acl','config_drift','writer_restart'])
def test_resource_or_isolation_failure_preserves_sources(tmp_path,monkeypatch,fault):
    """资源及隔离守卫失败不得变成有效备份，且不能为重试删除来源。"""
    module=api();backend,receipt,output=setup(tmp_path)
    if fault=='size':monkeypatch.setattr(module,'MAX_FILE_BYTES',1)
    elif fault=='timeout':backend.now=121
    elif fault=='acl':
        def deny(root):
            """模拟实际ACL拒绝，验证检查位于写文件之前。"""
            raise ValueError('private_acl_required')
        monkeypatch.setattr(module,'verify_private',deny)
    elif fault=='config_drift':backend.observed['config_sha256']='f'*64
    elif fault=='writer_restart':backend.busy=True
    else:
        def bad_index():
            """真实构造恶意tar成员，不能只看压缩文件SHA匹配。"""
            stream=io.BytesIO()
            with tarfile.open(fileobj=stream,mode='w') as archive:
                info=tarfile.TarInfo('../escape' if fault=='index_escape' else 'link')
                if fault=='index_link':info.type=tarfile.SYMTYPE;info.linkname='/other-task'
                archive.addfile(info)
            yield stream.getvalue()
        backend.index_chunks=bad_index
    with pytest.raises((ValueError,TimeoutError)):module.create_backup_set(backend.sources,receipt,output,backend)
    assert not (output/'manifest.json').exists() and backend.restore_calls==[]


def test_restore_rechecks_source_identity_even_when_rows_match(tmp_path):
    """相同行摘要不能掩盖源容器被替换，必须在任何导入前拒绝。"""
    module=api();backend,receipt,output=setup(tmp_path)
    saved=module.create_backup_set(backend.sources,receipt,output,backend)
    backend.sources['agent']['id']='f'*64
    with pytest.raises(ValueError):module.restore_backup_set(saved,backend.fresh,backend)
    assert backend.restore_calls==[]

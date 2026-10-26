"""AG07目标守卫：伪造标签不足以获得他人容器、共享卷或外网端口的控制权。"""
import copy
import importlib
from pathlib import Path
import pytest


def fixture():
    """只构造Docker inspect事实，不启动容器或连接Docker。"""
    expected=dict(id='a'*64,name='ag07-agent-db',image='sha256:'+'b'*64,owner='ag07-20261027',
        port=13371,container_port='3306/tcp',volume='ag07-agent-data')
    actual=dict(Id=expected['id'],Name='/ag07-agent-db',Image=expected['image'],
        Config={'Labels':{'ag07.owner':expected['owner']}},State={'Running':False},
        HostConfig={'Privileged':False,'NetworkMode':'bridge','PidMode':'','Binds':None,
                    'PortBindings':{'3306/tcp':[{'HostIp':'127.0.0.1','HostPort':'13371'}]}},
        Mounts=[{'Type':'volume','Name':expected['volume'],'Destination':'/var/lib/mysql','RW':True}])
    return expected,actual


@pytest.mark.parametrize('fault',[None,'id','owner','image','host','mount','privileged','pid','extra_port','running'])
def test_explicit_docker_target_before_mutation(fault):
    """整个实际配置必须匹配登记；拒绝时不得有任何启动或停止副作用。"""
    assert Path('scripts/operations/targets.py').exists(), '缺少显式目标守卫'
    module=importlib.import_module('scripts.operations.targets');expected,actual=fixture()
    if fault=='id':actual['Id']='c'*64
    if fault=='owner':actual['Config']['Labels']['ag07.owner']='other'
    if fault=='image':actual['Image']='sha256:'+'c'*64
    if fault=='host':actual['HostConfig']['PortBindings']['3306/tcp'][0]['HostIp']='0.0.0.0'
    if fault=='mount':actual['Mounts'][0]['Name']='shared-data'
    if fault=='privileged':actual['HostConfig']['Privileged']=True
    if fault=='pid':actual['HostConfig']['PidMode']='host'
    if fault=='extra_port':actual['HostConfig']['PortBindings']['22/tcp']=[]
    if fault=='running':actual['State']['Running']=True
    if fault:
        with pytest.raises(ValueError):module.validate_container(expected,actual,require_stopped=True)
    else:
        assert module.validate_container(expected,actual,require_stopped=True)['id']==expected['id']


@pytest.mark.parametrize('port,kind',[(13373,'3306/tcp'),(13374,'3306/tcp'),(18072,'8000/tcp')])
def test_registered_restore_targets_keep_full_isolation(port,kind):
    """新恢复端口仍须完整ID/镜像/卷证明；只扩展本阶段三目标，不放宽回环全网。"""
    expected,actual=fixture();expected.update(port=port,container_port=kind)
    actual['HostConfig']['PortBindings']={kind:[{'HostIp':'127.0.0.1','HostPort':str(port)}]}
    actual['Mounts'][0]['Destination']='/data' if kind=='8000/tcp' else '/var/lib/mysql'
    module=importlib.import_module('scripts.operations.targets')
    assert module.validate_container(expected,actual)['running'] is False
    # 即使登记和inspect同时写错协议，也不能把DB端口当索引服务。
    expected['container_port']='8000/tcp' if kind=='3306/tcp' else '3306/tcp'
    with pytest.raises(ValueError):module.validate_container(expected,actual)

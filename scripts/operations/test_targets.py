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
        Config={'Labels':{'owner':expected['owner']}},State={'Running':False},
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
    if fault=='owner':actual['Config']['Labels']['owner']='other'
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

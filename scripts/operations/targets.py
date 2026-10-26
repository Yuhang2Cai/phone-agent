"""AG07自有Docker目标只读守卫：精确ID、镜像、标签、回环端口和专属卷共同校验。

Docker返回事实由调用者一次有界inspect取得；这里不连接Docker，更不默认创建或接管资源。
"""
import re


def validate_container(expected, actual, *, require_stopped=False):
    """核登记与实际配置，返回安全身份摘要；任何缺字段或未知配置都拒绝变更。

expected只能来自本任务创建台账，不能取待检查对象的当前属性反填期望。
"""
    fields = {'id','name','image','owner','port','container_port','volume'}
    if not isinstance(expected,dict) or set(expected)!=fields or not isinstance(actual,dict):
        raise ValueError('invalid_target_record')
    if (expected['owner']!='ag07-20261027' or not isinstance(expected['id'],str)
            or re.fullmatch('[0-9a-f]{64}',expected['id']) is None
            or not isinstance(expected['image'],str)
            or re.fullmatch('sha256:[0-9a-f]{64}',expected['image']) is None
            or any(not isinstance(expected[k],str) or re.fullmatch('ag07-[a-z0-9-]+',expected[k]) is None
                   for k in ('name','volume'))
            or type(expected['port']) is not int
            or expected['container_port']!={13371:'3306/tcp',13372:'3306/tcp',13373:'3306/tcp',
                13374:'3306/tcp',18071:'8000/tcp',18072:'8000/tcp'}.get(expected['port'])):
        raise ValueError('unregistered_target')
    try:
        # 名称和标签都可能被复用，因此完整容器ID及实际镜像ID同样是必需证据。
        if (actual['Id']!=expected['id'] or actual['Name']!='/'+expected['name']
                or actual['Image']!=expected['image']
                or actual['Config']['Labels']['ag07.owner']!=expected['owner']):
            raise ValueError('target_identity_mismatch')
        host = actual['HostConfig']
        if (host['Privileged'] is not False or host['NetworkMode']!='bridge' or host['PidMode']!=''
                or host.get('Binds') not in (None,[])
                or host['PortBindings']!={expected['container_port']:[
                    {'HostIp':'127.0.0.1','HostPort':str(expected['port'])}]}):
            raise ValueError('target_scope_not_isolated')
        mounts = actual['Mounts']
        destination='/data' if expected['container_port']=='8000/tcp' else '/var/lib/mysql'
        if (not isinstance(mounts,list) or len(mounts)!=1
                or any(mounts[0].get(k)!=v for k,v in {
                    'Type':'volume','Name':expected['volume'],'Destination':destination,'RW':True}.items())):
            raise ValueError('target_volume_mismatch')
        if type(actual['State']['Running']) is not bool or require_stopped and actual['State']['Running']:
            raise ValueError('target_not_stopped')
    except (KeyError,TypeError,AttributeError) as exc:
        raise ValueError('target_observation_incomplete') from exc
    return {'id':expected['id'],'image':expected['image'],'owner':expected['owner'],
            'volume':expected['volume'],'running':actual['State']['Running']}

"""同一APK九项组合实际运行：突发→200条历史重建/上翻→四Tab→筛选取消/真实图片→局部失败恢复。"""
from pathlib import Path
from urllib.request import Request,build_opener,ProxyHandler
import subprocess,json,os,sys,time,uuid,hashlib,re
import argparse
parser=argparse.ArgumentParser(description='同一实际APK九项非ASR组合，私人凭据仅从指定忽略证据目录读取')
parser.add_argument('--apk',type=Path,required=True);parser.add_argument('--evidence',type=Path,required=True);parser.add_argument('--port',type=int,required=True)
args=parser.parse_args();out=args.evidence.resolve();c=Path(__file__).resolve().parent.parent;port=str(args.port);account=json.loads((out/'identity-private.json').read_text());opener=build_opener(ProxyHandler({}))
adb=['C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe','-s','emulator-5560'];env=os.environ.copy();env.update({'JAVA_HOME':'C:/Users/caid0/.jdks/ms-17.0.17','JAVA_TOOL_OPTIONS':'-Dfile.encoding=UTF-8','ANDROID_HOME':'C:/Users/caid0/AppData/Local/Android/Sdk','ANDROID_SDK_ROOT':'C:/Users/caid0/AppData/Local/Android/Sdk','MAESTRO_USERNAME':account['username'],'MAESTRO_PASSWORD':account['password']});env['PATH']=env['ANDROID_HOME']+'/platform-tools;'+env['JAVA_HOME']+'/bin;'+env['PATH'];stages={}
def device(*args):
    """仅操作固定专用设备，保留断言；命令不含后台共享令牌。"""
    r=subprocess.run([*adb,*args],capture_output=True,text=True,encoding='utf-8',errors='replace')
    # pidof在进程不存在时正确返回1；只有此精确查询允许该退出码，不能放宽其他设备命令。
    if args==('shell','pidof','com.fandazi.app') and r.returncode==1 and not r.stdout.strip():return ''
    assert r.returncode==0,r.stderr;return r.stdout.strip()
def api(path):
    """实际Spring保护读；仅返回当前Task-owned本人事实，不输出认证信息。"""
    return json.load(opener.open(Request('http://127.0.0.1:18080/api/'+path,headers={'Authorization':'Bearer '+account['token']}),timeout=30))
def sql(statement):
    """只对本轮合成账号的会话新增测试历史；容器内既有口令不提取到主机或日志。"""
    r=subprocess.run(['docker','exec','-i','fandazi-t12-business-db-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names fandazi_groupbuy_integration'],input=statement,capture_output=True,text=True,encoding='utf-8',errors='replace')
    if r.returncode:(out/'db-error.log').write_text(r.stderr,encoding='utf-8');raise AssertionError('合成历史数据库准备失败')
    return r.stdout.strip()
def literal(text):
    """只编码已知字符串为UTF8十六进制SQL字面量，避免可执行shell拼接。"""
    return 'CONVERT(0x'+text.encode().hex()+' USING utf8mb4)'
def mode(values):
    """阶段控制原子写入，故障仅影响公开只读接口。"""
    p=out/'control.tmp';p.write_text(json.dumps(values),encoding='utf-8');p.replace(out/'control.json')
def flow(name):
    """同一组合入口逐阶段运行并保存截图；执行失败不会变更验收标准。"""
    part=out/name;part.mkdir(exist_ok=True)
    with (part/'raw.log').open('w',encoding='utf-8') as log:r=subprocess.run(['C:/Users/caid0/.local/maestro/bin/maestro.bat','--device','emulator-5560','test','--debug-output',str(part),'--test-output-dir',str(part),'-e','COMBINATION_PHASE='+name,'-e','HISTORY_PHASE=restored','-e','HISTORY_SIZE=200','-e','HISTORY_PREVIOUS=198','-e','HISTORY_DRAFT=combined draft retained',str(c/'maestro/advanced-15-performance-regression.yaml')],env=env,stdout=log,stderr=subprocess.STDOUT)
    stages[name]={'exitCode':r.returncode};(out/'progress-safe.json').write_text(json.dumps(stages,indent=2),encoding='utf-8');print('组合阶段 '+name+' 退出码='+str(r.returncode),flush=True);assert r.returncode==0,name
apk=args.apk.resolve();digest=hashlib.sha256(apk.read_bytes()).hexdigest();installed=device('shell','pm','path','com.fandazi.app').removeprefix('package:');assert device('shell','sha256sum',installed).split()[0]==digest
previous=device('shell','settings','get','global','http_proxy');ordersBefore=api('group-orders');assert ordersBefore==[]
try:
    device('shell','settings','put','global','http_proxy','10.0.2.2:'+port);mode({'burst':True});flow('burst')
    records=[json.loads(x) for x in (out/'combined-events.jsonl').read_text().splitlines()];assert any(x.get('sent')==1000 and x.get('terminalSent') for x in records)
    owner=int(sql('SELECT id FROM app_users WHERE username='+literal(account['username'])+';'));session=sql(f'SELECT session_id FROM agent_sessions WHERE user_id={owner} ORDER BY id DESC LIMIT 1;');uuid.UUID(session)
    count=lambda:int(sql(f'SELECT COUNT(*) FROM agent_messages WHERE user_id={owner} AND session_id={literal(session)};'))
    assert count()==2,'明确输入必须真实持久化用户和助手两条，不能给未发生会话补假数据'
    rows=[]
    for n in range(3,201):
        role='user' if n%2 else 'assistant';client=literal('combined-client-'+str(n)) if role=='user' else 'NULL';content='完整正文。'*20+f'O7恢复第{n:03d}条'
        rows.append(f"INSERT INTO agent_messages(public_id,user_id,session_id,trace_id,run_id,role,content,created_at,client_message_key) VALUES('{uuid.uuid4()}',{owner},{literal(session)},'combined-trace-{n//2}','combined-run-{n//2}','{role}',{literal(content)},CURRENT_TIMESTAMP(6),{client});")
    sql('\n'.join(rows));assert count()==200;mode({})
    old=device('shell','pidof','com.fandazi.app');device('shell','input','keyevent','KEYCODE_HOME')
    for attempt in range(30):
        device('shell','am','kill','com.fandazi.app')
        if not device('shell','pidof','com.fandazi.app'):break
        time.sleep(.1)
    else:raise AssertionError('旧进程未真实死亡')
    device('shell','am','start','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n','com.fandazi.app/.MainActivity')
    for attempt in range(70):
        ime=device('shell','dumpsys','input_method')
        if re.search(r'\bmInputShown=true\b',ime):device('shell','input','keyevent','KEYCODE_BACK');break
        time.sleep(.1)
    new=device('shell','pidof','com.fandazi.app');assert new and new!=old
    flow('history');assert count()==200,'恢复不得重发输入'
    flow('tabs');assert count()==200
    mode({'slowFilter':True});flow('filter-images')
    mode({'failLists':True,'detailDelayMs':100});flow('detail-failure')
    events=lambda:[json.loads(x) for x in (out/'events.jsonl').read_text().splitlines()]
    before=sum(x.get('family')=='detail' for x in events());mode({});flow('detail-retry');after=sum(x.get('family')=='detail' for x in events());assert before==after,'局部重试不得重读详情'
    assert api('group-orders')==ordersBefore and count()==200
    assert not any(x.get('method')=='POST' and x.get('family') not in ['auth','agent'] for x in events()),'组合不得出现交易写入'
    result={'apkSha256':digest,'stages':stages,'burstEventsSent':1000,'persistedMessages':200,'historyRecoveryNoResend':True,'ordersBefore':0,'ordersAfter':0,'processRebuild':{'oldPid':old,'newPid':new},'localRetryDetailReadsBefore':before,'localRetryDetailReadsAfter':after,'ASR':'未运行','transportNote':'突发是显式本机事件压力；真实回答/历史/身份/公开图文来自隔离Spring；全交付数量另由本版本背压定向测试核对'}
    (out/'safe-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps({'stages':len(stages),'messages':200,'ordersUnchanged':True},ensure_ascii=False))
finally:
    mode({});device('shell','settings','put','global','http_proxy',previous if previous not in ['null',''] else ':0')

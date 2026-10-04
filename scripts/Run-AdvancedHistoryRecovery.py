"""聊天恢复设备运行器：实际后端持久历史、真实进程死亡、网络不可达及正在执行任务续接；禁止录音/ASR。"""
from pathlib import Path
import argparse,subprocess,json,os,time,uuid,hashlib,re
from urllib.request import Request,build_opener,ProxyHandler

def main():
    """安装指定APK，私有夹具只在内存/忽略证据中使用；finally恢复系统代理并解冻Agent服务。"""
    parser=argparse.ArgumentParser()
    parser.add_argument('--apk',required=True);parser.add_argument('--evidence',required=True);parser.add_argument('--fixture',required=True)
    args=parser.parse_args()
    root=Path(__file__).resolve().parent.parent
    folder=Path(args.evidence);folder.mkdir(parents=True,exist_ok=True)
    private=json.loads(Path(args.fixture).read_text(encoding='utf-8'))
    opener=build_opener(ProxyHandler({}))
    adb=['C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe','-s','emulator-5560']
    maestro='C:/Users/caid0/.local/maestro/bin/maestro.bat'
    def device(*parts):
        """执行设备命令并捕获输出；不在断言里打印凭据、全部环境或服务授权头。"""
        return subprocess.run([*adb,*parts],capture_output=True,text=True,encoding='utf-8',errors='replace')
    def request(path,body=None):
        """真实Spring接口，认证头仅驻内存；读订单只用于前后事实摘要。"""
        return json.load(opener.open(Request('http://127.0.0.1:18080/api/'+path,
            data=json.dumps(body).encode() if body is not None else None,
            headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if 'token' in locals_for_auth else {})}),timeout=30))
    # 闭包字典避免请求函数在登录前引用未初始化的token；不写入日志或可提交配置。
    locals_for_auth={}
    login=request('auth/login',{'username':private['username'],'password':private['password']})
    token=login['token'];locals_for_auth['token']=True
    def sql(statement):
        """容器内部直接使用既有DB口令，不提取到宿主；仅本Task合成会话历史表接受新增行。"""
        result=subprocess.run(['docker','exec','-i','fandazi-t12-business-db-1','sh','-c',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names fandazi_groupbuy_integration'],
            input=statement,capture_output=True,text=True,encoding='utf-8',errors='replace')
        if result.returncode:
            (folder/'db-error.log').write_text(result.stderr,encoding='utf-8');raise RuntimeError('历史夹具数据库操作失败')
        return result.stdout.strip()
    def literal(value):
        """固定UTF8安全SQL字面量，不拼接可执行shell内容。"""
        return 'CONVERT(0x'+value.encode().hex()+' USING utf8mb4)'
    owner=int(sql('SELECT id FROM app_users WHERE username='+literal(private['username'])+';'))
    orders_before=request('group-orders')
    env=os.environ.copy()
    env.update({'JAVA_HOME':'C:/Users/caid0/.jdks/ms-17.0.17','JAVA_TOOL_OPTIONS':'-Dfile.encoding=UTF-8',
        'ANDROID_HOME':'C:/Users/caid0/AppData/Local/Android/Sdk','ANDROID_SDK_ROOT':'C:/Users/caid0/AppData/Local/Android/Sdk',
        'MAESTRO_USERNAME':private['username'],'MAESTRO_PASSWORD':private['password']})
    env['PATH']=env['ANDROID_HOME']+'/platform-tools;'+env['JAVA_HOME']+'/bin;'+env.get('PATH','')
    summaries={};processes=[]
    def flow(name,phase,size='200',draft='O7 draft retained'):
        """每阶段真实Maestro输出留私有目录；只记录退出码，不打印带env的metadata字典。"""
        if phase!='prepare':
            # hideKeyboard在部分设备上无IME时发送Back会退出聊天；只有系统真实显示标记为true才收起。
            attempts=50 if phase in ('restored','offline','unfinished-restored') else 1
            for attempt in range(attempts):
                ime=device('shell','dumpsys','input_method').stdout
                assert 'mInputShown=' in ime,'设备缺少实际IME显示标记，不能猜测后发送Back'
                if re.search(r'\bmInputShown=true\b',ime):
                    assert device('shell','input','keyevent','KEYCODE_BACK').returncode==0
                    break
                if attempts>1:time.sleep(.1)
        part=folder/name;part.mkdir(exist_ok=True)
        with (part/'raw.log').open('w',encoding='utf-8') as log:
            result=subprocess.run([maestro,'--device','emulator-5560','test','--debug-output',str(part),'--test-output-dir',str(part),
                '-e','HISTORY_PHASE='+phase,'-e','HISTORY_SIZE='+size,'-e','HISTORY_DRAFT='+draft,
                str(root/'maestro/advanced-15-chat-recovery.yaml')],env=env,stdout=log,stderr=subprocess.STDOUT)
        summaries[name]={'exitCode':result.returncode,'phase':phase}
        (folder/'progress-safe.json').write_text(json.dumps(summaries,indent=2),encoding='utf-8')
        assert result.returncode==0,'Maestro聊天恢复阶段失败：'+name
        print('聊天恢复UI阶段通过：'+name,flush=True)
    def kill_and_restart():
        """先Home让Android真实保存状态，再am kill；不使用force-stop，必须证明原进程已经消失。"""
        before=device('shell','pidof','com.fandazi.app').stdout.strip()
        assert before,'进程死亡前没有运行的应用进程'
        assert device('shell','input','keyevent','KEYCODE_HOME').returncode==0
        for attempt in range(30):
            device('shell','am','kill','com.fandazi.app')
            if not device('shell','pidof','com.fandazi.app').stdout.strip():break
            time.sleep(.1)
        else:raise AssertionError('am kill未真正终止旧进程，不能用重启冒称进程重建')
        # 必须匹配原启动器Intent；只有显式组件会在旧任务之上新建第二个Activity，不能检验原保存树恢复。
        assert device('shell','am','start','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER',
            '-n','com.fandazi.app/.MainActivity').returncode==0
        for attempt in range(50):
            after=device('shell','pidof','com.fandazi.app').stdout.strip()
            if after and after!=before:break
            time.sleep(.1)
        else:raise AssertionError('重建未启动新进程')
        processes.append({'beforePid':before,'afterPid':after,'oldProcessTerminated':True})
    def count():
        """只返回当前合成会话行数，证明只读恢复不重发输入或增加助手请求。"""
        return int(sql(f"SELECT COUNT(*) FROM agent_messages WHERE user_id={owner} AND session_id={literal(session)};"))
    def fill_history(target):
        """补齐后端真实持久的合成历史，初始hello的两条网关记录保留；不伪造客户端消息或业务对象。"""
        existing=count();statements=[]
        for number in range(existing+1,target+1):
            content='完整正文。'*80+f'O7恢复第{number:03d}条'
            role='user' if number%2==1 else 'assistant'
            client=f'ui-client-{number}' if role=='user' else None
            statements.append(f"INSERT INTO agent_messages(public_id,user_id,session_id,trace_id,run_id,role,content,created_at,client_message_key) VALUES('{uuid.uuid4()}',{owner},{literal(session)},'o7-ui-trace-{(number-1)//2}','o7-ui-run-{(number-1)//2}','{role}',{literal(content)},CURRENT_TIMESTAMP(6),{repr(client) if client else 'NULL'});")
        sql('\n'.join(statements));assert count()==target
    original_proxy=device('shell','settings','get','global','http_proxy').stdout.strip()
    paused=False
    try:
        installed=device('install','-r',args.apk);assert installed.returncode==0 and 'Success' in installed.stdout
        flow('prepare','prepare')
        session=sql(f'SELECT session_id FROM agent_sessions WHERE user_id={owner} ORDER BY id DESC LIMIT 1;')
        assert count()==2,'真实hello必须有用户和助手完整记录，不能给错误会话补夹具'
        for size in (50,200):
            fill_history(size)
            kill_and_restart()
            flow(f'restored-{size}','restored',f'{size:03d}')
            assert count()==size,'进程恢复不得自动重发任何消息'
        # 使用不可达系统代理制造实际网络连接失败；API认证仍在宿主回环，数据库事实可独立核对。
        assert device('shell','settings','put','global','http_proxy','127.0.0.1:18089').returncode==0
        kill_and_restart();flow('offline','offline')
        assert count()==200
        device('shell','settings','put','global','http_proxy',original_proxy)
        flow('network-restored','retry-restored');assert count()==200
        # 真实Agent冻结时启动新输入；服务器已接收用户消息，客户端死亡后只读恢复，原服务续跑。
        result=subprocess.run(['docker','pause','fandazi-t12-agent-1'],capture_output=True)
        assert result.returncode==0;paused=True
        flow('unfinished-send','unfinished',draft='')
        assert count()==201,'仅明确发送的一条输入进入持久化'
        kill_and_restart();flow('unfinished-restored','unfinished-restored',draft='')
        assert count()==201,'恢复未完成请求时不能再次发送'
        assert subprocess.run(['docker','unpause','fandazi-t12-agent-1'],capture_output=True).returncode==0;paused=False
        flow('unfinished-completed','unfinished-completed',draft='')
        for attempt in range(100):
            if count()==202:break
            time.sleep(.1)
        assert count()==202,'服务器原请求应只补一条助手终态，不新增用户输入'
        # 旧历史也含问候，不能只凭屏幕出现旧文字判定续接完成；必须核对本会话真实终态及新轮trace。
        assert sql(f'SELECT active_run_status FROM agent_sessions WHERE user_id={owner} AND session_id={literal(session)};')=='succeeded','原服务器请求必须实际成功结束'
        assert sql(f'SELECT COUNT(DISTINCT trace_id) FROM (SELECT trace_id FROM agent_messages WHERE user_id={owner} AND session_id={literal(session)} ORDER BY id DESC LIMIT 2) AS latest_round;')=='1','最后用户和助手记录必须属于同一真实输入轮'
        flow('logout','logout',draft='')
        orders_after=request('group-orders')
        digest=lambda value:hashlib.sha256(json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
        assert digest(orders_before)==digest(orders_after),'恢复不得改变本人订单或券事实'
        summary={'apkSha256':hashlib.sha256(Path(args.apk).read_bytes()).hexdigest(),'phases':summaries,
            'processRebuilds':processes,'persistedMessageCounts':[50,200,201,202],
            'ordersUnchanged':True,'orderFactsSha256':digest(orders_before),'serverRunStatus':'succeeded','lastRoundSingleTrace':True,'asr':'未运行'}
        (folder/'safe-result.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps({'phases':len(summaries),'realProcessDeaths':len(processes),'ordersUnchanged':True},ensure_ascii=False))
    finally:
        device('shell','settings','put','global','http_proxy',original_proxy)
        if paused:subprocess.run(['docker','unpause','fandazi-t12-agent-1'],capture_output=True)

if __name__=='__main__': main()

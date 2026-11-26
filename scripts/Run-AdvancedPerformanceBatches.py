"""固定模拟器三批Macrobenchmark：同一驱动、输入、编译、缓存条件，原始trace及失败完整保留。

只测文字和公开目录；不生成性能配置，不录音或执行ASR；模拟器结果明确仅为开发参考。
"""
from pathlib import Path
from urllib.request import build_opener,ProxyHandler,Request
import argparse,subprocess,json,time,hashlib

def main():
    """安装明确指定的测量目标，逐批五种既有手势测量；finally恢复代理，失败不取最好批替代。"""
    parser=argparse.ArgumentParser();parser.add_argument('--target',type=Path,required=True);parser.add_argument('--driver',type=Path,required=True);parser.add_argument('--fixture',type=Path,required=True);parser.add_argument('--evidence',type=Path,required=True);parser.add_argument('--port',type=int,required=True);args=parser.parse_args()
    out=args.evidence.resolve();out.mkdir(parents=True,exist_ok=True);credential=json.loads((args.fixture/'credentials.json').read_text());adb=['C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe','-s','emulator-5560'];opener=build_opener(ProxyHandler({}));records=[]
    def device(*parts):
        """固定设备命令失败即停止；不输出凭据或整段带认证参数的命令。"""
        r=subprocess.run([*adb,*parts],capture_output=True,text=True,encoding='utf-8',errors='replace');assert r.returncode==0,r.stderr;return r.stdout.strip()
    def stats():
        """仅读取回放累计计数；每批使用前后差值，不能混用其他批的请求或图像字节。"""
        return json.load(opener.open(f'http://127.0.0.1:{args.port}/__perf/stats',timeout=10))
    beforeProxy=device('shell','settings','get','global','http_proxy')
    try:
        for p in [args.target,args.driver]:assert 'Success' in device('install','-r',str(p.resolve()))
        # 目标须通过benchmarkApiBaseUrl编译为直连回放；本机Perfetto HTTP保留直接连接，不使用系统代理。
        device('shell','settings','put','global','http_proxy',':0')
        for batch in range(1,4):
            part=out/f'batch-{batch}';part.mkdir(exist_ok=False)
            # 冻结同一100行输入和每次事件压力；冷暖缓存由原始驱动的既定动作分别控制。
            controls={'catalogSize':100,'dealListFailure':False,'catalogDelayMillis':0,'detailDelayMillis':100,'dealListDelayMillis':0,'eventCount':1,'eventIntervalMillis':0}
            json.load(opener.open(Request(f'http://127.0.0.1:{args.port}/__perf/control',data=json.dumps(controls).encode(),headers={'Content-Type':'application/json'}),timeout=10))
            before=stats();started=time.monotonic();command=[*adb,'shell','am','instrument','-w','-r','-e','class','com.fandazi.app.benchmark.FandaziJourneyBenchmark','-e','androidx.benchmark.suppressErrors','EMULATOR','-e','androidx.benchmark.startupProfiles.enable','false','-e','fixtureControlUrl',f'http://10.0.2.2:{args.port}','-e','fixtureUsername',credential['username'],'-e','fixturePassword',credential['password'],'com.fandazi.app.benchmark/androidx.test.runner.AndroidJUnitRunner']
            with (part/'instrumentation.log').open('w',encoding='utf-8') as log:r=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
            raw=(part/'instrumentation.log').read_text(encoding='utf-8',errors='replace');after=stats();device('pull','/sdcard/Android/media/com.fandazi.app.benchmark',str(part/'artifacts'))
            record={'batch':batch,'instrumentExit':r.returncode,'passed':'OK (5 tests)' in raw,'seconds':round(time.monotonic()-started,3),'requestDelta':{key:after['requests'].get(key,0)-before['requests'].get(key,0) for key in set(after['requests'])|set(before['requests'])}}
            records.append(record);(out/'progress-safe.json').write_text(json.dumps(records,indent=2),encoding='utf-8');print(json.dumps(record),flush=True)
            assert record['passed'],'本批五场测量未全部通过，首次失败和trace已保存'
        summary={'referenceOnly':True,'targetApkSha256':hashlib.sha256(args.target.read_bytes()).hexdigest(),'driverSha256':hashlib.sha256(args.driver.read_bytes()).hexdigest(),'fixtureSha256':hashlib.sha256((args.fixture/'fixture.json').read_bytes()).hexdigest(),'compilation':'CompilationMode.None','catalogSize':100,'startupIterationsPerBatch':10,'batches':records,'formalPerformance':'受阻：只有x86_64模拟器；不得据此宣布20%/5%门槛通过','ASR':'未运行'}
        (out/'safe-result.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    finally:device('shell','settings','put','global','http_proxy',beforeProxy if beforeProxy not in ['null',''] else ':0')

if __name__=='__main__':main()

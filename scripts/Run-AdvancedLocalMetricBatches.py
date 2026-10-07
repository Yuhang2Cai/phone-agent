"""固定三批局部参考：真实历史/保存树、聊天Holder、后台解析、图片缓存及导航；ASR严格不执行。"""
from pathlib import Path
import argparse,json,subprocess,hashlib

def main():
    """每批仅运行显式列出的非ASR类，保存原始断言和当前输出；finally恢复系统代理。"""
    parser=argparse.ArgumentParser();parser.add_argument('--apk',type=Path,required=True);parser.add_argument('--test-apk',type=Path,required=True);parser.add_argument('--identity',type=Path,required=True);parser.add_argument('--evidence',type=Path,required=True);args=parser.parse_args();out=args.evidence.resolve();out.mkdir(parents=True,exist_ok=True);identity=json.loads(args.identity.read_text());adb=['C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe','-s','emulator-5560'];records=[]
    def device(*parts):
        """固定设备及明确产物，不能使用此前APK冒充当前源码；错误不吞掉。"""
        r=subprocess.run([*adb,*parts],capture_output=True,text=True,encoding='utf-8',errors='replace');assert r.returncode==0,r.stderr;return r.stdout.strip()
    classes=[('history','feature.agent.AgentHistoryDeviceTest',2,['history-recovery-device.json','event-index-allocation-device.json']),('chat','feature.agent.AgentChatRenderingDeviceTest',1,['chat-rendering-device.json']),('parsing','feature.store.RepositoryParsingDeviceTest',1,['repository-parsing-device.json']),('images','core.image.CatalogImageCacheTest',1,['image-cache-device.json']),('recycling','feature.agent.AgentChatRecyclingTest',3,['chat-recycling-device.json']),('navigation','core.navigation.TopLevelNavigationDeviceTest',1,[])]
    previous=device('shell','settings','get','global','http_proxy')
    try:
        for apk in [args.apk,args.test_apk]:assert 'Success' in device('install','-r',str(apk.resolve()))
        # 所有真实历史及本设备回环HTTP都直接连接，代理不能把局部ServerSocket错误转给性能回放。
        device('shell','settings','put','global','http_proxy',':0')
        for batch in range(1,4):
            folder=out/f'batch-{batch}';folder.mkdir(exist_ok=False)
            for name,klass,count,outputs in classes:
                phase=f't15-10-b{batch}'
                command=[*adb,'shell','am','instrument','-w','-r','-e','class','com.fandazi.app.'+klass,'-e','phase',phase,'-e','fixtureUsername',identity['username'],'-e','fixturePassword',identity['password'],'-e','session50',identity['sessions']['50'],'-e','session200',identity['sessions']['200'],'com.fandazi.app.test/androidx.test.runner.AndroidJUnitRunner']
                with (folder/(name+'.log')).open('w',encoding='utf-8') as log:r=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
                raw=(folder/(name+'.log')).read_text(encoding='utf-8',errors='replace');record={'batch':batch,'scenario':name,'instrumentExit':r.returncode,'tests':count,'passed':f'OK ({count} test'+('s)' if count>1 else ')') in raw};records.append(record)
                (out/'progress-safe.json').write_text(json.dumps(records,indent=2),encoding='utf-8');print(json.dumps(record),flush=True);assert record['passed'],name+'局部设备参考未通过，不能保留旧结果冒称当前通过'
                # 导出每个当前方法实际写出的数值证据；导航以本批phase区分文件名，避免陈旧残留。
                for file in outputs+([f'tab-navigation-{phase}.json'] if name=='navigation' else []):device('pull','/sdcard/Android/data/com.fandazi.app/files/performance/'+file,str(folder/file))
        result={'referenceOnly':True,'apkSha256':hashlib.sha256(args.apk.read_bytes()).hexdigest(),'testApkSha256':hashlib.sha256(args.test_apk.read_bytes()).hexdigest(),'batches':records,'ASR':'未运行','formalPerformance':'受阻：模拟器调试局部参考，既有方法内点PSS/ART计数及合成只读身份边界不替代ARM全指标/真实HTTP峰值'}
        (out/'safe-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    finally:device('shell','settings','put','global','http_proxy',previous if previous not in ['null',''] else ':0')

if __name__=='__main__':main()

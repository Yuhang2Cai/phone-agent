"""公共图片设备验收运行器：恢复代理前先安装最新APK，亲跑缺图/失败/暖返回，统计请求并核对目录事实。"""
from pathlib import Path
import argparse,subprocess,os,json,hashlib,time
from urllib.request import Request,build_opener,ProxyHandler

def main():
    """输入精确APK和私有证据路径，失败保留首次日志；无论成功失败都撤销全局代理和ADB反向端口。"""
    parser=argparse.ArgumentParser();parser.add_argument('--apk',required=True);parser.add_argument('--evidence',required=True);parser.add_argument('--fixture',required=True);parser.add_argument('--section',choices=('all','checkout','warm','orders'),default='all');args=parser.parse_args()
    root=Path(__file__).resolve().parent.parent;apk=Path(args.apk);folder=Path(args.evidence);folder.mkdir(parents=True,exist_ok=True)
    adb=['C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe','-s','emulator-5560'];maestro='C:/Users/caid0/.local/maestro/bin/maestro.bat'
    opener=build_opener(ProxyHandler({}));control='http://127.0.0.1:18083/__control'
    def request(url,body=None,headers=None):
        """只在宿主回环读取真实服务和安全统计，不向输出写入完整业务响应。"""
        return json.load(opener.open(Request(url,data=json.dumps(body).encode() if body is not None else None,headers={'Content-Type':'application/json',**(headers or {})}),timeout=30))
    before=request('http://127.0.0.1:18080/api/stores')
    assert before and before[0]['imageUrl'],'Task13第一门店实际餐图不存在'
    env=os.environ.copy();env.update({'JAVA_HOME':'C:/Users/caid0/.jdks/ms-17.0.17','JAVA_TOOL_OPTIONS':'-Dfile.encoding=UTF-8'})
    # Maestro驱动重连时必须能定位同一SDK的adb；不能仅依赖父进程已经启动的5037服务。
    env.update({'ANDROID_HOME':'C:/Users/caid0/AppData/Local/Android/Sdk','ANDROID_SDK_ROOT':'C:/Users/caid0/AppData/Local/Android/Sdk'})
    env['PATH']=env['ANDROID_HOME']+'/platform-tools;'+env['JAVA_HOME']+'/bin;'+env.get('PATH','')
    # 使用已存在的开发夹具账号，凭据仅注入Maestro环境；不创建账号、订单或支付。
    # 挑选已有订单和券的合成账号，所有业务事实都在测试前后通过同一身份读取。
    for account in json.loads(Path(args.fixture).read_text(encoding='utf-8-sig'))['scenarios']:
        login=request('http://127.0.0.1:18080/api/auth/login',{'username':account['username'],'password':account['password']})
        auth={'Authorization':'Bearer '+login['token']}
        orders_before=request('http://127.0.0.1:18080/api/group-orders',headers=auth)
        selected_order=next((r for r in orders_before if r.get('vouchers')),None)
        if selected_order:break
    else:raise AssertionError('既有合成账号没有可读订单和券，不能生成假验收数据')
    selected_voucher=selected_order['vouchers'][0]
    env.update({'MAESTRO_USERNAME':account['username'],'MAESTRO_PASSWORD':account['password']})
    deal=request('http://127.0.0.1:18080/api/deals/'+before[0]['featuredDeal']['publicId'])
    for package in ('com.dirror.music','com.fandazi.app.test','com.fandazi.app'):
        subprocess.run([*adb,'uninstall',package],capture_output=True)
    removed=subprocess.run([*adb,'shell','pm','path','com.fandazi.app'],capture_output=True)
    assert removed.returncode in (0,1) and not removed.stdout.strip() and not removed.stderr.strip(),'旧包未确认移除或设备命令异常'
    installed=subprocess.run([*adb,'install',str(apk)],capture_output=True,text=True);assert installed.returncode==0 and 'Success' in installed.stdout
    proxy=subprocess.Popen([os.sys.executable,str(root/'scripts/AdvancedImageCacheProxy.py')],stdout=(folder/'proxy.log').open('w'),stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    summaries={}
    try:
        # 仅用本任务新增反向端口；记录原全局代理并在finally准确恢复，避免留下测量故障环境。
        original_proxy=subprocess.check_output([*adb,'shell','settings','get','global','http_proxy'],text=True).strip()
        for attempt in range(50):
            try:request(control);break
            except Exception:time.sleep(.1)
        else:raise RuntimeError('受控图片代理未启动')
        subprocess.run([*adb,'reverse','tcp:18083','tcp:18083'],check=True,capture_output=True)
        subprocess.run([*adb,'shell','settings','put','global','http_proxy','127.0.0.1:18083'],check=True,capture_output=True)
        for mode in ('missing','failure','warm','checkout','orders'):
            if args.section!='all' and mode!=args.section:continue
            request(control,{'mode':mode,'missingPaths':['/api/group-orders/'+selected_order['orderId'],'/api/vouchers/'+selected_voucher['publicId']]});output=folder/mode;output.mkdir(exist_ok=True)
            steps=('prepare','recover') if mode=='failure' else ('all',)
            failed_requests=0
            for step in steps:
                part=output/step;part.mkdir(exist_ok=True)
                if step=='recover':request(control,{'releaseFailure':True})
                subprocess.run([*adb,'start-server'],check=True,capture_output=True)
                with (part/'raw.log').open('w',encoding='utf-8') as log:
                    result=subprocess.run([maestro,'--device','emulator-5560','test','--debug-output',str(part),'--test-output-dir',str(part),'-e','IMAGE_SCENARIO='+mode,'-e','IMAGE_STEP='+step,'-e','IMAGE_ALT='+before[0]['imageAlt'],'-e','IMAGE_STORE_ID='+before[0]['publicId'],'-e','IMAGE_DEAL_ID='+deal['publicId'],'-e','IMAGE_DEAL_ALT='+deal['imageAlt'],'-e','IMAGE_ORDER_ID='+selected_order['orderId'],'-e','IMAGE_VOUCHER_ID='+selected_voucher['publicId'],'-e','IMAGE_ORDER_ALT='+selected_order['imageAlt'],str(root/'maestro/advanced-15-images-cache.yaml')],env=env,stdout=log,stderr=subprocess.STDOUT)
                if result.returncode!=0:
                    (part/'failed-requests.json').write_text(json.dumps(request(control),ensure_ascii=False,indent=2),encoding='utf-8')
                    raise AssertionError(f'Maestro图片阶段失败：{mode}/{step}')
                if step=='prepare':
                    # 同URL可能供不同尺寸的多个Glide任务使用，初始均503；不能假定最先请求的是被点击的大图。
                    initial=request(control);negative=[r for r in initial['requests'] if r['path']==initial['target']]
                    assert negative and all(r['status']==503 for r in negative)
                    failed_requests=len(negative)
                    (part/'requests.json').write_text(json.dumps(initial,ensure_ascii=False,indent=2),encoding='utf-8')
            stats=request(control);(output/'requests.json').write_text(json.dumps(stats,ensure_ascii=False,indent=2),encoding='utf-8')
            assert result.returncode==0,f'Maestro图片阶段失败：{mode}'
            target=stats['target'];photos=[r for r in stats['requests'] if r['path']==target]
            metadata=[r for r in stats['requests'] if r['path']=='/api/stores/'+before[0]['publicId']]
            if mode=='missing':
                assert len(metadata)==1,'缺图必须只查询该门店元数据一次'
                assert sum(r['path']=='/api/stores' for r in stats['requests'])==1,'图片刷新不能重新查询整个目录'
                assert photos and all(r['status']==200 for r in photos)
            if mode=='failure':
                assert [r['status'] for r in photos]==[503]*failed_requests+[200],'只有用户明确点击后才新增一次正确资源请求'
                assert not metadata,'加载失败不能改成元数据或整页刷新'
            if mode=='warm':
                assert len(photos)==1,'三次Tab返回同餐图应复用缓存'
                thumbnails=[r for r in stats['requests'] if r['path'].startswith('/catalog-images/') and '/thumb/' in r['path']]
                assert thumbnails,'小尺寸套餐图片必须请求Task13站内缩略图派生'
            if mode=='checkout':
                assert sum(r['method']=='POST' and r['path']=='/api/purchases/quotes' for r in stats['requests'])==1,'图片刷新不能重建报价'
                assert sum(r['path']=='/api/deals/'+deal['publicId'] for r in stats['requests'])==2,'详情首次读取加缺图单次图片元数据读取'
                assert not any(r['method']!='GET' and r['path'] not in ('/api/auth/login','/api/purchases/quotes') for r in stats['requests']),'图片测试触发了交易写入'
            if mode=='orders':
                for path in ('/api/group-orders/'+selected_order['orderId'],'/api/vouchers/'+selected_voucher['publicId']):
                    assert sum(r['path']==path for r in stats['requests'])==2,'首次详情和单次图片GET必须各一次'
                assert sum(r['path']=='/api/refunds/by-voucher/'+selected_voucher['publicId'] for r in stats['requests'])==1,'图片刷新不能重查售后进度或重绘资格'
                assert not any(r['method']!='GET' and r['path']!='/api/auth/login' for r in stats['requests']),'订单图片操作不得准备或执行交易'
            summaries[mode]={'exitCode':result.returncode,'targetRequests':len(photos),'targetResponseBytes':sum(r['bytes'] for r in photos),'metadataRequests':len(metadata),'catalogRequests':sum(r['path']=='/api/stores' for r in stats['requests'])}
        after=request('http://127.0.0.1:18080/api/stores')
        digest=lambda rows:hashlib.sha256(json.dumps(rows,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
        assert digest(before)==digest(after),'图片验收不得改变实际后端目录事实'
        orders_after=request('http://127.0.0.1:18080/api/group-orders',headers=auth)
        assert digest(orders_before)==digest(orders_after),'图片操作不得改变本人订单/凭证事实'
        summary={'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'phases':summaries,'catalogFacts':{'stores':len(before),'beforeSha256':digest(before),'afterSha256':digest(after)},'orderFacts':{'orders':len(orders_before),'beforeSha256':digest(orders_before),'afterSha256':digest(orders_after)},'asr':'未运行'}
        (folder/'safe-result.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(summary,ensure_ascii=False))
    finally:
        subprocess.run([*adb,'shell','settings','put','global','http_proxy',locals().get('original_proxy',':0')],capture_output=True)
        subprocess.run([*adb,'reverse','--remove','tcp:18083'],capture_output=True)
        proxy.terminate();proxy.wait(timeout=10)

if __name__=='__main__':main()

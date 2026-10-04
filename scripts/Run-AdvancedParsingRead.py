"""后台解析设备只读验收：安装指定APK，读取现有合成账号并跑目录/订单/券；不生成假业务或输出凭据。"""
from pathlib import Path
import argparse,hashlib,json,os,subprocess
from urllib.request import Request,build_opener,ProxyHandler

def main():
    """输入最新APK、私有夹具和证据目录；失败保留日志并退出非零，业务事实改变同样拒绝通过。"""
    parser=argparse.ArgumentParser();parser.add_argument('--apk',required=True);parser.add_argument('--fixture',required=True);parser.add_argument('--evidence',required=True);parser.add_argument('--section',choices=['all','orders'],default='all')
    args=parser.parse_args();root=Path(__file__).resolve().parent.parent;apk=Path(args.apk);folder=Path(args.evidence);folder.mkdir(parents=True,exist_ok=True)
    assert apk.is_file() and Path(args.fixture).is_file(),'缺少APK或私有既有账号'
    adb=['C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe','-s','emulator-5560']
    maestro='C:/Users/caid0/.local/maestro/bin/maestro.bat';opener=build_opener(ProxyHandler({}));base='http://127.0.0.1:18080/api/'
    accounts=json.loads(Path(args.fixture).read_text(encoding='utf-8-sig'))['scenarios'];selected=None
    # 只挑已有本人订单且有券的合成账号，运行时读当前资格，不能假设历史夹具始终未退款。
    for account in accounts:
        login=json.load(opener.open(Request(base+'auth/login',data=json.dumps({'username':account['username'],'password':account['password']}).encode(),headers={'Content-Type':'application/json'})))
        headers={'Authorization':'Bearer '+login['token']}
        orders=json.load(opener.open(Request(base+'group-orders',headers=headers)))
        order=next((row for row in orders if row.get('vouchers')),None)
        if order is not None:selected=(account,headers,orders,order);break
    assert selected is not None,'既有账号均无可读订单券，不能伪造设备成功'
    account,headers,before,order=selected
    # 凭据只注入子进程环境，原始日志放私有目录；公开摘要只含数量/内容摘要和APK摘要。
    env=os.environ.copy();env.update({'JAVA_HOME':'C:/Users/caid0/.jdks/ms-17.0.17','JAVA_TOOL_OPTIONS':'-Dfile.encoding=UTF-8','MAESTRO_USERNAME':account['username'],'MAESTRO_PASSWORD':account['password'],'PARSE_ORDER_ID':order['orderId'],'PARSE_VOUCHER_ID':order['vouchers'][0]['publicId']})
    result=subprocess.run([*adb,'install','-r',str(apk)],capture_output=True,text=True);(folder/'install.log').write_text(result.stdout+result.stderr);assert result.returncode==0 and 'Success' in result.stdout
    phases={}
    for phase,flow in [('catalog','advanced-15-baseline.yaml'),('orders','advanced-15-parsing-read.yaml')]:
        # 只读订单选择器修复时不重复20轮未受影响目录；最终报告按同APK摘要说明证据复用。
        if args.section=='orders' and phase!='orders':continue
        output=folder/phase;output.mkdir(exist_ok=True)
        with (output/'raw.log').open('w',encoding='utf-8') as log:
            # 非MAESTRO前缀变量必须显式传入-e，否则选择器会保留字面量；只传合成公开身份，凭据仍在环境。
            result=subprocess.run([maestro,'--device','emulator-5560','test','--debug-output',str(output),'--test-output-dir',str(output),'-e','PARSE_ORDER_ID='+order['orderId'],'-e','PARSE_VOUCHER_ID='+order['vouchers'][0]['publicId'],str(root/'maestro'/flow)],env=env,stdout=log,stderr=subprocess.STDOUT)
        phases[phase]=result.returncode;assert result.returncode==0,f'设备只读阶段失败：{phase}'
    after=json.load(opener.open(Request(base+'group-orders',headers=headers)))
    # 保留完整数组形状及排序，不把空数组误计为一笔；只读行为不应改变付款和资格事实。
    def facts(rows):
        return {'orders':len(rows),'vouchers':sum(len(row.get('vouchers') or []) for row in rows),'sha256':hashlib.sha256(json.dumps(rows,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()}
    assert facts(before)==facts(after),'读取回归改变本人交易事实'
    summary={'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'phases':phases,'before':facts(before),'after':facts(after)}
    (folder/'safe-result.json').write_text(json.dumps(summary,indent=2),encoding='utf-8');print(json.dumps(summary))

if __name__=='__main__':main()

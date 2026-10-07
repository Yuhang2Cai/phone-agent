"""只用于设备验收的合成HTTP夹具：生产仓库正常解析有序30/100/500条，不写业务数据库或修改APK事实。

真实Spring评价仍返回最近20条；此服务在测试边界专门测500行展示，不宣称服务端已有500条评价接口。
"""
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from urllib.parse import urlparse,parse_qs
import json,sys,time,threading
from pathlib import Path
from urllib.request import Request,build_opener,ProxyHandler
from urllib.error import HTTPError
control=Path(__file__).with_name('fixture-control.json')
events=Path(__file__).with_name('http-events.jsonl')
event_lock=threading.Lock()
opener=build_opener(ProxyHandler({}))

def identity(n):
    """合成UUID由夹具固定，重名/排序变化后保持资源身份；只属于测试服务。"""
    return f'00000000-0000-0000-0000-{n:012d}'

def deal(n,store):
    """完整公开套餐合同，货币分和有效期只用于解析/回收断言，图片未知明确null。"""
    return dict(publicId=identity(10000+n),storePublicId=store,title=f'合成套餐{n:03}',description='合成说明',priceCents=1234+n,originalPriceCents=2500,inventory=10,validFrom='2026-01-01',validTo='2030-12-31',active=True,purchaseLimit=2,imageUrl=None,imageAlt=None)

class Handler(BaseHTTPRequestHandler):
    """GET仅读取合成测试行，控制参数来自设备请求，每类行显式带稳定公开身份。"""
    # 长连接始终带准确Content-Length；避免旧HTTP/1.0关闭连接被设备池短暂复用成截断读取。
    protocol_version='HTTP/1.1'
    def do_GET(self):
        """公开详情/列表的延迟与错误仅在明确合成边界注入；真实订单和认证不合成成功。"""
        started=time.monotonic_ns();mode=json.loads(control.read_text(encoding='utf-8')) if control.exists() else {}
        path=urlparse(self.path).path
        # 私人订单读取始终交由真实Spring认证，公开规模夹具不能制造匿名订单或改变私有资格。
        if path=='/api/group-orders' or path.startswith('/api/group-orders/'):
            self.forward();return
        family='detail' if path.startswith('/api/deals/') else 'options' if path.startswith('/api/stores/') and path.endswith('/deals') else 'other-public'
        delay=mode.get('detailDelayMs',0) if family=='detail' else mode.get('listDelayMs',0) if family=='options' else 0
        if delay:time.sleep(delay/1000)
        status=200
        try:
            if family=='options' and mode.get('failLists'):
                status=503;self.reply({'code':'SYNTHETIC_OPTIONS_FAILURE','message':'synthetic fixture failure'},status);return
            if family=='detail' and mode.get('fixture'):
                n=int(path.rsplit('/',1)[-1].rsplit('-',1)[-1])-10000
                value=deal(n,identity(1))
                if mode.get('wrongDetailId'):value['publicId']=identity(10002)
                if mode.get('missingPrice'):value.pop('priceCents')
                self.reply(value);return
            if family=='options' and mode.get('fixture') and (mode.get('wrongListStore') or mode.get('missingMember')):
                store=path.split('/')[3];rows=[deal(n,identity(2) if mode.get('wrongListStore') else store) for n in range(2 if mode.get('missingMember') else 1,4)]
                self.reply(rows);return
            self.read_get()
        except (BrokenPipeError,ConnectionResetError):
            # 用户切换/退出导致连接取消是测量事实，不能把它改写成客户端成功。
            status='cancelled'
        finally:
            event={'method':'GET','family':family,'path':path if family!='other-public' else '/public-read','startedNs':started,'finishedNs':time.monotonic_ns(),'injectedDelayMs':delay,'status':status}
            with event_lock:
                with events.open('a',encoding='utf-8') as stream:stream.write(json.dumps(event)+'\n')

    def reply(self,value,status=200):
        """返回完整长度JSON，减少连接池截断噪声；不保存请求头、凭据或用户内容。"""
        body=json.dumps(value,ensure_ascii=False).encode();self.send_response(status);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)

    def read_get(self):
        parsed=urlparse(self.path);params=parse_qs(parsed.query)
        mode=json.loads(control.read_text(encoding='utf-8')) if control.exists() else {}
        path=parsed.path
        if mode.get('failPath')==path:
            body=b'{"code":"SYNTHETIC_READ_FAILURE","message":"synthetic fixture failure"}'
            self.send_response(int(mode.get('failStatus',503)));self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body);return
        if 'count' not in params and not mode.get('fixture'):
            self.forward();return
        count=int(params.get('count',[str(mode.get('size',30))])[0]);assert count in [30,100,500]
        path=parsed.path
        store=path.split('/')[3] if path.startswith('/api/stores/') else identity(1)
        if path=='/api/stores' or path.count('/')==3 and path.startswith('/api/stores/') and path!='/api/stores/cities':
            rows=[dict(publicId=identity(n),name=f'合成门店{n:03}',cityCode='fixture',category='火锅',averageSpendCents=1234+n,address='合成地址',openingHours='合成时间',rating=4.2,reviewCount=1,reviewDistribution=[0,0,0,1,0],distanceMeters=None,imageUrl=None,imageAlt=None,featuredDeal=deal(n,identity(n))) for n in range(1,count+1)]
            if path!='/api/stores':rows=next((row for row in rows if row['publicId']==store),None)
            else:
                if params.get('keyword',[''])[0]=='advanced-no-match':rows=[]
                if params.get('sort',[''])[0]=='PRICE_DESC':rows.reverse()
        elif path.endswith('/dishes'):
            rows=[dict(publicId=identity(20000+n),name=f'合成菜品{n:03}',description='合成说明',priceCents=100+n,imageUrl=None) for n in range(1,count+1)]
        elif path.endswith('/reviews'):
            rows=[dict(publicId=identity(30000+n),storePublicId=store,reviewerName=f'合成作者{n:03}',rating=4.0,content=f'合成评价{n:03}',createdAt='2026-10-01T00:00:00Z') for n in range(1,count+1)]
        elif path.endswith('/deals'):rows=[deal(n,store) for n in range(1,count+1)]
        elif path.startswith('/api/deals/'):
            n=int(path.rsplit('/',1)[-1].rsplit('-',1)[-1])-10000;rows=deal(n,identity(1))
        elif path=='/api/stores/cities':rows=[dict(cityCode='fixture',name='fixture')]
        else:self.forward();return
        # 规模由设备测试count查询参数或运行器控制文件显式选择；生产ApiClient未作任何测试改造。
        body=json.dumps(rows,ensure_ascii=False).encode();self.send_response(200);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def do_POST(self):
        """任何写业务仍走真实Spring，同时记录去身份的操作族，便于证明失败列表没有意外交易。"""
        event={'method':'POST','family':urlparse(self.path).path.split('/')[2],'startedNs':time.monotonic_ns()}
        with event_lock:
            with events.open('a',encoding='utf-8') as stream:stream.write(json.dumps(event)+'\n')
        self.post_forward()

    def post_forward(self):
        """认证及用户明确动作仍转发实际隔离Spring，不生成假JWT或交易成功。"""
        self.forward()
    def forward(self):
        """系统代理只转发到本机隔离Spring；认证头只驻内存，不输出、不持久化。"""
        parsed=urlparse(self.path);body=self.rfile.read(int(self.headers.get('Content-Length','0'))) if self.command!='GET' else None
        headers={key:value for key,value in self.headers.items() if key.lower() not in ['host','connection','proxy-connection','content-length']}
        request=Request('http://127.0.0.1:18080'+parsed.path+('?' + parsed.query if parsed.query else ''),data=body,headers=headers,method=self.command)
        try:response=opener.open(request,timeout=30)
        except HTTPError as error:response=error
        with response:
            result=response.read();self.send_response(response.status);self.send_header('Content-Type',response.headers.get('Content-Type','application/json'));self.send_header('Content-Length',str(len(result)));self.end_headers();self.wfile.write(result)
    def log_message(self,*args):
        """不输出响应正文、身份或凭据，设备报告只保留集合数量与测量。"""
        pass

if __name__=='__main__':
    server=ThreadingHTTPServer(('127.0.0.1',int(sys.argv[1]) if len(sys.argv)>1 else 18089),Handler)
    print('合成HTTP验收夹具就绪',flush=True);server.serve_forever()

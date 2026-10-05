"""只用于O8导航验收的HTTP夹具（公开规模与明确401注入）：生产仓库正常解析有序30/100/500条，不写业务数据库或修改APK事实。

真实Spring评价仍返回最近20条；此服务在测试边界专门测500行展示，不宣称服务端已有500条评价接口。
"""
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from urllib.parse import urlparse,parse_qs
import json,sys
from pathlib import Path
from urllib.request import Request,build_opener,ProxyHandler
from urllib.error import HTTPError
control=Path(__file__).with_name('fixture-control.json')
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
        parsed=urlparse(self.path);params=parse_qs(parsed.query)
        mode=json.loads(control.read_text(encoding='utf-8')) if control.exists() else {}
        path=parsed.path
        if mode.get('failPath')==path:
            # failStatus=401只建立认证失效边界，默认503保留读失败语义；不会伪造业务成功。
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
        elif path=='/api/group-orders':
            rows=[dict(orderId=identity(40000+n),dealId=identity(10000+n),storeId=identity(1),quantity=1,totalCents=1234+n,status='PENDING_PAYMENT',voucherCount=1,
                vouchers=[dict(publicId=identity(50000+n),orderId=identity(40000+n),dealId=identity(10000+n),dealTitle=f'合成订单{n:03}',storeId=identity(1),storeName='合成门店',status='UNUSED',expiresAt='2030-12-31T00:00:00Z',redeemedAt=None,canRefund=False,amountCents=None,createdAt='2026-10-01T00:00:00Z',imageUrl=None,imageAlt=None)],
                canReview=False,createdAt='2026-10-01T00:00:00Z',storeName='合成门店',dealTitle=f'合成订单{n:03}',imageUrl=None,imageAlt=None,paidTotalCents=None,paidAt=None,expiresAt='2030-12-31T00:00:00Z',dealDescription='合成说明') for n in range(1,count+1)]
        elif path.startswith('/api/deals/'):
            n=int(path.rsplit('/',1)[-1].rsplit('-',1)[-1])-10000;rows=deal(n,identity(1))
        elif path=='/api/stores/cities':rows=[dict(cityCode='fixture',name='fixture')]
        else:self.forward();return
        # 规模由设备测试count查询参数或运行器控制文件显式选择；生产ApiClient未作任何测试改造。
        body=json.dumps(rows,ensure_ascii=False).encode();self.send_response(200);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def do_POST(self):
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

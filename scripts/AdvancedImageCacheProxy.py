"""公开图片验收代理：仅透传18080读取与登录，注入第一门店图片缺失或单次503；不修改服务/数据库。"""
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from urllib.request import Request,build_opener,ProxyHandler
from urllib.error import HTTPError
from urllib.parse import urlsplit
import json,threading,argparse

LOCK=threading.Lock();STATE={'mode':'idle','target':None,'requests':[],'failureBlocked':False,'missingPaths':[]};OPENER=build_opener(ProxyHandler({}))

class ImageCacheProxy(BaseHTTPRequestHandler):
    """只记录路径、状态和响应字节，不记录查询、身份头、正文或凭据；控制仅允许宿主回环。"""
    protocol_version='HTTP/1.0'
    def log_message(self,*args):
        """关闭默认日志，防止URL携带隐私信息进入公开输出。"""
        pass
    def do_GET(self):
        """透传真实公开/本人读取或返回宿主控制统计。"""
        self.handle_request()
    def do_POST(self):
        """仅允许宿主模式控制和既有账号登录，不开放交易写入。"""
        self.handle_request()
    def reply(self,code,body,content_type):
        """明确响应长度并关闭连接，图片错误不得伪造成功JPEG。"""
        # 代理逐请求使用短连接，必须显式告知OkHttp不复用已关闭连接，避免导航时出现响应头EOF。
        self.send_response(code);self.send_header('Content-Type',content_type);self.send_header('Content-Length',str(len(body)));self.send_header('Connection','close');self.end_headers();self.wfile.write(body)
        self.close_connection=True
    def handle_request(self):
        """校验入口→只读透传→限定照片故障→安全计数；第一门店其余字段保持原接口字节语义。"""
        parsed=urlsplit(self.path)
        if parsed.path=='/__control' and self.client_address[0]=='127.0.0.1':
            if self.command=='POST':
                config=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))))
                if config.get('releaseFailure'):
                    # 负例截图已证明失败后，由宿主放开同一资源，再由用户点击触发唯一恢复请求。
                    with LOCK:
                        assert STATE['mode']=='failure'
                        STATE['failureBlocked']=False
                else:
                    assert config['mode'] in ('missing','failure','warm','checkout','orders','idle')
                    with LOCK:STATE.update(mode=config['mode'],target=None,requests=[],failureBlocked=config['mode']=='failure',missingPaths=config.get('missingPaths',[]))
            with LOCK:body=json.dumps(STATE,ensure_ascii=False).encode()
            self.reply(200,body,'application/json');return
        # 购买确认负例仅允许首次报价准备；订单、付款、退款等写入一直拒绝。
        with LOCK:checkout=STATE['mode']=='checkout'
        if parsed.netloc not in ('10.0.2.2:18080','127.0.0.1:18080','localhost:18080') or (self.command!='GET' and parsed.path!='/api/auth/login' and not(checkout and parsed.path=='/api/purchases/quotes')):
            with LOCK:STATE['requests'].append({'method':self.command,'path':parsed.path,'status':403,'bytes':2,'rejected':True})
            self.reply(403,b'{}','application/json');return
        body=self.rfile.read(int(self.headers.get('Content-Length','0'))) if self.command=='POST' else None
        headers={k:v for k,v in self.headers.items() if k.lower() not in ('host','connection','proxy-connection','accept-encoding','content-length')}
        with LOCK:
            target=STATE['target'];mode=STATE['mode']
            failure_blocked=STATE['failureBlocked']
        if mode=='failure' and parsed.path==target and failure_blocked:
            payload=b'';code=503;content_type='image/jpeg'
        else:
            request=Request('http://127.0.0.1:18080'+parsed.path+('?' + parsed.query if parsed.query else ''),headers=headers,data=body,method=self.command)
            try:response=OPENER.open(request,timeout=30)
            except HTTPError as error:response=error
            with response:
                payload=response.read();code=response.status;content_type=response.headers.get('Content-Type','application/octet-stream')
            if parsed.path=='/api/stores' and code==200:
                rows=json.loads(payload)
                if rows:
                    with LOCK:STATE['target']=urlsplit(rows[0]['imageUrl']).path
                    if mode=='missing':
                        # 只移除第一门店照片字段作为设备负例；其他ID、金额、套餐与顺序不改，真实后台不写入。
                        rows[0]['imageUrl']=None;payload=json.dumps(rows,ensure_ascii=False).encode()
            if checkout and parsed.path=='/api/purchases/quotes' and code==200:
                # 只移除报价展示图片，报价身份、金额、期限和凭证仍由后端原样返回，不记录它们。
                quote=json.loads(payload);quote['imageUrl']=None;payload=json.dumps(quote,ensure_ascii=False).encode()
        # 只改变既有本人订单/券首次读取的照片字段，下一次同路径GET返回后端原图。
        with LOCK:
            first_missing=mode=='orders' and parsed.path in STATE['missingPaths'] and not any(r['path']==parsed.path for r in STATE['requests'])
        if first_missing and code==200:
            row=json.loads(payload);row['imageUrl']=None;payload=json.dumps(row,ensure_ascii=False).encode()
        with LOCK:STATE['requests'].append({'method':self.command,'path':parsed.path,'status':code,'bytes':len(payload)})
        try:self.reply(code,payload,content_type)
        except (BrokenPipeError,ConnectionResetError):pass

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=18083);args=parser.parse_args()
    ThreadingHTTPServer(('127.0.0.1',args.port),ImageCacheProxy).serve_forever()

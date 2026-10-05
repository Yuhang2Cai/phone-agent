"""九项组合验收网络边界：真实隔离Spring事实、指定合成账号的突发事件及可恢复公开读故障。

本服务只绑定本机；账号、历史、订单、图片均由真实Spring提供，不合成交易成功。
突发事件明确属于传输压力夹具，不作为数据库业务历史或正式性能数据。
"""
from pathlib import Path
from http.server import ThreadingHTTPServer
from urllib.parse import urlparse,parse_qs
from urllib.request import Request,build_opener,ProxyHandler
from urllib.error import HTTPError
import importlib.util,json,sys,threading,time,uuid,hashlib

folder=Path(sys.argv[1]).resolve();port=int(sys.argv[2]);source=Path(__file__).with_name('Serve-AdvancedDetailFixtures.py')
spec=importlib.util.spec_from_file_location('detail_fixture',source);detail=importlib.util.module_from_spec(spec);spec.loader.exec_module(detail)
detail.control=folder/'control.json';detail.events=folder/'events.jsonl'
private=json.loads((folder/'identity-private.json').read_text(encoding='utf-8'))
opener=build_opener(ProxyHandler({}));start=threading.Event();sent=threading.Event();lock=threading.Lock();bound=None

def record(value):
    """仅保存事件数量、路径族和状态；不记录认证头、账号、消息正文或历史内容。"""
    with lock:
        with (folder/'combined-events.jsonl').open('a',encoding='utf-8') as stream:stream.write(json.dumps(value)+'\n')

class Handler(detail.Handler):
    """只在独占账号连接中注入1000事件，其余私有请求仍使用真实认证和实际服务。"""
    def owned(self):
        """精确比较本Task新账号的认证头；不是此账号时不介入聊天协议。"""
        return self.headers.get('Authorization')=='Bearer '+private['token']
    def do_GET(self):
        """公开筛选只注入旧请求延迟；事件压力不改变真实历史读、身份或交易资格。"""
        global bound
        parsed=urlparse(self.path);path=parsed.path;mode=json.loads(detail.control.read_text()) if detail.control.exists() else {}
        if path=='/api/stores' and parse_qs(parsed.query).get('category') and mode.get('slowFilter'):
            time.sleep(3);record({'family':'old-filter-delayed','millis':3000})
        if path.startswith('/api/agent/sessions/') and path.endswith('/events') and self.owned() and mode.get('burst'):
            session=path.split('/')[4];uuid.UUID(session)
            with lock:
                if bound is None:bound=session
                assert bound==session,'组合夹具不能跨会话注入事件'
            cursor=int(self.headers.get('Last-Event-ID','0'))
            self.send_response(200);self.send_header('Content-Type','text/event-stream');self.send_header('Cache-Control','no-cache');self.send_header('Connection','close');self.end_headers();self.close_connection=True
            if not start.wait(45):return
            delivered=0
            try:
                for number in range(cursor+1,1001):
                    event={'id':number,'type':'completed' if number==1000 else 'text_delta','summary':'组合突发接收完成' if number==1000 else '', 'payload':{'delta':'压' if number<1000 else '', 'sequence':str(number),'traceId':'combination-transport-pressure','runId':'combination-transport-pressure'}}
                    self.wfile.write(b'id: '+str(number).encode()+b'\ndata: '+json.dumps(event,ensure_ascii=False).encode()+b'\n\n');delivered+=1
                self.wfile.flush();record({'family':'burst-sse','afterEventId':cursor,'sent':delivered,'lastId':1000 if delivered else cursor,'terminalSent':delivered>0});sent.set()
                # 发完后保持有限连接，避免回放EOF触发空流高速重连，干扰组合UI及主机参考测量。
                deadline=time.monotonic()+90
                while time.monotonic()<deadline and json.loads(detail.control.read_text()).get('burst'):
                    self.wfile.write(b': combined-heartbeat\n\n');self.wfile.flush();time.sleep(.5)
            except (BrokenPipeError,ConnectionResetError):record({'family':'burst-cancelled','sent':delivered})
            finally:sent.set()
            return
        super().do_GET()
    def do_POST(self):
        """聊天写入仍是用户明确点击且真实持久化；延后真实HTTP回补以观察SSE后页面状态。"""
        path=urlparse(self.path).path;mode=json.loads(detail.control.read_text()) if detail.control.exists() else {}
        if path=='/api/auth/login':
            # 注册与登录可能签发不同JWT；只有实际Spring验证独占账号成功后，更新内存中的精确令牌。
            body=self.rfile.read(int(self.headers.get('Content-Length','0')))
            try:response=opener.open(Request('http://127.0.0.1:18080'+path,data=body,headers={'Content-Type':'application/json'}),timeout=30)
            except HTTPError as error:response=error
            with response:status=response.status;data=response.read();content=response.headers.get('Content-Type','application/json')
            if status==200 and json.loads(body).get('username')==private['username']:
                private['token']=json.loads(data)['token'];record({'family':'owned-login-bound','status':200})
            self.send_response(status);self.send_header('Content-Type',content);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
        if path.startswith('/api/agent/sessions/') and path.endswith('/messages') and self.owned() and mode.get('burst'):
            body=self.rfile.read(int(self.headers.get('Content-Length','0')))
            headers={k:v for k,v in self.headers.items() if k.lower() not in ('host','connection','proxy-connection','content-length')}
            start.set()
            try:response=opener.open(Request('http://127.0.0.1:18080'+path,data=body,headers=headers),timeout=60)
            except HTTPError as error:response=error
            with response:status=response.status;data=response.read();content=response.headers.get('Content-Type','application/json')
            assert sent.wait(30),'事件压力订阅未发生，不能把普通HTTP回答冒称突发组合通过'
            # 延迟只属于验收夹具，让UI有机会应用1000事件；正文仍完整使用真实Spring回答。
            time.sleep(5)
            self.send_response(status);self.send_header('Content-Type',content);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
            record({'family':'actual-message-write','status':status,'bodySha256':hashlib.sha256(data).hexdigest()});return
        super().do_POST()

if __name__=='__main__':
    ThreadingHTTPServer(('127.0.0.1',port),Handler).serve_forever()

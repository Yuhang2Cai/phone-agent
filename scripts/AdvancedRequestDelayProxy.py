"""Android普通请求验收延迟代理：仅透传既有18080真实服务，延迟指定响应观察取消；不生成业务数据。"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, build_opener, ProxyHandler
from urllib.error import HTTPError
from urllib.parse import urlsplit, parse_qs
import json, time, threading, select, socket, argparse

LOCK=threading.Lock()
# 控制状态仅含阶段、路径条件和秒数；统计不包含认证头、正文、完整查询或用户名。
STATE={'phase':'idle','rule':{},'requests':[]}
OPENER=build_opener(ProxyHandler({}))

class DelayProxy(BaseHTTPRequestHandler):
    """HTTP代理只允许本项目API origin；控制入口仅允许宿主机回环调用，业务请求保持原方法/字节。"""
    # 每次透传独占短连接，使用HTTP/1.0明确不复用；实际设备传输是否完整仍由运行证据判断。
    protocol_version='HTTP/1.0'
    def log_message(self, *args):
        """禁用默认URL日志，避免认证或查询隐私落盘。"""
        return
    def do_GET(self):
        """处理公开读取、SSE透传和宿主机统计入口。"""
        self.handle_request()
    def do_POST(self):
        """处理控制规则及真实普通写请求透传，不重试写入。"""
        self.handle_request()
    def do_PATCH(self):
        """兼容原API写方法，代理本身没有重试或幂等规则。"""
        self.handle_request()
    def do_PUT(self):
        """透传既有更新请求，仅记录生命周期。"""
        self.handle_request()
    def do_DELETE(self):
        """透传既有删除请求，不修改业务响应。"""
        self.handle_request()
    def reply(self, code, body, content_type='application/json'):
        """返回已知字节及长度并关闭连接，统计/错误也不输出私人请求。"""
        self.send_response(code);self.send_header('Content-Type',content_type);self.send_header('Content-Length',str(len(body)));self.send_header('Connection','close');self.end_headers();self.wfile.write(body)
        self.close_connection=True
    def handle_request(self):
        """输入校验→规则快照→真实转发→受控等待→交付计数；断连只表示客户端停止等待。"""
        parsed=urlsplit(self.path)
        if parsed.path=='/__control' and self.client_address[0]=='127.0.0.1':
            if self.command=='POST':
                config=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))))
                with LOCK:STATE.update(phase=config['phase'],rule=config.get('rule',{}),requests=[])
            with LOCK:payload=json.dumps(STATE).encode()
            self.reply(200,payload);return
        if parsed.netloc not in ('10.0.2.2:18080','127.0.0.1:18080','localhost:18080'):
            self.reply(403,b'{"error":"Only isolated project API allowed"}');return
        body=self.rfile.read(int(self.headers.get('Content-Length','0'))) if self.command in ('POST','PUT','PATCH') else None
        with LOCK:
            rule=dict(STATE['rule']);record={'phase':STATE['phase'],'method':self.command,'path':parsed.path,'delayed':False,'cancelled':False,'completed':False};STATE['requests'].append(record)
        # 查询条件仅在内存中判断；完整URL/凭据/正文不进入统计。SSE不使用普通响应延迟规则。
        match=(parsed.path.startswith(rule.get('pathPrefix','!')) and parsed.path.endswith(rule.get('pathSuffix',''))
               and (not rule.get('method') or self.command==rule['method'])
               and (not rule.get('category') or parse_qs(parsed.query).get('category')==[rule['category']]))
        delay=float(rule.get('seconds',0)) if match else 0
        record['delayed']=delay>0
        headers={key:value for key,value in self.headers.items() if key.lower() not in ('host','connection','proxy-connection','accept-encoding','content-length')}
        upstream=Request('http://127.0.0.1:18080'+parsed.path+('?' + parsed.query if parsed.query else ''),data=body,headers=headers,method=self.command)
        try:
            try:response=OPENER.open(upstream,timeout=125)
            except HTTPError as error:response=error
            with response:
                content_type=response.headers.get('Content-Type','application/octet-stream')
                # 只记录传输元数据用于区分真实后端错误与代理读体问题，不保存响应正文或认证数据。
                record['status']=response.status
                record['encoding']=response.headers.get('Content-Encoding','identity')
                if 'text/event-stream' in content_type:
                    # 长连接保持流式转发，不缓存整个SSE，不把普通请求取消器改造成另一套重连策略。
                    self.send_response(response.status);self.send_header('Content-Type',content_type);self.send_header('Connection','close');self.end_headers()
                    while True:
                        chunk=response.read1(4096)
                        if not chunk:break
                        self.wfile.write(chunk);self.wfile.flush()
                    record['completed']=True;self.close_connection=True;return
                data=response.read();record['bodyBytes']=len(data);deadline=time.monotonic()+delay
                while time.monotonic()<deadline:
                    # 观察下游关闭，不改变已完成的上游业务。尤其POST取消不能据此声称事务回滚。
                    if select.select([self.connection],[],[],0.05)[0] and self.connection.recv(1,socket.MSG_PEEK)==b'':
                        record['cancelled']=True;return
                self.reply(response.status,data,content_type);record['completed']=True
        except (BrokenPipeError,ConnectionResetError):record['cancelled']=True
        except Exception:
            # 错误仅统计，禁止记录可能包含用户正文或令牌的异常对象。
            record['error']=True
            try:self.reply(502,b'{"error":"Isolated proxy unavailable"}')
            except (BrokenPipeError,ConnectionResetError):record['cancelled']=True

if __name__=='__main__':
    parser=argparse.ArgumentParser(description='仅透传真实隔离业务API的取消验收代理');parser.add_argument('--port',type=int,default=18082);args=parser.parse_args()
    # 回环监听仅供宿主机及模拟器10.0.2.2访问，不改变Spring18080或数据库端口。
    ThreadingHTTPServer(('127.0.0.1',args.port),DelayProxy).serve_forever()

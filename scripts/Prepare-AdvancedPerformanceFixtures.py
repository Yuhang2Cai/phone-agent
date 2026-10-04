"""性能回放后端：从隔离Spring公开目录生成确定夹具，不连接数据库、不接受交易。

prepare把公开响应和固定合成会话写入指定私有目录；serve仅监听本机，提供同协议读接口。
这是客户端成本测量入口，不能用它的成功响应代替Spring认证、购买或历史持久化验收。
"""
import argparse
import copy
import hashlib
import json
import secrets
import threading
import time
import uuid
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from urllib.request import ProxyHandler, build_opener

# 版本及命名空间共同固定所有合成公开ID；版本变更必须建立新基线，不能覆盖旧测量。
VERSION = 'advanced-perf-v1'
NAMESPACE = uuid.UUID('39f01283-cc0a-4b3a-8bab-bd702b4cf34b')


def encoded(value):
    """稳定序列化UTF-8，返回用于HTTP及SHA核对的字节，避免格式变化影响比较。"""
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')


def build_fixture(seed):
    """输入非空公开目录，输出三个目录规模、两个消息规模及1000顺序事件；非法ID抛异常。"""
    if not seed:
        raise ValueError('必须先读取隔离Spring公开目录，不允许内置业务兜底')
    for source in seed:
        uuid.UUID(source['publicId'])
    catalog = {}
    # 只在后端复制权威公开字段；新增公开ID和名称明确标记合成来源，套餐归属同步更新。
    for size in (30, 100, 500):
        rows = []
        for index in range(size):
            row = copy.deepcopy(seed[index % len(seed)])
            identity = str(uuid.uuid5(NAMESPACE, f'{VERSION}/store/{index}'))
            row.update(publicId=identity, name=f'性能夹具{index:03d} · {row["name"]}')
            deal = row.get('featuredDeal')
            if deal:
                deal.update(publicId=str(uuid.uuid5(NAMESPACE, f'{VERSION}/deal/{index}')), storePublicId=identity)
            rows.append(row)
        catalog[str(size)] = rows
    messages = {}
    # 固定长文、所属卡片与进度，不含私人正文、资格或确认凭证；重复文案仍具有独立消息身份。
    for size in (50, 200):
        rows = []
        for index in range(size):
            store = catalog['500'][index % 500]
            user = index % 2 == 0
            rows.append({'publicId': str(uuid.uuid5(NAMESPACE, f'{VERSION}/message/{index}')),
                'text': ('性能回放查询' if user else '这是后端合成的长回答，用于测量展示和状态体积。' * 80),
                'fromUser': user, 'cards': [] if user or index % 5 else [{'type': 'store',
                    'storePublicId': store['publicId'], 'fields': {'name': store['name'], 'imageUrl': store.get('imageUrl', '')}}],
                'events': [] if user else [{'id': index + 1, 'type': 'tool_completed', 'summary': '只读夹具查询完成'}],
                'streaming': index == size - 1})
        messages[str(size)] = rows
    events = [{'id': index, 'type': 'completed' if index == 1000 else 'text_delta',
        'summary': '回放完成' if index == 1000 else '',
        'payload': {'delta': '回放文本', 'sequence': str(index), 'runId': str(NAMESPACE)}} for index in range(1, 1001)]
    return {'version': VERSION, 'sourceSha256': hashlib.sha256(encoded(seed)).hexdigest(),
        'catalog': catalog, 'messages': messages, 'events': events}


def serve(folder, images, port, container=False):
    """仅本机启动受控HTTP回放；输入私有夹具及图片根，所有交易写请求返回405。"""
    fixture = json.loads((folder / 'fixture.json').read_text(encoding='utf-8'))
    credentials = json.loads((folder / 'credentials.json').read_text(encoding='utf-8'))
    lock = threading.Lock()
    controls = {'catalogSize': 30, 'dealListDelayMillis': 0, 'dealListFailure': False,
        'detailDelayMillis': 100, 'catalogDelayMillis': 0, 'eventIntervalMillis': 0, 'eventCount': 1000}
    counts = Counter()
    # 每会话独立从第一条回答开始，避免先前探针/其他场景改变后续50/200消息的卡片与正文身份。
    message_rounds = Counter()

    class Handler(BaseHTTPRequestHandler):
        """处理既有公开读协议和显式控制协议；日志仅记录路径计数，不记录令牌或请求正文。"""
        # JSON与图片都有确定Content-Length，允许真实OkHttp持续复用HTTP/1.1连接。
        # 不依赖HTTP/1.0隐式关闭，避免连接池把已关闭的回放连接当下一轮请求可用连接。
        protocol_version = 'HTTP/1.1'
        def log_message(self, *_):
            """关闭默认URL日志，避免测试参数或认证信息进入可提交报告。"""

        def reply(self, value, status=200):
            """发送稳定JSON，断开只结束该请求，不改变后续控制条件。"""
            body = encoded(value)
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_GET(self):
            """先处理控制/夹具，再按权威公开身份路由目录；未实现协议明确404，禁止业务默认值。"""
            parsed = urlparse(self.path)
            path, query = parsed.path, parse_qs(parsed.query)
            with lock:
                config = dict(controls)
                counts[path] += 1
            if path == '/__perf/stats':
                with lock:
                    return self.reply({'version': VERSION, 'controls': dict(controls), 'requests': dict(counts)})
            if path == '/__perf/messages':
                return self.reply(fixture['messages'][query.get('size', ['50'])[0]])
            if path == '/__perf/manifest':
                return self.reply(json.loads((folder / 'manifest.json').read_text(encoding='utf-8')))
            if path.startswith('/catalog-images/'):
                # 解析后核对目录归属，不能利用图片路径读取本机任意文件或私有凭据。
                image = (images / path.lstrip('/')).resolve()
                if not image.is_relative_to(images.resolve()) or not image.is_file():
                    return self.reply({'error': '图片不存在'}, 404)
                data = image.read_bytes()
                with lock:
                    counts['imageBytes'] += len(data)
                self.send_response(200)
                self.send_header('Content-Type', 'image/jpeg')
                self.send_header('Cache-Control', 'public, max-age=31536000, immutable')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                try:
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                return
            rows = fixture['catalog'][str(config['catalogSize'])]
            if path == '/api/stores/cities':
                return self.reply([{'cityCode': value, 'name': value} for value in sorted({r['cityCode'] for r in rows})])
            if path == '/api/stores':
                time.sleep(config['catalogDelayMillis'] / 1000)
                keyword, category = query.get('keyword', [''])[0], query.get('category', [''])[0]
                selected = [r for r in rows if (not keyword or keyword in r['name']) and (not category or r['category'] == category)]
                sort = query.get('sort', [''])[0]
                if sort in ('AVERAGE_SPEND_ASC', 'AVERAGE_SPEND_DESC'):
                    selected.sort(key=lambda row: row['averageSpendCents'], reverse=sort.endswith('DESC'))
                return self.reply(selected)
            parts = path.strip('/').split('/')
            if len(parts) >= 3 and parts[:2] == ['api', 'stores']:
                row = next((r for r in fixture['catalog']['500'] if r['publicId'] == parts[2]), None)
                if not row:
                    return self.reply({'error': '门店不存在'}, 404)
                if len(parts) == 3:
                    return self.reply(row)
                if parts[-1] == 'deals':
                    time.sleep(config['dealListDelayMillis'] / 1000)
                    return self.reply({'error': '受控列表失败'}, 503) if config['dealListFailure'] else self.reply([row['featuredDeal']] if row.get('featuredDeal') else [])
                if parts[-1] in ('dishes', 'reviews'):
                    return self.reply([])
            if path.startswith('/api/deals/'):
                time.sleep(config['detailDelayMillis'] / 1000)
                # 公开门店允许没有推荐套餐；跳过空投影，不让合法目录中的空值导致详情路由异常。
                deal = next((r['featuredDeal'] for r in fixture['catalog']['500'] if (r.get('featuredDeal') or {}).get('publicId') == parts[-1]), None)
                return self.reply(deal) if deal else self.reply({'error': '套餐不存在'}, 404)
            if path.startswith('/api/agent/sessions/') and path.endswith('/events'):
                # 沿用正式客户端的Last-Event-ID协议；首次无游标和旧查询参数只作兼容读取。
                cursor = int(self.headers.get('Last-Event-ID', query.get('afterEventId', ['0'])[0]))
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream')
                self.send_header('Cache-Control', 'no-cache')
                self.send_header('Connection', 'close')
                self.end_headers()
                # 固定有限回放发送完即结束，不以未声明长度的HTTP/1.1持久连接让读取端永久等待。
                self.close_connection = True
                try:
                    for event in fixture['events'][:config['eventCount']]:
                        if event['id'] <= cursor:
                            continue
                        self.wfile.write(b'id: ' + str(event['id']).encode() + b'\ndata: ' + encoded(event) + b'\n\n')
                        self.wfile.flush()
                        time.sleep(config['eventIntervalMillis'] / 1000)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                return
            if path.startswith('/api/group-buying/') or path.startswith('/api/orders'):
                return self.reply([])
            return self.reply({'error': '回放不支持此协议'}, 404)

        def do_POST(self):
            """控制参数白名单、合成登录和只读问答是唯一写入口；绝不代理交易到真实服务。"""
            try:
                length = int(self.headers.get('Content-Length', 0))
                if not 0 <= length <= 65536:
                    return self.reply({'error': '输入过大'}, 413)
                body = json.loads(self.rfile.read(length) or b'{}')
            except (ValueError, json.JSONDecodeError):
                return self.reply({'error': '非法输入'}, 400)
            path = urlparse(self.path).path
            with lock:
                counts['POST ' + path] += 1
            if path == '/__perf/control':
                if not set(body).issubset(controls):
                    return self.reply({'error': '未知控制参数'}, 400)
                for key, value in body.items():
                    # 故障开关必须是JSON布尔值，不能把字符串或数字的真值误当成一次故障注入。
                    if key == 'dealListFailure' and type(value) is not bool:
                        return self.reply({'error': '非法故障开关'}, 400)
                    if key == 'catalogSize' and value not in (30, 100, 500):
                        return self.reply({'error': '非法目录规模'}, 400)
                    if key.endswith('Millis') and (type(value) is not int or not 0 <= value <= 10000):
                        return self.reply({'error': '非法时序'}, 400)
                    if key == 'eventCount' and (type(value) is not int or not 1 <= value <= 1000):
                        return self.reply({'error': '非法事件数'}, 400)
                with lock:
                    controls.update(body)
                return self.reply({'updated': sorted(body)})
            if path == '/api/auth/login':
                if body.get('username') != credentials['username'] or not secrets.compare_digest(body.get('password', ''), credentials['password']):
                    return self.reply({'error': '回放账号不匹配'}, 401)
                return self.reply({'token': credentials['token'], 'user': {'username': credentials['username'], 'email': 'fixture@example.invalid'}})
            if path.startswith('/api/agent/sessions/') and path.endswith('/messages'):
                if self.headers.get('Authorization') != 'Bearer ' + credentials['token']:
                    return self.reply({'error': '回放需要认证'}, 401)
                with lock:
                    counts['messageRequests'] += 1
                    session = path.strip('/').split('/')[3]
                    message_rounds[session] += 1
                    index = message_rounds[session]
                message = fixture['messages']['200'][(index * 2 - 1) % 200]
                return self.reply({'status': 'COMPLETED', 'message': message['text'], 'events': message['events'], 'cards': message['cards']})
            return self.reply({'error': '回放禁止交易及其他写入'}, 405)

    # Windows Docker保留转发端口时由容器承载，容器外仅发布127.0.0.1；主机运行始终只绑定本机。
    if container and not Path('/.dockerenv').is_file():
        raise ValueError('容器监听只能在明确隔离Docker环境启用')
    ThreadingHTTPServer(('0.0.0.0' if container else '127.0.0.1', port), Handler).serve_forever()


def main():
    """命令行准备/运行入口；凭据仅新生成到指定私有目录，不输出密码、令牌或请求正文。"""
    parser = argparse.ArgumentParser(description='固定客户端性能回放，禁止用于真实业务验收')
    parser.add_argument('mode', choices=('prepare', 'serve'))
    parser.add_argument('--folder', type=Path, required=True)
    parser.add_argument('--api', default='http://127.0.0.1:18080/api/')
    parser.add_argument('--seed', type=Path)
    parser.add_argument('--images', type=Path)
    parser.add_argument('--port', type=int, default=18080)
    parser.add_argument('--container', action='store_true', help='仅容器内监听，宿主必须绑定127.0.0.1发布')
    args = parser.parse_args()
    if args.mode == 'serve':
        if not args.images or not args.images.is_dir():
            parser.error('serve必须指定已审定公开图片根目录')
        serve(args.folder, args.images, args.port, args.container)
        return
    # 来源明确、禁用系统代理；不读取账户或私有会话，只取得公开目录。
    seed = json.loads(args.seed.read_text(encoding='utf-8')) if args.seed else json.load(
        build_opener(ProxyHandler({})).open(args.api.rstrip('/') + '/stores', timeout=20))
    fixture = build_fixture(seed)
    args.folder.mkdir(parents=True, exist_ok=True)
    (args.folder / 'fixture.json').write_bytes(encoded(fixture))
    manifest = {'version': VERSION, 'sourceSha256': fixture['sourceSha256'],
        'fixtureSha256': hashlib.sha256(encoded(fixture)).hexdigest(), 'catalogCounts': [30, 100, 500],
        'messageCounts': [50, 200], 'eventCount': 1000, 'eventIntervalMillis': 0,
        'consumerPauseMillis': 300, 'bufferCapacity': 16, 'detailDelayMillis': 100, 'dealListDelayMillis': 3000,
        'tabRounds': 20, 'storeIds': [r['publicId'] for r in fixture['catalog']['500']]}
    (args.folder / 'manifest.json').write_bytes(encoded(manifest))
    private = args.folder / 'credentials.json'
    if not private.exists():
        private.write_bytes(encoded({'username': 'perf-' + secrets.token_hex(6), 'password': secrets.token_urlsafe(24), 'token': secrets.token_urlsafe(32)}))
    print(json.dumps({key: value for key, value in manifest.items() if key != 'storeIds'}, ensure_ascii=False))


if __name__ == '__main__':
    main()

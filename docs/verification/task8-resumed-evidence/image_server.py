"""Task8 图片故障夹具：只为新隔离套餐提供同一真实图片的 503/200 切换，不修改已有套餐 URL。"""
import argparse
import hashlib
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import httpx

ROOT = Path('C:/fdz-build')
PRIVATE = ROOT / '.runtime/task8-resumed/image-server'


class Handler(BaseHTTPRequestHandler):
    """只接收单一图片路径；响应状态由本机私有故障标记决定，不向网络暴露控制接口。"""
    # 保持 HTTP/1.1 连接让 Android 按 Content-Length 完整读取；不同图片请求由独立线程处理。
    protocol_version = 'HTTP/1.1'
    def do_GET(self):
        """对真实图片请求记录状态，未恢复时返回真实 HTTP 503，恢复后返回原字节。"""
        if self.path != '/meal.jpg':
            self.send_error(404)
            return
        failed = (PRIVATE / 'blocked').exists()
        status = 503 if failed else 200
        body = b'image temporarily unavailable' if failed else (PRIVATE / 'meal.jpg').read_bytes()
        self.send_response(status)
        self.send_header('Content-Type', 'text/plain' if failed else 'image/jpeg')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        self.wfile.flush()
        with (PRIVATE / 'requests.jsonl').open('a', encoding='utf-8') as stream:
            stream.write(json.dumps({'path': self.path, 'status': status, 'bytes': len(body)}) + '\n')

    def log_message(self, format, *args):
        """禁用默认客户端地址日志，只保留上述图片路径、状态及字节数。"""


def main():
    """准备时从现有套餐种子下载该图；只绑定回环接口，恢复模式仅移除本测试的故障标记。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['prepare', 'serve', 'recover'])
    mode = parser.parse_args().mode
    if mode == 'prepare':
        assert not PRIVATE.exists(), '禁止覆盖旧图片实验'
        source = json.loads((ROOT / 'data/seed/deals.json').read_text(encoding='utf-8-sig'))[0]
        image = httpx.get(source['imageUrl'], trust_env=False, timeout=40).raise_for_status()
        assert image.headers['content-type'].startswith('image/') and image.content[:2] == b'\xff\xd8'
        PRIVATE.mkdir()
        (PRIVATE / 'meal.jpg').write_bytes(image.content)
        (PRIVATE / 'blocked').touch()
        (PRIVATE / 'source.json').write_text(json.dumps({'url': source['imageUrl'],
            'sha256': hashlib.sha256(image.content).hexdigest(), 'bytes': len(image.content)}), encoding='utf-8')
        print('原套餐图片已保存，故障夹具仅供新隔离订单使用')
    elif mode == 'recover':
        (PRIVATE / 'blocked').unlink()
        print('同一图片 URL 已恢复真实图片响应')
    else:
        ThreadingHTTPServer(('127.0.0.1', 8768), Handler).serve_forever()


if __name__ == '__main__':
    main()

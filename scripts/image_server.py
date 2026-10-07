"""本地真实图片只读服务：固定公开路径、无上传/控制接口，不承诺具备生产媒体托管。"""
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class ImageHandler(BaseHTTPRequestHandler):
    """只暴露启动时核对的图片，不允许路径穿越或访问其他私有文件。"""
    protocol_version = "HTTP/1.1"
    def do_GET(self):
        """响应固定图片，明确Content-Length；未知路径返回404，不做任意文件映射。"""
        if self.path != "/meal.jpg":
            self.send_error(404)
            return
        body = self.server.image_bytes
        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        """不记录客户端地址、令牌或请求正文，服务只有只读图片路径。"""


def main():
    """必须由启动者提供真实已有JPEG；校验后仅绑定回环，不改原后端记录。"""
    body = Path(os.environ["FANDAZI_IMAGE_PATH"]).read_bytes()
    if not body.startswith(b"\xff\xd8"):
        raise ValueError("图片不是JPEG")
    server = ThreadingHTTPServer(("127.0.0.1", int(os.getenv("FANDAZI_IMAGE_PORT", "8768"))), ImageHandler)
    server.image_bytes = body
    server.serve_forever()


if __name__ == "__main__":
    main()

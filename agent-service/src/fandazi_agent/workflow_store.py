"""可替换的快照存储；订单与权限仍由后端负责。"""

from __future__ import annotations

from hashlib import sha256
import json
import os
from pathlib import Path
import tempfile
from typing import Any, Protocol


class WorkflowStore(Protocol):
    """按精确的用户与会话组合保存完整签名快照。

    保存操作必须原子替换并持久确认后才能返回。
    仅记录不存在时读取操作才返回空值；存储故障必须抛出。
    删除操作必须成功或抛出；签名终态可阻止取消或拒绝后清理重试时重放。
    调用方必须对同一归属者的运行与恢复操作串行化，包括跨工作进程的操作。
    """

    """加载当前数据。"""
    """加载当前数据。"""
    def load(self, user_id: str, session_id: str) -> dict[str, Any] | None: ...

    """保存当前数据。"""
    """保存当前数据。"""
    def save(self, user_id: str, session_id: str, snapshot: dict[str, Any]) -> None: ...

    """删除当前数据。"""
    """删除当前数据。"""
    def delete(self, user_id: str, session_id: str) -> None: ...


class FileWorkflowStore:
    """本地重启恢复存储：使用同步落盘和同目录原子替换。

    存储目录必须仅供本服务使用，并在重启后保留。
    此适配器不提供分布式锁或多主机存储能力。
    """

    """处理 `__init__` 相关逻辑。"""
    """处理 `__init__` 相关逻辑。"""
    def __init__(self, directory: str | Path):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)

    """处理 `_path` 相关逻辑。"""
    """处理 `_path` 相关逻辑。"""
    def _path(self, user_id: str, session_id: str) -> Path:
        owner = json.dumps([user_id, session_id], ensure_ascii=False, separators=(",", ":"))
        return self.directory / (sha256(owner.encode("utf-8")).hexdigest() + ".json")

    """加载当前数据。"""
    """加载当前数据。"""
    def load(self, user_id: str, session_id: str) -> dict[str, Any] | None:
        try:
            encoded = self._path(user_id, session_id).read_text(encoding="utf-8")
        except FileNotFoundError:
            return None
        snapshot = json.loads(encoded)
        if not isinstance(snapshot, dict):
            raise ValueError("Workflow snapshot must be a JSON object")
        return snapshot

    """保存当前数据。"""
    """保存当前数据。"""
    def save(self, user_id: str, session_id: str, snapshot: dict[str, Any]) -> None:
        encoded = json.dumps(snapshot, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        descriptor, temporary = tempfile.mkstemp(dir=self.directory, suffix=".tmp")
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
                stream.write(encoded)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self._path(user_id, session_id))
        finally:
            Path(temporary).unlink(missing_ok=True)

    """删除当前数据。"""
    """删除当前数据。"""
    def delete(self, user_id: str, session_id: str) -> None:
        self._path(user_id, session_id).unlink(missing_ok=True)

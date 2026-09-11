"""Replaceable snapshot storage; order and permission authority stays in Spring Boot."""

from __future__ import annotations

from hashlib import sha256
import json
import os
from pathlib import Path
import tempfile
from typing import Any, Protocol


class WorkflowStore(Protocol):
    """Store complete signed snapshots under an exact (user_id, session_id) pair.

    save must atomically replace and durably acknowledge a snapshot before returning.
    load returns None only for an absent record; storage failures must raise.
    delete removes the record or raises; signed terminal outcomes prevent replay
    when cancellation/rejection cleanup has to be retried.
    Callers must serialize runs/restores for a given owner, including across workers.
    """

    def load(self, user_id: str, session_id: str) -> dict[str, Any] | None: ...

    def save(self, user_id: str, session_id: str, snapshot: dict[str, Any]) -> None: ...

    def delete(self, user_id: str, session_id: str) -> None: ...


class FileWorkflowStore:
    """Local process-restart storage using fsync and same-directory atomic replace.

    The directory must be private to the service and retained across restarts.
    This adapter does not implement distributed locking or multi-host storage.
    """

    def __init__(self, directory: str | Path):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)

    def _path(self, user_id: str, session_id: str) -> Path:
        owner = json.dumps([user_id, session_id], ensure_ascii=False, separators=(",", ":"))
        return self.directory / (sha256(owner.encode("utf-8")).hexdigest() + ".json")

    def load(self, user_id: str, session_id: str) -> dict[str, Any] | None:
        try:
            encoded = self._path(user_id, session_id).read_text(encoding="utf-8")
        except FileNotFoundError:
            return None
        snapshot = json.loads(encoded)
        if not isinstance(snapshot, dict):
            raise ValueError("Workflow snapshot must be a JSON object")
        return snapshot

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

    def delete(self, user_id: str, session_id: str) -> None:
        self._path(user_id, session_id).unlink(missing_ok=True)

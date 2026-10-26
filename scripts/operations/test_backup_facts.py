"""恢复事实适配器测试：真实SQLite索引归档，不把合成数据当作真实Chroma/MySQL恢复。"""
import importlib
import io
from pathlib import Path
import sqlite3
import tarfile
import time
import pytest


def api():
    """缺实现先形成指定红测。"""
    assert Path('scripts/operations/backup_facts.py').exists(),'缺少真实备份事实适配'
    return importlib.import_module('scripts.operations.backup_facts')


def archive(tmp_path,*,wal=False,duplicate=False):
    """只使用本地合成集合表，归档是真实字节，不启动模型或索引服务。"""
    source=tmp_path/'chroma.sqlite3'
    with sqlite3.connect(source) as conn:
        conn.execute('CREATE TABLE collections (id TEXT PRIMARY KEY)')
        conn.execute("INSERT INTO collections VALUES ('synthetic')")
    target=tmp_path/'index.tar'
    with tarfile.open(target,'w') as output:
        output.add(source,arcname='./chroma.sqlite3')
        if duplicate:output.add(source,arcname='chroma.sqlite3')
        if wal:
            info=tarfile.TarInfo('chroma.sqlite3-wal');info.size=1;output.addfile(info,io.BytesIO(b'x'))
    return target


def test_archive_counts_real_sqlite_collections_without_writing_source(tmp_path):
    """离线读取必须来自归档SQLite；重复读取摘要稳定，源归档完全不变。"""
    import hashlib
    path=archive(tmp_path);before=hashlib.sha256(path.read_bytes()).hexdigest()
    facts=api().index_facts(path,tmp_path,time.monotonic()+5)
    assert facts['collections']==1 and len(facts['volume_sha256'])==64
    assert hashlib.sha256(path.read_bytes()).hexdigest()==before
    assert not list(tmp_path.glob('ag07-index-read-*'))


@pytest.mark.parametrize('fault',['wal','duplicate'])
def test_incomplete_checkpoint_or_duplicate_paths_are_not_ignored(tmp_path,fault):
    """不能忽略未checkpoint的WAL或归一化重名文件，防止恢复少数据却摘要通过。"""
    path=archive(tmp_path,**{fault:True})
    with pytest.raises(ValueError):api().index_facts(path,tmp_path,time.monotonic()+5)


def test_expired_database_snapshot_does_not_query():
    """期限在进入前已耗尽，不发起任何数据库操作。"""
    class NoConnection:
        """外部边界陷阱，任何连接操作都是本测试的越界。"""
        def cursor(self,*args,**kwargs):raise AssertionError('不应查询')
    with pytest.raises(TimeoutError):api().database_facts(NoConnection(),'agent','dianping_intent',time.monotonic()-1)

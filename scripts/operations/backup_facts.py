"""AG07备份水位事实读取：真实MySQL只读一致性快照和已停写索引归档的完整字节摘要。

连接/容器归属和私有目录权限由上层先核；本模块不启动服务、不生成embedding、不执行恢复。
"""
import hashlib
from contextlib import closing
import json
from pathlib import Path,PurePosixPath
import re
import sqlite3
import tarfile
import tempfile
import time
from operations.release_manifest import digest
from scripts.operations.backup import table_digest,_validate_index,MAX_FILE_BYTES


BUSINESS_TABLES={
    'agent':('agent_service_runs','agent_task_registry','agent_task_turn_receipts'),
    'spring':('group_buy_orders','refund_requests','complaint_tickets','confirmation_grants','agent_tool_executions')}
"""原执行键、确认状态及领域结果均纳入；不是只比较订单总数。"""
RECOVERY_TABLES={'agent':('agent_private_goal_states',),'spring':('agent_goal_recoveries','agent_conversation_tasks')}
"""恢复次数、活动目标和围栏的完整行摘要，不能遗漏空表或新表。"""


def _deadline(deadline):
    """每个读取步骤前后核单调期限；超时结果不能用于签发归档成功。"""
    if time.monotonic()>=deadline:raise TimeoutError('facts_deadline')


def database_facts(connection,role,database,deadline):
    """读取已证明归属的PyMySQL连接；256表/每表十万行/全库256MiB，无库写副作用。

调用方必须给连接设置有限connect/read/write_timeout，并在finally关闭。这里使用只读
一致性事务，所有表排序后的内容摘要连同Flyway迁移与关键状态返回，不保存明文业务行。
"""
    _deadline(deadline)
    if role not in BUSINESS_TABLES or not isinstance(database,str) or re.fullmatch('[a-zA-Z0-9_]{1,64}',database) is None:
        raise ValueError('invalid_database_fact_scope')
    import pymysql
    with connection.cursor() as cursor:
        cursor.execute('SELECT DATABASE()')
        if cursor.fetchone()!=(database,):raise ValueError('database_connection_mismatch')
        cursor.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ')
        cursor.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY')
    try:
        with connection.cursor() as cursor:
            cursor.execute('SELECT TABLE_NAME, ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s ORDER BY TABLE_NAME LIMIT 257',(database,))
            entries=cursor.fetchall()
        names=[row[0] for row in entries]
        if (not entries or len(entries)>256 or any(row[1]!='InnoDB' for row in entries)
                or any(re.fullmatch('[a-zA-Z0-9_]{1,64}',name) is None for name in names)
                or not set(BUSINESS_TABLES[role]+RECOVERY_TABLES[role]).issubset(names)
                or role=='spring' and 'flyway_schema_history' not in names):
            raise ValueError('database_tables_incomplete')
        tables={};total_bytes=0;flyway=[]
        for name in names:
            _deadline(deadline)
            with connection.cursor(pymysql.cursors.SSCursor) as cursor:
                # 表名只能来自已校验的information_schema，不能插入用户SQL。
                cursor.execute('SELECT /*+ MAX_EXECUTION_TIME(5000) */ * FROM `'+name+'`')
                columns=[column[0] for column in cursor.description]
                def rows():
                    """逐行预算覆盖全库；Flyway保留公开迁移元数据，其余只进入摘要。"""
                    nonlocal total_bytes
                    for row in cursor:
                        _deadline(deadline)
                        total_bytes+=len(json.dumps(row,default=str,ensure_ascii=False,separators=(',',':')).encode('utf-8'))
                        if total_bytes>MAX_FILE_BYTES:raise ValueError('database_fact_byte_budget')
                        if name=='flyway_schema_history':
                            public=dict(zip(columns,row))
                            flyway.append({key:public[key] for key in ('installed_rank','version','script','checksum','success')})
                        yield row
                tables[name]=table_digest(rows())
        _deadline(deadline)
        return dict(tables=tables,flyway=sorted(flyway,key=lambda row:row['installed_rank']),
            business_keys_sha256=digest({key:tables[key] for key in BUSINESS_TABLES[role]}),
            recovery_budget_sha256=digest({key:tables[key] for key in RECOVERY_TABLES[role]}),engines=['InnoDB'])
    finally:
        # 只读事务也要释放快照与连接占用；不把读操作带入下一次导入连接。
        connection.rollback()


def index_facts(archive_path,private_directory,deadline):
    """对停写归档逐文件校验并读取集合数；非空WAL、重名或缺SQLite都阻塞。

临时SQLite只在已核私有目录中以随机名建立，immutable只读打开，finally仅删除自建文件。
"""
    _deadline(deadline);path=Path(archive_path)
    if not path.is_file() or path.stat().st_size>MAX_FILE_BYTES:raise ValueError('index_file_budget')
    _validate_index(path)
    fingerprints={};sqlite_path=None
    try:
        with tarfile.open(path,'r:*') as archive:
            for member in archive:
                _deadline(deadline)
                if not member.isfile():continue
                name=PurePosixPath(member.name).as_posix()
                if name in fingerprints:raise ValueError('normalized_index_duplicate')
                if name.endswith('-wal') and member.size:raise ValueError('index_wal_not_checkpointed')
                database=PurePosixPath(name).name=='chroma.sqlite3'
                if database and sqlite_path is not None:raise ValueError('multiple_index_databases')
                temporary=None
                if database:
                    temporary=tempfile.NamedTemporaryFile(prefix='ag07-index-read-',dir=private_directory,delete=False)
                    sqlite_path=Path(temporary.name)
                hasher=hashlib.sha256();size=0
                try:
                    with archive.extractfile(member) as source:
                        for chunk in iter(lambda:source.read(65536),b''):
                            _deadline(deadline);hasher.update(chunk);size+=len(chunk)
                            if temporary is not None:temporary.write(chunk)
                finally:
                    if temporary is not None:temporary.close()
                if size!=member.size:raise ValueError('incomplete_index_member')
                fingerprints[name]={'size':size,'sha256':hasher.hexdigest()}
        if sqlite_path is None:raise ValueError('missing_index_database')
        # SQLite连接自身的with只结束事务，不关闭句柄；显式closing保证Windows可立即回收文件。
        with closing(sqlite3.connect(sqlite_path.as_uri()+'?mode=ro&immutable=1',uri=True)) as connection:
            # 查询只允许有限CPU时间；集合数必须来自归档本体，不能由调用方声明。
            connection.set_progress_handler(lambda:int(time.monotonic()>=deadline),1000)
            connection.execute('PRAGMA query_only=ON')
            count=connection.execute('SELECT COUNT(*) FROM collections').fetchone()[0]
        _deadline(deadline)
        return {'volume_sha256':digest(fingerprints),'collections':count}
    finally:
        if sqlite_path is not None:sqlite_path.unlink(missing_ok=True)

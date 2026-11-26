"""AG07停写下的双库及索引归档编排，依赖受信来源/恢复适配器与本机私有文件守卫。

源库不恢复、不删；失败保留归档证据，目标绝不自动开放。真实数据库适配器由有界演练接入。
"""
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path,PurePosixPath
import re
import stat
import tarfile
from operations.local_files import verify_private,read_json,atomic_json
from operations.reader import _safe_path
from operations.release_manifest import digest
from scripts.operations.release import require_maintenance


MAX_FILE_BYTES=256*1024*1024
"""单份dump或索引256MiB硬上限，超限拒绝且不截断冒充完整备份。"""
MAX_MEMBERS=10000
"""索引归档最多一万成员；此上限限制解析成本，不允许悄悄遗漏余下文件。"""
ROLES=('agent','spring','chroma')
"""备份及恢复必须完整保留两个独立数据库和既有索引。"""


def mysql_command(docker_executable,target,*,restore_database=None):
    """固定导出/导入argv；秘密只从目标容器私有环境读取，绝不拼接到宿主命令。

调用者必须先核实际容器ID/镜像/owner和恢复新库，执行时还须使用120秒及字节上限。
本函数只返回命令，不负责证明目标属于本任务，不执行任何外部进程。
"""
    if (not isinstance(docker_executable,str) or not Path(docker_executable).is_absolute()
            or not isinstance(target,dict) or target.get('owner')!='ag07-20261027'
            or not isinstance(target.get('id'),str) or re.fullmatch('[0-9a-f]{64}',target['id']) is None
            or not isinstance(target.get('database'),str)
            or re.fullmatch('[a-zA-Z0-9_]{1,64}',target['database']) is None):
        raise ValueError('invalid_mysql_command_target')
    prefix=[docker_executable,'exec']
    if restore_database is None:
        # 不使用--databases：避免生成USE/CREATE DATABASE导致恢复返回源库。
        script='export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysqldump --user=root --single-transaction --hex-blob --no-tablespaces --skip-add-drop-table --set-gtid-purged=OFF --routines --events --triggers "$1"'
        database=target['database']
    else:
        if (not isinstance(restore_database,str) or re.fullmatch('ag07_restore_[a-z0-9_]{1,40}',restore_database) is None
                or restore_database==target['database']):raise ValueError('unsafe_mysql_restore_database')
        prefix.append('-i');database=restore_database
        script='export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql --user=root --database="$1"'
    return prefix+[target['id'],'sh','-c',script,'ag07-mysql',database]


def table_digest(rows,*,max_rows=100000,max_bytes=MAX_FILE_BYTES):
    """逐行消费后排序计算与历史一致的行摘要；超预算抛错，绝不截断表数据。

输入由服务端游标逐批读取；摘要不持久保存明文行，Flyway与业务键另做显式闭合。
"""
    encoded=[];size=0
    for row in rows:
        value=json.dumps(row,default=str,ensure_ascii=False,separators=(',',':'))
        size+=len(value.encode('utf-8'))
        if len(encoded)>=max_rows or size>max_bytes:raise ValueError('table_digest_budget')
        encoded.append(value)
    encoded.sort()
    return {'rows':len(encoded),'sha256':hashlib.sha256('\n'.join(encoded).encode('utf-8')).hexdigest()}


@dataclass(frozen=True)
class BackupSet:
    """归档目录与创建时清单摘要；修改磁盘清单不能自行重新签名后恢复。"""
    root: Path
    manifest_sha256: str


def _sources(sources):
    """显式来源完整、独立且属于本任务，拒绝隐式旧库和其他容器。"""
    if not isinstance(sources,dict) or set(sources)!=set(ROLES):raise ValueError('three_sources_required')
    identities=set()
    for role,row in sources.items():
        if (not isinstance(row,dict) or set(row)!={'id','owner','database','image'}
                or row['owner']!='ag07-20261027'
                or not isinstance(row['id'],str) or re.fullmatch('[0-9a-f]{64}',row['id']) is None
                or row['id'] in identities or not isinstance(row['database'],str)
                or re.fullmatch('[a-zA-Z0-9_]{1,64}',row['database']) is None
                or not isinstance(row['image'],str) or re.fullmatch('sha256:[0-9a-f]{64}',row['image']) is None):
            raise ValueError('invalid_owned_source')
        identities.add(row['id'])


def _facts(facts):
    """两库逐表、迁移、业务键及恢复预算必须齐备，未知索引或非事务表不猜成安全。"""
    if not isinstance(facts,dict) or set(facts)!=set(ROLES):raise ValueError('incomplete_source_facts')
    for role in ('agent','spring'):
        row=facts[role]
        required={'tables','flyway','business_keys_sha256','recovery_budget_sha256','engines'}
        if (not isinstance(row,dict) or set(row)!=required or not isinstance(row['tables'],dict)
                or not row['tables'] or len(row['tables'])>256 or not isinstance(row['flyway'],list)
                or not row['engines'] or any(x!='InnoDB' for x in row['engines'])
                or any(not isinstance(row[k],str) or re.fullmatch('[0-9a-f]{64}',row[k]) is None
                       for k in ('business_keys_sha256','recovery_budget_sha256'))):
            raise ValueError('database_facts_incomplete')
        for table,summary in row['tables'].items():
            if (not isinstance(table,str) or not isinstance(summary,dict) or set(summary)!={'rows','sha256'}
                    or type(summary['rows']) is not int or summary['rows']<0
                    or not isinstance(summary['sha256'],str) or re.fullmatch('[0-9a-f]{64}',summary['sha256']) is None):
                raise ValueError('table_facts_incomplete')
    index=facts['chroma']
    if (not isinstance(index,dict) or set(index)!={'volume_sha256','collections'}
            or type(index['collections']) is not int or index['collections']<0
            or not isinstance(index['volume_sha256'],str) or re.fullmatch('[0-9a-f]{64}',index['volume_sha256']) is None):
        raise ValueError('index_facts_incomplete')


def _write_chunks(path,chunks,backend,deadline):
    """私有新文件流式写入并fsync；失败保留部分文件，不能当作有效归档。"""
    size=0;hasher=hashlib.sha256()
    with path.open('xb') as output:
        for chunk in chunks:
            if backend.clock()>=deadline:raise TimeoutError('backup_deadline')
            if not isinstance(chunk,bytes) or len(chunk)>MAX_FILE_BYTES:raise ValueError('invalid_dump_chunk')
            size+=len(chunk)
            if size>MAX_FILE_BYTES:raise ValueError('backup_file_budget')
            output.write(chunk);hasher.update(chunk)
        output.flush();os.fsync(output.fileno())
    if not size:raise ValueError('empty_backup_file')
    return {'file':path.name,'size':size,'sha256':hasher.hexdigest()}


def _validate_dump(path):
    """拒绝数据库重定向与危险全局命令，包括MySQL版本条件注释；不执行SQL探测。

只接受本任务受信mysqldump产生并按创建摘要固定的文件，不能把此检查当通用SQL沙箱。
"""
    # 按有界行读取，超长行直接阻塞，不能只检查行前缀后继续恢复。
    carry=b''
    with path.open('rb') as source:
        while True:
            line=source.readline(4*1024*1024+1)
            if not line:break
            if len(line)>4*1024*1024:raise ValueError('dump_line_budget')
            # 保存有限上下文，使CREATE换行DATABASE及条件注释仍被当作同条命令。
            combined=carry+line
            executable=re.sub(rb'/\*(?!\!)[\s\S]*?\*/',b' ',combined)
            executable=re.sub(rb'/\*!\d*\s*',b'',executable)
            if re.search(rb'(?i)(?:^|;)\s*(?:USE\b|(?:CREATE|DROP|ALTER)\s+DATABASE\b|GRANT\b|SET\s+GLOBAL\b|SOURCE\b|LOAD\s+DATA\b)',executable):
                raise ValueError('dump_redirect_or_global_command')
            carry=executable[-4096:]


def _validate_index(path):
    """只接受有限普通文件/目录，拒绝穿越、链接、设备、重复成员和解包膨胀。"""
    seen=set();total=0
    try:
        with tarfile.open(path,mode='r:*') as archive:
            for member in archive:
                name=PurePosixPath(member.name)
                if (name.is_absolute() or '..' in name.parts or '\\' in member.name or ':' in member.name
                        or member.name in seen or not(member.isfile() or member.isdir())):
                    raise ValueError('unsafe_index_member')
                seen.add(member.name);total+=member.size
                if len(seen)>MAX_MEMBERS or total>MAX_FILE_BYTES:raise ValueError('index_archive_budget')
    except tarfile.TarError as exc:raise ValueError('invalid_index_archive') from exc
    if not seen:raise ValueError('missing_index_members')


def create_backup_set(checked_targets,maintenance_receipt,output,backend):
    """停写窗口内依次归档两库/索引；源前后摘要一致才写有效清单，无源修改副作用。"""
    _sources(checked_targets)
    if backend.observe_sources()!=checked_targets:raise ValueError('source_identity_changed')
    require_maintenance(maintenance_receipt,backend)
    root=Path(output)
    if not root.is_absolute():raise ValueError('absolute_private_output_required')
    _safe_path(root);verify_private(root.parent)
    # Python3.13在Windows对0700生成OWNER RIGHTS SID，现有严格守卫不接受该特殊SID。
    # Windows继承刚核验的私有父目录ACL；POSIX仍显式0700，之后再次核实际权限。
    root.mkdir(exist_ok=False,mode=0o777 if os.name=='nt' else 0o700);verify_private(root)
    deadline=maintenance_receipt.stopped_at+120
    before=backend.snapshot_sources();_facts(before)
    files={}
    for role in ROLES:
        require_maintenance(maintenance_receipt,backend)
        path=root/(role+('.tar' if role=='chroma' else '.sql'))
        chunks=backend.index_chunks() if role=='chroma' else backend.dump_chunks(role)
        files[role]=_write_chunks(path,chunks,backend,deadline)
        (_validate_index if role=='chroma' else _validate_dump)(path)
    after=backend.snapshot_sources();_facts(after)
    require_maintenance(maintenance_receipt,backend)
    if before!=after or backend.observe_sources()!=checked_targets:raise ValueError('backup_source_changed')
    observed=backend.observe()
    manifest={'schema_version':1,'owner':'ag07-20261027','sources':checked_targets,'files':files,
              'facts':before,'maintenance_plan_sha256':maintenance_receipt.plan_sha256,
              'contracts_sha256':observed['contracts_sha256'],'config_sha256':observed['config_sha256'],
              'release_sources':observed['sources'],
              'source_unchanged':True,'model_calls':0}
    atomic_json(root/'manifest.json',manifest)
    return BackupSet(root,digest(manifest))


def _verify_files(saved):
    """恢复前一次验证全套清单与每个文件，不允许先恢复一库后才发现第二库坏。"""
    if not isinstance(saved,BackupSet):raise ValueError('verified_backup_set_required')
    verify_private(saved.root);manifest=read_json(saved.root/'manifest.json')
    if digest(manifest)!=saved.manifest_sha256:raise ValueError('backup_manifest_changed')
    _sources(manifest['sources']);_facts(manifest['facts'])
    for role in ROLES:
        item=manifest['files'][role];expected=role+('.tar' if role=='chroma' else '.sql')
        if item['file']!=expected:raise ValueError('archive_path_changed')
        path=saved.root/expected;_safe_path(path);info=path.stat()
        if not stat.S_ISREG(info.st_mode) or info.st_nlink!=1 or info.st_size!=item['size'] or not 0<info.st_size<=MAX_FILE_BYTES:
            raise ValueError('backup_file_changed')
        with path.open('rb') as source:
            hasher=hashlib.file_digest(source,'sha256')
        if hasher.hexdigest()!=item['sha256']:raise ValueError('backup_file_digest_changed')
        (_validate_index if role=='chroma' else _validate_dump)(path)
    return manifest


def restore_backup_set(manifest,fresh_targets,backend):
    """只导入新私有目标，全部真实摘要匹配后返回验证；本函数永不开放业务入口。"""
    saved=manifest;data=_verify_files(saved)
    if backend.observe_sources()!=data['sources']:raise ValueError('restore_source_identity_changed')
    if not isinstance(fresh_targets,dict) or set(fresh_targets)!=set(ROLES):raise ValueError('fresh_targets_required')
    observed=backend.observe_fresh(fresh_targets)
    if observed!=fresh_targets:raise ValueError('fresh_observation_mismatch')
    ids=set(row['id'] for row in data['sources'].values())
    for role,target in fresh_targets.items():
        if (not isinstance(target,dict) or set(target)!={'id','owner','database','image','exists'}
                or target['id'] in ids or not isinstance(target['id'],str)
                or re.fullmatch('[0-9a-f]{64}',target['id']) is None or target['exists'] is not False
                or target['owner']!=data['owner'] or target['image']!=data['sources'][role]['image']
                or not isinstance(target['database'],str) or re.fullmatch('ag07_restore_[a-z0-9_]+',target['database']) is None):
            raise ValueError('unsafe_restore_target')
        ids.add(target['id'])
    # 源只读摘要与备份水位一致，恢复前后都核；目标验证不能掩盖源误改。
    before=backend.snapshot_sources();_facts(before)
    if before!=data['facts']:raise ValueError('source_changed_before_restore')
    deadline=backend.clock()+120
    for role in ROLES:
        if backend.clock()>=deadline:raise TimeoutError('restore_deadline')
        backend.restore_file(role,saved.root/data['files'][role]['file'],fresh_targets[role],deadline)
    restored=backend.verify_restored(fresh_targets,deadline);_facts(restored)
    after=backend.snapshot_sources()
    if backend.clock()>=deadline:raise TimeoutError('restore_deadline')
    if restored!=data['facts'] or after!=before or backend.observe_sources()!=data['sources']:
        raise ValueError('restore_facts_mismatch')
    return {'status':'verified','backup_sha256':saved.manifest_sha256,'source_unchanged':True,
            'business_opened':False,'model_calls':0}

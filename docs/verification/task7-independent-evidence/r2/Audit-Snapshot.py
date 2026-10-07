"""R2独立拟提交快照审计：核对Git索引与树对象、快照完整字节和895冻结源，禁止改产品或索引。

输入start/end阶段；Git只读取对象，按blob协议计算快照内容身份，输出非敏感路径/摘要。
"""
import hashlib
import json
import os
import subprocess
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path('C:/fdz-build')
META = ROOT / 'docs/verification/task7-r2-evidence'
PUBLIC = ROOT / 'docs/verification/task7-independent-evidence/r2'
REPOS = {'phone': ROOT, 'agent': Path('D:/project/-Demo-master')}
SNAPSHOTS = {name: ROOT / '.runtime/task7-delivery-r2' / name for name in REPOS}


def git(repo, *args):
    """读取固定仓库的Git结果；失败抛错，不执行stage、提交、tree写入或恢复命令。"""
    return subprocess.run(['git', '-C', str(repo), *args], check=True, capture_output=True).stdout


def digest(path):
    """计算文件当前完整字节SHA，不做换行或内容规范化。"""
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def main():
    """核对所有索引路径和受控SDK配置；只允许已知构建产物作为快照额外文件。"""
    phase = sys.argv[1]
    PUBLIC.mkdir(parents=True, exist_ok=True)
    output = PUBLIC / ('snapshot-' + phase + '.json')
    assert not output.exists(), '禁止覆盖首次快照结果'
    trees = json.loads((META / 'index-trees.json').read_text(encoding='utf-8-sig'))
    result = {'phase': phase, 'at': datetime.now().astimezone().isoformat(), 'repos': {}, 'errors': []}
    for name, repo in REPOS.items():
        snapshot = SNAPSHOTS[name]
        entries = {}
        for row in git(repo, 'ls-tree', '-r', '-z', trees[name]).split(b'\0'):
            if not row:
                continue
            header, path = row.split(b'\t', 1)
            mode, kind, blob = header.decode().split()
            assert kind == 'blob' and mode != '120000', '拟提交源含链接或子模块'
            entries[path.decode('utf-8')] = (mode, blob)
        index = {}
        for row in git(repo, 'ls-files', '--stage', '-z').split(b'\0'):
            if not row:
                continue
            header, path = row.split(b'\t', 1)
            mode, blob, stage = header.decode().split()
            assert stage == '0', '索引存在冲突'
            index[path.decode('utf-8')] = (mode, blob)
        if entries != index:
            result['errors'].append(name + ':当前index与冻结tree不符')
        proposed = (META / (name + '-proposed-paths.txt')).read_text(encoding='utf-8-sig').splitlines()
        changed = [x.decode('utf-8') for x in git(repo, 'diff', '--cached', '--name-only', '-z').split(b'\0') if x]
        if set(proposed) != set(changed):
            result['errors'].append(name + ':拟提交路径与当前index增量不符')
        checked = []
        autocrlf = git(repo, 'config', '--get', 'core.autocrlf').decode().strip()
        for path, (mode, blob) in entries.items():
            local = snapshot / path
            if not local.is_file():
                result['errors'].append(name + ':快照缺路径:' + path)
                continue
            body = local.read_bytes()
            # Git blob是原始字节，不借工作区过滤器或未来dirty文件补缺依赖。
            actual = hashlib.sha1(b'blob ' + str(len(body)).encode() + b'\0' + body).hexdigest()
            # checkout-index在core.autocrlf=true下按Git规则输出CRLF；只接受纯换行转换，不能忽略其它内容差异。
            clean = body.replace(b'\r\n', b'\n')
            clean_blob = hashlib.sha1(b'blob ' + str(len(clean)).encode() + b'\0' + clean).hexdigest()
            controlled_crlf = actual != blob and autocrlf == 'true' and b'\0' not in body and clean_blob == blob
            if actual != blob and not controlled_crlf:
                result['errors'].append(name + ':快照字节不等index blob:' + path)
            checked.append({'path': path, 'blob': blob, 'actualBlob': actual, 'bytes': len(body), 'controlledCrlfOnly': controlled_crlf, 'cleanBlob': clean_blob})
        extras = []
        links = []
        for folder, dirs, files in os.walk(snapshot, followlinks=False):
            for entry in dirs + files:
                node = Path(folder) / entry
                if node.is_symlink() or (node.stat().st_file_attributes & 0x400):
                    links.append(str(node.relative_to(snapshot)))
            for entry in files:
                relative = (Path(folder) / entry).relative_to(snapshot).as_posix()
                if relative in entries:
                    continue
                generated = relative.startswith(('android-app/.gradle/', 'android-app/.kotlin/', 'android-app/app/build/', 'android-app/build/', 'backend/target/')) or '__pycache__/' in relative
                controlled_sdk = name == 'phone' and relative == 'android-app/local.properties'
                extras.append({'path': relative, 'generated': generated, 'controlledSdk': controlled_sdk})
                if not generated and not controlled_sdk:
                    result['errors'].append(name + ':未知快照额外文件:' + relative)
        if links:
            result['errors'].append(name + ':快照含链接')
        index_path = Path(git(repo, 'rev-parse', '--path-format=absolute', '--git-path', 'index').decode().strip())
        result['repos'][name] = {'tree': trees[name], 'head': git(repo, 'rev-parse', 'HEAD').decode().strip(), 'indexSha256': digest(index_path), 'coreAutocrlf': autocrlf, 'proposedPaths': proposed, 'changedPaths': changed, 'treeFileCount': len(entries), 'checkedFileCount': len(checked), 'files': checked, 'extraFiles': extras, 'links': links}
    # 工作区的源保护不同于拟提交投影，九项授权R2变化明确列出，不能借零变化复用所有旧证据。
    manifest = json.loads((META / 'source-freeze.json').read_text(encoding='utf-8-sig'))
    old = json.loads((ROOT / 'docs/verification/task7-evidence/source-freeze.json').read_text(encoding='utf-8-sig'))
    old_map = {(x['repo'], x['path']): x['sha256'] for x in old['files']}
    frozen = []
    delta = []
    for item in manifest['files']:
        actual = digest(REPOS[item['repo']] / item['path'])
        frozen.append({'repo': item['repo'], 'path': item['path'], 'sha256': actual, 'unchanged': actual == item['sha256']})
        if actual != item['sha256']:
            result['errors'].append('895冻结变化:' + item['repo'] + '/' + item['path'])
        previous = old_map.get((item['repo'], item['path']))
        if previous != actual:
            delta.append({'repo': item['repo'], 'path': item['path'], 'r0Sha256': previous, 'r2Sha256': actual})
    result.update(sourceCount=len(frozen), sourceFiles=frozen, r0Delta=delta, manifestSha256=digest(META / 'source-freeze.json'))
    assert len(frozen) == 895, '冻结数量不符'
    if phase == 'end':
        before = json.loads((PUBLIC / 'snapshot-start-retry.json').read_text(encoding='utf-8'))
        for name in REPOS:
            for key in ('tree', 'head', 'indexSha256'):
                if before['repos'][name][key] != result['repos'][name][key]:
                    result['errors'].append(name + ':结束Git保护变化:' + key)
    result['passed'] = not result['errors']
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print('R2快照/源保护', phase, 'PASS' if result['passed'] else 'FAIL', '895源；错误', len(result['errors']))
    assert result['passed'], '独立快照审计失败，见脱敏路径证据'


if __name__ == '__main__':
    main()

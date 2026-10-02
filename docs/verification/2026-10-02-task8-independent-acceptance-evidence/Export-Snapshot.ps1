<# 独立验收入口：只读校验两仓冻结索引与工作文件，导出隔离快照并保留历史报告。
依赖 Git 与 PowerShell ZIP；输出仅含路径和摘要，不读取或复制凭据，不修改索引。
#>
$ErrorActionPreference = 'Stop'
$private = 'C:/fdz-build/.runtime/task8-independent-final'
$public = $PSScriptRoot
# 运行目录只能新建，避免第二次执行覆盖首次失败或既有验收证据。
if (Test-Path -LiteralPath $private) { throw '独立运行目录已存在，禁止覆盖' }
[IO.Directory]::CreateDirectory($private) | Out-Null
$freeze = Get-Content 'C:/fdz-build/docs/verification/task8-resumed-evidence/freeze-preacceptance.json' -Raw | ConvertFrom-Json
$repositories = @(
    @{root='C:/fdz-build'; folder='phone'; tree='1cc8fff9b0077672fd87d26a460a0fda5b7b7996'; head='f2f0803'},
    @{root='D:/project/-Demo-master'; folder='-Demo-master'; tree='0d29e30b066dd0b33d8cf244fa75d71ee3d5995a'; head='9fdc5e1'}
)
$receipt = @()
foreach ($repo in $repositories) {
    # 输入核验先于导出，HEAD 或索引不符即停止，不接受相近版本替代。
    $head = (& git -C $repo.root rev-parse HEAD).Trim()
    $tree = (& git -C $repo.root write-tree).Trim()
    if (!$head.StartsWith($repo.head) -or $tree -ne $repo.tree) { throw '冻结版本不一致' }
    $frozen = @($freeze.repositories | Where-Object repository -eq $repo.root)
    if ($frozen.Count -ne 1) { throw '冻结仓库条目缺失' }
    foreach ($entry in $frozen[0].files) {
        $hash = (Get-FileHash -LiteralPath (Join-Path $repo.root $entry.path) -Algorithm SHA256).Hash
        if ($hash -ne $entry.workingSha256) { throw "冻结文件发生变化：$($entry.path)" }
    }
    # 直接从索引树归档，避免混合工作区未来业务和本机配置进入构建证据。
    $destination = Join-Path $private $repo.folder
    & git -C $repo.root archive --format=zip --output="$private/$($repo.folder).zip" $tree
    if ($LASTEXITCODE) { throw '归档失败' }
    Expand-Archive -LiteralPath "$private/$($repo.folder).zip" -DestinationPath $destination
    & git -C $repo.root status --porcelain=v1 *> "$private/$($repo.folder)-status-start.txt"
    $receipt += [ordered]@{repository=$repo.root; head=$head; indexTree=$tree; snapshot=$destination; frozenFilesChecked=$frozen[0].files.Count}
}
# 历史报告按字节备份；之后只追加当前入口，不删除首次失败和暂停记录。
Copy-Item -LiteralPath 'C:/fdz-build/docs/verification/task8-acceptance.md' -Destination "$private/task8-acceptance-original.md"
$receipt | ConvertTo-Json -Depth 6 | Set-Content "$public/snapshot.json" -Encoding utf8
Write-Output '冻结校验、索引导出和历史报告备份完成。'

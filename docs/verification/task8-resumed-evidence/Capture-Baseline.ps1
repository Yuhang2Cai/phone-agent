<#
记录 Task8 恢复起点的源码与私有清单摘要；依赖 Git 和 PowerShell 文件 API。
完整源码只备份至忽略目录，公开结果仅含版本、路径与摘要，不输出凭据或业务内容。
#>
$ErrorActionPreference = 'Stop'
$private = 'C:/fdz-build/.runtime/task8-resumed'
$public = 'C:/fdz-build/docs/verification/task8-resumed-evidence'
if (Test-Path -LiteralPath "$private/source-start") { throw '恢复基线已存在，禁止覆盖' }
$repos = @(@{name='phone';root='C:/fdz-build';prefix='^(android-app/|backend/|maestro/|contracts/|scripts/|AGENTS.md$)'}, @{name='agent';root='D:/project/-Demo-master';prefix='^(service/|tests/|config/|intent/|dag/|workflows/|evaluation/|memory/|AGENTS.md$)'})
$manifest = @()
foreach ($repo in $repos) {
    # 从 Git 已跟踪及非忽略文件取候选，保留所有原改动，不把临时构建输出混入源码快照。
    $files = @(& git -C $repo.root -c core.quotePath=false ls-files --cached --others --exclude-standard) | Sort-Object -Unique
    $head = & git -C $repo.root rev-parse HEAD
    foreach ($file in $files) {
        if ($file -notmatch $repo.prefix -or $file -match '(__pycache__|\.pyc$|/results/|/build/|/target/)') { continue }
        $source = Join-Path $repo.root $file
        if (!(Test-Path -LiteralPath $source -PathType Leaf)) { continue }
        $dest = "$private/source-start/$($repo.name)/$file"
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($dest)) | Out-Null
        Copy-Item -LiteralPath $source -Destination $dest
        $manifest += [ordered]@{repo=$repo.name;head=$head;path=$file;sha256=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash}
    }
}
# 旧私有清单只登记摘要，不复制到公开目录，也不修改任何原夹具。
$oldJson = @(Get-ChildItem -LiteralPath 'C:/fdz-build/.runtime' -File -Recurse -Filter '*.json' | Where-Object { !$_.FullName.StartsWith(($private -replace '/', '\'), [StringComparison]::OrdinalIgnoreCase) } | ForEach-Object { [ordered]@{path=$_.FullName;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash} })
[IO.Directory]::CreateDirectory($public) | Out-Null
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$public/source-start.json" -Encoding utf8
$oldJson | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$private/old-json-start.json" -Encoding utf8
[ordered]@{sourceFiles=$manifest.Count;protectedJson=$oldJson.Count;at=(Get-Date).ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath "$public/baseline-summary.json" -Encoding utf8
Write-Output "源码基线 $($manifest.Count) 个文件；原私有 JSON $($oldJson.Count) 个，仅记录摘要。"

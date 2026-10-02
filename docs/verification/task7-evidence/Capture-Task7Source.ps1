# 本模块保存 Task7 开始字节和源摘要；输入是两仓当前工作区，输出为忽略目录备份及公开 SHA 台账。
# 不触碰 Git 索引；目录仅复制代码与受保护资料，避免把运行凭据和生成缓存纳入审查。
param([ValidateSet('start','freeze')][string]$Phase = 'start')
$ErrorActionPreference = 'Stop'
$phoneRoot = 'C:\fdz-build'
$agentRoot = 'D:\project\-Demo-master'
$privateRoot = Join-Path $phoneRoot ".runtime\task7\source-$Phase"
$publicRoot = Join-Path $phoneRoot 'docs\verification\task7-evidence'
if (Test-Path -LiteralPath $privateRoot) { throw "禁止覆盖已有源快照：$Phase" }
New-Item -ItemType Directory -Path $privateRoot -Force | Out-Null
$rows = @()
foreach ($repo in @(@{name='phone';root=$phoneRoot},@{name='agent';root=$agentRoot})) {
    # 从版本管理文件和未忽略文件收集输入，随后限定产品与测试目录；不遍历链接或 runtime。
    $paths = @(& git -C $repo.root ls-files --cached --others --exclude-standard) | Sort-Object -Unique
    foreach ($relative in $paths) {
        $sourcePath = Join-Path $repo.root $relative
        $product = if ($repo.name -eq 'phone') {
            $relative -match '^(backend/src/|backend/pom.xml$|android-app/app/src/|android-app/[^/]+\.kts$|android-app/app/[^/]+\.kts$|maestro/|contracts/|scripts/)'
        } else {
            $relative -match '^(service/|tests/|config/|intent/|dag/|workflows/|evaluation/|memory/|node/)' -and $relative -notmatch '(chroma_db|__pycache__|\.pyc$)'
        }
        $protected = $relative -match '(task8|task-8|task7-start|AGENTS\.md|docs/superpowers/(specs|plans)/|docs/ui-design/ui-design-spec\.md)'
        if (($product -or $protected) -and (Test-Path -LiteralPath $sourcePath -PathType Leaf) -and $relative -notmatch '(target/|build/|\.runtime/|\.pytest|results/|\.xml$.*target)') {
            # 每项同时复制原字节和记录摘要，后续 delta 对照这份工作区而非误用干净 HEAD。
            $destination = Join-Path $privateRoot "$($repo.name)\$relative"
            New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
            Copy-Item -LiteralPath $sourcePath -Destination $destination
            $file = Get-Item -LiteralPath $sourcePath
            $rows += [pscustomobject]@{repo=$repo.name;path=$relative;sha256=(Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash;bytes=$file.Length;protected=$protected}
        }
    }
}
$manifest = [pscustomobject]@{phase=$Phase;capturedAt=(Get-Date).ToString('o');phoneBase=(& git -C $phoneRoot rev-parse HEAD);agentBase=(& git -C $agentRoot rev-parse HEAD);files=$rows}
# 输出是工具生成的台账；公开文件只有路径、长度和摘要，不输出源码中的运行凭据。
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $publicRoot "source-$Phase.json") -Encoding utf8
Write-Output "phase=$Phase files=$($rows.Count) phoneBase=$($manifest.phoneBase) agentBase=$($manifest.agentBase)"

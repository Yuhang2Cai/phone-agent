# Task5 fix round1冻结：只生成差异/哈希/写集，不暂存提交，不把原Task8脏文件纳入修复归属。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$evidence = Join-Path $root 'docs/verification/task5-evidence/fixround1'
$before = Join-Path $root '.runtime/task5-fixround1-before'
$previous = Get-Content -LiteralPath (Join-Path $evidence 'previous-freeze-manifest.json') -Raw | ConvertFrom-Json
$patches = [Collections.Generic.List[string]]::new()
$delta = [Collections.Generic.List[object]]::new()
$created = @(
    'android-app/app/src/test/java/com/fandazi/app/feature/agent/Task5ConfirmationActionTest.kt',
    'scripts/task5-fixround1-session-snapshot.py',
    'scripts/task5-fixround1-identity.py',
    'scripts/Save-Task5FixRound1Freeze.ps1',
    'maestro/task5-fixround1-stale-prepare.yaml',
    'maestro/task5-fixround1-old-tap.yaml',
    'maestro/task5-fixround1-current-cancel.yaml'
)
Push-Location $root
try {
    # 修复前像包含R0实现及原脏依赖，补丁只适用这些精确SHA；不得从HEAD整文件覆盖共享源。
    foreach ($file in Get-ChildItem -LiteralPath $before -Recurse -File) {
        $relative = [IO.Path]::GetRelativePath($before, $file.FullName).Replace('\','/')
        $old = (Get-FileHash -LiteralPath $file.FullName).Hash
        $current = (Get-FileHash -LiteralPath (Join-Path $root $relative)).Hash
        if ($old -eq $current) { continue }
        $delta.Add([pscustomobject]@{path=$relative;classification='Fix round1修改R0产品';before=$old;after=$current;preimage=$file.FullName})
        $diff = & git -c core.autocrlf=false diff --no-index -- ([IO.Path]::GetRelativePath($root,$file.FullName).Replace('\','/')) $relative 2>$null
        if ($LASTEXITCODE -ne 1) { throw "生成修复补丁失败：$relative" }
        $patches.Add(($diff -join "`n").Replace('a/.runtime/task5-fixround1-before/','a/'))
    }
    foreach ($relative in $created) {
        $delta.Add([pscustomobject]@{path=$relative;classification='Fix round1新增测试或证据运行器';before=$null;after=(Get-FileHash -LiteralPath (Join-Path $root $relative)).Hash;preimage=$null})
        $diff = & git -c core.autocrlf=false diff --no-index -- /dev/null $relative 2>$null
        if ($LASTEXITCODE -ne 1) { throw "生成新增补丁失败：$relative" }
        $patches.Add($diff -join "`n")
    }
    $patches -join "`n`n" | Set-Content -LiteralPath (Join-Path $evidence 'fixround1.patch') -Encoding utf8
    # 累计冻结沿用原Task5归属；只有上述精确修复覆盖旧哈希，其余产品/依赖必须维持R0版本。
    $cumulative = [Collections.Generic.List[object]]::new()
    foreach ($row in $previous.source) {
        $change = @($delta | Where-Object path -eq $row.path)
        $actual = (Get-FileHash -LiteralPath (Join-Path $root $row.path)).Hash
        if (-not $change.Count -and $actual -ne $row.after) { throw "非修复写集发生变化：$($row.path)" }
        $cumulative.Add([pscustomobject]@{path=$row.path;classification=$row.classification;after=$actual;round1Modified=($change.Count -gt 0)})
    }
    foreach ($row in $delta) {
        if (-not @($cumulative | Where-Object path -eq $row.path).Count) { $cumulative.Add($row) }
    }
    $protected = @($previous.protectedFiles | ForEach-Object {
        $actual = (Get-FileHash -LiteralPath $_.path).Hash
        [pscustomobject]@{path=$_.path;before=$_.before;current=$actual;equal=($actual -eq $_.before)}
    })
    if (@($protected | Where-Object { -not $_.equal }).Count) { throw '原真实夹具哈希变化，不能冻结为保护通过' }
    foreach ($dependency in $previous.unchangedDependencies) {
        if ((Get-FileHash -LiteralPath (Join-Path $root $dependency.path)).Hash -ne $dependency.sha256) { throw "原脏依赖变化：$($dependency.path)" }
    }
    $staged = @(& git diff --cached --name-only)
    if ($staged.Count) { throw '检测到暂存变化，不能声明未stage' }
    $canonical = @($cumulative | Sort-Object path | ForEach-Object { "$($_.path):$($_.after)" }) -join "`n"
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { $freezeSha = [Convert]::ToHexString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))) }
    finally { $hasher.Dispose() }
    $documents = @('docs/verification/2026-10-02-task5-development.md',
        '.superpowers/sdd/2026-09-29-group-buying-agent-refactor-implementation/task-5-report.md')
    $evidenceFiles = @(Get-ChildItem -LiteralPath $evidence -File | Where-Object { $_.Name -notin @('freeze-manifest.json','write-set.txt') }) +
        @(Get-ChildItem -LiteralPath (Join-Path $root 'docs/verification/task5-evidence') -Directory -Filter 'maestro-fixround1-*' | Get-ChildItem -File)
    $documentRows = @($documents | ForEach-Object { [pscustomobject]@{path=$_;sha256=(Get-FileHash -LiteralPath (Join-Path $root $_)).Hash} })
    $evidenceRows = @($evidenceFiles | ForEach-Object { [pscustomobject]@{path=[IO.Path]::GetRelativePath($root,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName).Hash} })
    [pscustomobject]@{recordedAt=(Get-Date -Format o);status='开发修复自测，待Jason独立复验';phoneHead=(& git rev-parse HEAD).Trim();
        sourceFreezeSha256=$freezeSha;apkSha256=(Get-FileHash -LiteralPath 'android-app/app/build/outputs/apk/debug/app-debug.apk').Hash;
        delta=$delta;cumulativeSource=$cumulative;unchangedDependencies=$previous.unchangedDependencies;protectedFiles=$protected;
        staged=$staged;documents=$documentRows;evidence=$evidenceRows;patchBase='Fix round1仅适用delta.before原像，累计实施需要R0补丁/依赖边界；不把共享导航Task6/7/8 hunk归Task5'} |
        ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $evidence 'freeze-manifest.json') -Encoding utf8
    @($delta.path) + $documents + @($evidenceRows.path) + @('docs/verification/task5-evidence/fixround1/freeze-manifest.json') |
        ForEach-Object { (Join-Path $root $_).Replace('\','/') } | Sort-Object -Unique |
        Set-Content -LiteralPath (Join-Path $evidence 'write-set.txt') -Encoding utf8
    [pscustomobject]@{delta=$delta.Count;cumulativeSource=$cumulative.Count;freezeSha256=$freezeSha;protectedFiles=$protected.Count} | ConvertTo-Json
} finally { Pop-Location }
exit 0

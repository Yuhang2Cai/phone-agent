# Task6 源码证据工具：保存双仓编译输入的字节快照和摘要，不复制运行凭据、产物或链接。
param([ValidateSet('before','after')][string]$Phase = 'before')
$ErrorActionPreference = 'Stop'
$output = 'C:/fdz-build/.runtime/task6-source-' + $Phase
New-Item -ItemType Directory -Force -Path $output | Out-Null
$rows = @()
foreach ($repo in @(@{name='phone';root='C:/fdz-build';paths=@('backend/src','android-app/app/src','maestro','scripts')}, @{name='agent';root='D:/project/-Demo-master';paths=@('service','tests')})) {
    foreach ($relative in $repo.paths) {
        # 仅保存普通源码文件；历史缓存、证据和密钥不属于产品输入。
        foreach ($file in Get-ChildItem -LiteralPath (Join-Path $repo.root $relative) -File -Recurse) {
            if ($file.FullName -match '__pycache__|\.pyc$' -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) { continue }
            $path = [IO.Path]::GetRelativePath($repo.root,$file.FullName)
            $target = Join-Path $output ($repo.name + '/' + $path)
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
            Copy-Item -LiteralPath $file.FullName -Destination $target
            $rows += [pscustomobject]@{repo=$repo.name;path=$path;sha256=(Get-FileHash -LiteralPath $file.FullName).Hash}
        }
    }
}
$rows | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output 'manifest.json') -Encoding utf8
[pscustomobject]@{phase=$Phase;files=$rows.Count;output=$output} | ConvertTo-Json

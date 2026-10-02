# Task7 私有夹具保护：仅枚举原 Task2/5/8 的 JSON 文件并采集字节摘要，不读取或输出旧凭据。
param([ValidateSet('start','after')][string]$Phase='start')
$ErrorActionPreference='Stop'
$root='C:/fdz-build/.runtime'
$output="C:/fdz-build/docs/verification/task7-evidence/protected-$Phase.json"
if (Test-Path -LiteralPath $output) { throw '保护摘要已存在，禁止覆盖开始证据' }
# 固定选择原任务清单；排除本任务目录，避免把新账号误算成原任务变化。
$files=Get-ChildItem -LiteralPath $root -File -Filter '*.json' | Where-Object { $_.Name -match '^task(2|5|6|8)' }
$directories=Get-ChildItem -LiteralPath $root -Directory | Where-Object { $_.Name -match '^task(2|5|6|8)' }
foreach($directory in $directories) { $files+=Get-ChildItem -LiteralPath $directory.FullName -File -Recurse -Filter '*.json' }
$entries=@($files | Sort-Object FullName -Unique | ForEach-Object {
    [pscustomobject]@{path=$_.FullName.Substring($root.Length+1);bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
})
$entries | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $output -Encoding utf8
if($Phase -eq 'after') {
    $before=Get-Content -LiteralPath 'C:/fdz-build/docs/verification/task7-evidence/protected-start.json' -Raw | ConvertFrom-Json
    if (($before | ConvertTo-Json -Depth 4 -Compress) -ne ($entries | ConvertTo-Json -Depth 4 -Compress)) { throw '原任务私有清单字节变化，需逐项审查' }
}
Write-Output "protected $Phase : $($entries.Count) files"

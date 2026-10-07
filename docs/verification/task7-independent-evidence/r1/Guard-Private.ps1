# R1私有清单保护：只枚举并计算原Task2/5/6/8 JSON字节，不读取旧凭据；按三个事实字段比较。
param([ValidateSet('start','end')][string]$Phase)
$ErrorActionPreference='Stop'
$task7Root='C:/fdz-build/.runtime';$task7Public='C:/fdz-build/docs/verification/task7-independent-evidence/r1'
$task7Output="$task7Public/private-$Phase.json"
if(Test-Path $task7Output){throw '不得覆盖原私有保护结果'}
$task7Files=@(Get-ChildItem $task7Root -File -Filter '*.json' | Where-Object Name -match '^task(2|5|6|8)')
Get-ChildItem $task7Root -Directory | Where-Object Name -match '^task(2|5|6|8)' | ForEach-Object {$task7Files+=Get-ChildItem $_.FullName -Recurse -File -Filter '*.json'}
$task7Current=@($task7Files | Sort-Object FullName -Unique | ForEach-Object {@{path=$_.FullName.Substring($task7Root.Length+1);bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}})
$task7Baseline=Get-Content 'C:/fdz-build/docs/verification/task7-independent-evidence/protected-after.json' -Raw | ConvertFrom-Json
$task7Before=@{};$task7Baseline | ForEach-Object {$task7Before[$_.path]=$_}
$task7Changed=@($task7Current | Where-Object {!$task7Before.ContainsKey($_.path) -or $task7Before[$_.path].bytes -ne $_.bytes -or $task7Before[$_.path].sha256 -ne $_.sha256})
$task7Missing=@($task7Baseline | Where-Object {$_.path -notin $task7Current.path})
@{at=(Get-Date -Format o);phase=$Phase;count=$task7Current.Count;changedCount=$task7Changed.Count;missingCount=$task7Missing.Count;entries=$task7Current;passed=($task7Changed.Count -eq 0 -and $task7Missing.Count -eq 0)} | ConvertTo-Json -Depth 5 | Set-Content $task7Output -Encoding utf8
if($task7Changed.Count -or $task7Missing.Count){throw '原Task私有JSON字节变化'}
Write-Output "R1私有保护 $Phase $($task7Current.Count)项未变"

# R1只统计两个自然401的四段运行；不读取或导出Maestro的env/inputText等敏感命令值。
$ErrorActionPreference='Stop'
$task7Base='C:/fdz-build/docs/verification/task7-independent-evidence';$task7Public="$task7Base/r1";$task7Private='C:/fdz-build/.runtime/task7-independent'
$task7Runs=@();$task7Witness=@()
foreach($task7Entry in @('order','voucher')){
 foreach($task7Phase in @('prepare','return')){
  $task7Label="r1-401-$task7Entry-$task7Phase"
  $task7Path="$task7Base/maestro-$task7Label/result.json"
  if(!(Test-Path $task7Path)){continue}
  $task7Result=Get-Content $task7Path -Raw | ConvertFrom-Json
  $task7CommandFiles=@(Get-ChildItem "$task7Private/maestro-$task7Label" -Recurse -File -Filter commands.json)
  if($task7CommandFiles.Count -ne 1){throw "R1完整命令树数量不符:$task7Label"}
  $task7Tree=Get-Content $task7CommandFiles[0].FullName -Raw | ConvertFrom-Json
  $task7Statuses=@($task7Tree | Group-Object {$_.metadata.status} | ForEach-Object {@{status=$_.Name;count=$_.Count}})
  $task7Shots=@(Get-ChildItem (Split-Path $task7Path) -Recurse -File -Filter '*.png' | ForEach-Object {@{path=[IO.Path]::GetRelativePath($task7Base,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}})
  $task7Runs+=@{label=$task7Label;entry=$task7Entry;phase=$task7Phase;exitCode=$task7Result.exitCode;startedAt=$task7Result.startedAt;finishedAt=$task7Result.finishedAt;commands=$task7Tree.Count;statuses=$task7Statuses;apkSha256=$task7Result.apkSha256;screenshots=$task7Shots}
 }
}
# 服务访问日志只记录时间/方法/路径/状态；自然过期证据必须包含原资源401、登录200、同资源200。
$task7Requests=@()
foreach($task7Line in @(Get-ChildItem "$task7Private/r1/http-access" -File | ForEach-Object {Get-Content $_.FullName})){
 if($task7Line -match '^\[(?<time>\d{2}/[A-Za-z]{3}/\d{4}:\d{2}:\d{2}:\d{2}) (?<offset>[+-]\d{4})\] (?<method>[A-Z]+) (?<path>\S+) (?<status>\d+)$'){
  $task7At=[DateTimeOffset]::ParseExact("$($Matches.time) $($Matches.offset.Insert(3,':'))",'dd/MMM/yyyy:HH:mm:ss zzz',[Globalization.CultureInfo]::InvariantCulture)
  $task7Requests+=@{at=$task7At.ToString('o');method=$Matches.method;path=$Matches.path;status=[int]$Matches.status}
 }
}
$task7Orders=Get-Content "$task7Private/orders.json" -Raw | ConvertFrom-Json
foreach($task7Entry in @('order','voucher')){
 $task7Prepare=$task7Runs | Where-Object { $_.entry -eq $task7Entry -and $_.phase -eq 'prepare' }
 $task7Return=$task7Runs | Where-Object { $_.entry -eq $task7Entry -and $_.phase -eq 'return' }
 if(!$task7Prepare -or !$task7Return){continue}
 $task7Target=if($task7Entry -eq 'order'){"/api/group-orders/$($task7Orders[0].orderId)"}else{"/api/vouchers/$($task7Orders[0].vouchers[0].publicId)"}
 $task7Window=@($task7Requests | Where-Object {[DateTimeOffset]$_.at -ge [DateTimeOffset]$task7Prepare.startedAt -and [DateTimeOffset]$_.at -le [DateTimeOffset]$task7Return.finishedAt})
 $task7Denied=@($task7Window | Where-Object {$_.path -eq $task7Target -and $_.status -eq 401})
 $task7Login=$task7Window | Where-Object {$_.path -eq '/api/auth/login' -and $_.status -eq 200} | Select-Object -Last 1
 $task7Restored=@($task7Window | Where-Object {$_.path -eq $task7Target -and $_.status -eq 200 -and [DateTimeOffset]$_.at -ge [DateTimeOffset]$task7Login.at})
 $task7Expiry=Get-Content "$task7Public/expiry-$task7Entry.json" -Raw | ConvertFrom-Json
 $task7Passed=$task7Prepare.exitCode -eq 0 -and $task7Return.exitCode -eq 0 -and $task7Denied.Count -ge 1 -and $task7Restored.Count -ge 1 -and [DateTimeOffset]$task7Denied[0].at -ge [DateTimeOffset]$task7Expiry.waitUntil
 $task7Witness+=@{entry=$task7Entry;originalTarget=$task7Target;expiry=$task7Expiry;requests=$task7Window;real401=$task7Denied.Count;originalRestored200=$task7Restored.Count;passed=$task7Passed}
}
$task7Runs | ConvertTo-Json -Depth 7 | Set-Content "$task7Public/maestro-counts.json" -Encoding utf8
$task7Witness | ConvertTo-Json -Depth 7 | Set-Content "$task7Public/natural401-api.json" -Encoding utf8
Write-Output "独立R1已完整运行$($task7Runs.Count)段，已形成$($task7Witness.Count)个401原目标证据"
if(@($task7Witness | Where-Object {!$_.passed}).Count){throw 'R1真实401/同原目标恢复证据失败'}

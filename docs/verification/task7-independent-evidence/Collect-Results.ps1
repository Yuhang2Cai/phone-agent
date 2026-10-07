# 独立统计器：只导出命令状态、截图指纹和只读访问路径，禁止导出原命令中的登录变量。
$ErrorActionPreference='Stop'
$task7Root='C:/fdz-build';$task7Public="$task7Root/docs/verification/task7-independent-evidence";$task7Private="$task7Root/.runtime/task7-independent"
$task7Runs=@()
foreach($task7Result in Get-ChildItem $task7Public -Recurse -File -Filter result.json){
 if($task7Result.Directory.Name -notlike 'maestro-*'){continue}
 $task7Info=Get-Content $task7Result.FullName -Raw | ConvertFrom-Json
 $task7Label=$task7Result.Directory.Name.Substring(8)
 $task7Commands=@(Get-ChildItem "$task7Private/maestro-$task7Label" -Recurse -File -Filter commands.json)
 if($task7Commands.Count -ne 1){throw "命令树数量异常:$task7Label"}
 $task7Tree=Get-Content $task7Commands[0].FullName -Raw | ConvertFrom-Json
 # 不输出 evaluatedCommand/env/inputText：这些对象包含真实口令，不能进入公开统计。
 $task7Statuses=@($task7Tree | Group-Object {$_.metadata.status} | ForEach-Object {@{status=$_.Name;count=$_.Count}})
 $task7Shots=@(Get-ChildItem $task7Result.Directory.FullName -Recurse -File -Filter '*.png' | ForEach-Object {@{path=[IO.Path]::GetRelativePath($task7Public,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}})
 $task7Runs+=@{label=$task7Label;flow=$task7Info.flow;startedAt=$task7Info.startedAt;finishedAt=$task7Info.finishedAt;exitCode=$task7Info.exitCode;apkSha256=$task7Info.apkSha256;commands=$task7Tree.Count;statuses=$task7Statuses;screenshots=$task7Shots}
}
$task7Runs | Sort-Object startedAt | ConvertTo-Json -Depth 7 | Set-Content "$task7Public/maestro-counts.json" -Encoding utf8
# 网络恢复不是只检查开关：按恢复流程真实时间窗口从访问日志提取原 API 的成功响应。
$task7LogLines=@(Get-ChildItem "$task7Root/.runtime/task7-http-access","$task7Private/http-access" -File | ForEach-Object {Get-Content $_.FullName})
$task7Requests=@()
foreach($task7Line in $task7LogLines){
 if($task7Line -match '^\[(?<time>\d{2}/[A-Za-z]{3}/\d{4}:\d{2}:\d{2}:\d{2}) (?<offset>[+-]\d{4})\] (?<method>[A-Z]+) (?<path>\S+) (?<status>\d+)$'){
  $task7At=[DateTimeOffset]::ParseExact("$($Matches.time) $($Matches.offset.Insert(3,':'))",'dd/MMM/yyyy:HH:mm:ss zzz',[Globalization.CultureInfo]::InvariantCulture)
  $task7Requests+=@{at=$task7At.ToString('o');method=$Matches.method;path=$Matches.path;status=[int]$Matches.status}
 }
}
$task7Network=@()
foreach($task7Label in @('network-recover','voucher-network-recover')){
 $task7Run=$task7Runs | Where-Object label -eq $task7Label
 if(!$task7Run){continue}
 $task7Selected=@($task7Requests | Where-Object { [DateTimeOffset]$_.at -ge ([DateTimeOffset]$task7Run.startedAt).AddSeconds(-1) -and [DateTimeOffset]$_.at -le [DateTimeOffset]$task7Run.finishedAt -and $_.method -eq 'GET' -and ($_.path -eq '/api/group-orders' -or $_.path -match '^/api/(group-orders|vouchers)/[a-f0-9-]+$') })
 $task7Network+=@{label=$task7Label;requests=$task7Selected;successfulOriginalReads=@($task7Selected | Where-Object status -eq 200).Count}
}
$task7Network | ConvertTo-Json -Depth 5 | Set-Content "$task7Public/network-api-confirmed.json" -Encoding utf8
# 自然401只公开本轮过期准备到返回的原路径/状态，原始访问日志本身不含令牌或正文。
$task7Expiry=@()
foreach($task7Entry in @('orders','order','voucher')){
 $task7Prepare=$task7Runs | Where-Object label -eq "401-$task7Entry-prepare"
 $task7Return=$task7Runs | Where-Object label -eq "401-$task7Entry-return"
 if(!$task7Prepare -or !$task7Return){continue}
 $task7Selected=@($task7Requests | Where-Object {[DateTimeOffset]$_.at -ge [DateTimeOffset]$task7Prepare.startedAt -and [DateTimeOffset]$_.at -le [DateTimeOffset]$task7Return.finishedAt})
 $task7Expiry+=@{entry=$task7Entry;requests=$task7Selected;actual401=@($task7Selected | Where-Object status -eq 401).Count}
}
$task7Expiry | ConvertTo-Json -Depth 5 | Set-Content "$task7Public/natural401-api.json" -Encoding utf8
Write-Output "独立统计: Maestro=$($task7Runs.Count)，自然401入口=$($task7Expiry.Count)"

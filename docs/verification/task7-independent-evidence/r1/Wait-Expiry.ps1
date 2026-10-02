# 独立自然过期验证：输入原入口与已实测期限，读取本轮设备登录访问记录，输出脱敏时间证据。
param([ValidateSet('orders','order','voucher')][string]$Entry,[int]$LifetimeSeconds=120)
$ErrorActionPreference='Stop'
$task7Root='C:/fdz-build';$task7Private="$task7Root/.runtime/task7-independent/r1"
# prepare 已结束且期间没有其他登录；只接受最近五分钟内的真实成功设备登录，避免引用旧轮次。
$task7Lines=@(Get-ChildItem "$task7Private/http-access" -File | Sort-Object LastWriteTime | ForEach-Object {Get-Content $_.FullName})
$task7Line=$task7Lines | Where-Object {$_ -match 'POST /api/auth/login 200$'} | Select-Object -Last 1
if($task7Line -notmatch '^\[(?<time>\d{2}/[A-Za-z]{3}/\d{4}:\d{2}:\d{2}:\d{2}) (?<offset>[+-]\d{4})\]'){throw '无法核实本轮设备登录时间'}
$task7Offset=$Matches.offset.Insert(3,':')
$task7Login=[DateTimeOffset]::ParseExact("$($Matches.time) $task7Offset",'dd/MMM/yyyy:HH:mm:ss zzz',[Globalization.CultureInfo]::InvariantCulture)
if(([DateTimeOffset]::Now-$task7Login).TotalSeconds -gt 300){throw '登录记录过旧'}
$task7Deadline=$task7Login.AddSeconds($LifetimeSeconds+4)
$task7Evidence="$task7Root/docs/verification/task7-independent-evidence/r1/expiry-$Entry.json"
if(Test-Path $task7Evidence){throw '禁止覆盖首次过期记录'}
@{entry=$Entry;actualLoginAt=$task7Login.ToString('o');lifetimeSeconds=$LifetimeSeconds;waitUntil=$task7Deadline.ToString('o');ordinaryJwt=$true;tokenTampered=$false} | ConvertTo-Json | Set-Content $task7Evidence -Encoding utf8
# 四秒余量覆盖访问日志秒精度与服务验证时钟；不改令牌、设备时间或开启故障注入。
while([DateTimeOffset]::Now -lt $task7Deadline){
 $task7Remaining=[math]::Ceiling(($task7Deadline-[DateTimeOffset]::Now).TotalSeconds)
 Write-Output "独立自然过期 $Entry 剩余 $task7Remaining 秒"
 Start-Sleep -Seconds ([math]::Min(30,$task7Remaining))
}
Write-Output "独立自然过期 $Entry 已到达"

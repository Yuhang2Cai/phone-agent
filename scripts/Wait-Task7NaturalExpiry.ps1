# Task7 普通JWT自然过期：按真实成功登录访问时间等待，不改设备令牌或使用故障夹具。
param([ValidateSet('orders','order','voucher')][string]$Entry,[int]$LifetimeSeconds=180)
$ErrorActionPreference='Stop'
$root='C:/fdz-build'
# 最近一次成功登录是prepare流程设备登录；日志仅路径/状态，不记录用户名口令或JWT。
$lines=@(Get-ChildItem "$root/.runtime/task7-http-access" -File | Sort-Object LastWriteTime | ForEach-Object {Get-Content $_.FullName})
$line=$lines | Where-Object {$_ -match 'POST /api/auth/login 200$'} | Select-Object -Last 1
if($line -notmatch '^\[(?<time>\d{2}/[A-Za-z]{3}/\d{4}:\d{2}:\d{2}:\d{2}) (?<offset>[+-]\d{4})\]') {throw '无法核实设备登录实际时间'}
$offset=$Matches.offset.Insert(3,':')
$login=[DateTimeOffset]::ParseExact("$($Matches.time) $offset",'dd/MMM/yyyy:HH:mm:ss zzz',[Globalization.CultureInfo]::InvariantCulture)
$deadline=$login.AddSeconds($LifetimeSeconds+4)
@{entry=$Entry;actualLoginAt=$login.ToString('o');lifetimeSeconds=$LifetimeSeconds;waitUntil=$deadline.ToString('o');ordinaryJwt=$true;tokenTampered=$false} | ConvertTo-Json | Set-Content "$root/docs/verification/task7-evidence/expiry-$Entry.json" -Encoding utf8
# 等待分批进行，控制台每30秒报告余秒；用户新消息不会被单次超长阻塞掩盖。
while([DateTimeOffset]::Now -lt $deadline) {
 $remaining=[math]::Ceiling(($deadline-[DateTimeOffset]::Now).TotalSeconds)
 Write-Output "Task7 natural expiry $Entry remaining=$remaining seconds"
 Start-Sleep -Seconds ([math]::Min(30,$remaining))
}
Write-Output "Task7 natural expiry $Entry reached"

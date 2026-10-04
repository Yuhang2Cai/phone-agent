# 普通HTTP设备验收运行器：配置专用模拟器代理，透传真实18080接口并延迟指定响应；finally恢复网络环境。
param(
    [Parameter(Mandatory=$true)][string]$ApkPath,
    [Parameter(Mandatory=$true)][string]$FixturePath,
    [Parameter(Mandatory=$true)][string]$EvidenceDirectory,
    [ValidateSet('all','filter','detail','chat')][string]$Section='all',
    [ValidatePattern('^emulator-\d+$')][string]$Device='emulator-5560'
)
$ErrorActionPreference='Stop'
$adb='C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$maestro='C:/Users/caid0/.local/maestro/bin/maestro.bat'
$python='C:/Users/caid0/anaconda3/python.exe'
$root=Split-Path -Parent $PSScriptRoot
$proxyUrl='http://127.0.0.1:18082/__control'
$env:JAVA_HOME='C:/Users/caid0/.jdks/ms-17.0.17'
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
$env:PYTHONUTF8='1'

# 输入校验：只改已启动的专用模拟器，不启动另一服务或修改任何业务库；已有代理不为本任务强行覆盖。
if (!(Test-Path -LiteralPath $ApkPath) -or !(Test-Path -LiteralPath $FixturePath)) { throw '缺少最新APK或既有私有合成账号' }
if (!(& $adb devices | Select-String "^$Device\s+device")) { throw '指定模拟器未就绪' }
$originalProxy=(& $adb -s $Device shell settings get global http_proxy).Trim()
if ($originalProxy -notin @(':0','null','')) { throw '模拟器已有其他代理，不能覆盖未知配置' }
if ((& $adb -s $Device reverse --list) -match 'tcp:18082') { throw '设备已有18082反向转发，不能覆盖未知设置' }
$null=Invoke-RestMethod -NoProxy 'http://127.0.0.1:18080/api/stores/cities'
$fixture=Get-Content -LiteralPath $FixturePath -Raw | ConvertFrom-Json
$account=$fixture.scenarios | Select-Object -First 1
$env:MAESTRO_USERNAME=$account.username;$env:MAESTRO_PASSWORD=$account.password
New-Item -ItemType Directory -Force $EvidenceDirectory | Out-Null
$proxyProcess=$null

function Get-OrderFacts {
    # 只读本人订单并构造数量/摘要；凭据及完整订单仅在内存短暂存在，聊天hello不应改变交易事实。
    $loginBody=@{username=$account.username;password=$account.password} | ConvertTo-Json -Compress
    $login=Invoke-RestMethod -NoProxy -Method Post -ContentType 'application/json' -Body $loginBody 'http://127.0.0.1:18080/api/auth/login'
    # 显式保留JSON数组形状：避免Invoke-RestMethod的数组对象被@()包装成一项，空订单必须计为零。
    $wire=Invoke-WebRequest -NoProxy -Headers @{Authorization="Bearer $($login.token)"} 'http://127.0.0.1:18080/api/group-orders'
    $orders=ConvertFrom-Json -InputObject $wire.Content -NoEnumerate
    if ($orders -isnot [Array]) { throw '本人订单接口未返回数组，不能构造验收事实' }
    $raw=ConvertTo-Json -InputObject $orders -Depth 20 -Compress
    # 券数量按每笔订单的实际非空券求和，缺失或null券字段不得被管道计为一张券。
    $voucherCount=0
    foreach ($order in $orders) {
        foreach ($voucher in $order.vouchers) { if ($null -ne $voucher) { $voucherCount++ } }
    }
    $digest=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes([string]$raw)))
    return @{orders=$orders.Count;vouchers=$voucherCount;sha256=$digest}
}
$beforeFacts=Get-OrderFacts
try {
    # 隐藏启动可恢复的宿主机辅助进程；路径无私人凭据，PID只用于本次finally精确关闭。
    $proxyProcess=Start-Process -FilePath $python -ArgumentList @('-u',(Join-Path $PSScriptRoot 'AdvancedRequestDelayProxy.py')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $EvidenceDirectory 'proxy-stdout.log') -RedirectStandardError (Join-Path $EvidenceDirectory 'proxy-stderr.log')
    $deadline=(Get-Date).AddSeconds(10)
    while ($true) {
        try { $null=Invoke-RestMethod -NoProxy $proxyUrl; break } catch {
            if ($proxyProcess.HasExited -or (Get-Date) -gt $deadline) { throw '延迟代理启动失败' }
            Start-Sleep -Milliseconds 200
        }
    }
    # 使用ADB隧道传输辅助代理，排除模拟器NAT的响应截断；业务目标URL仍保持原10.0.2.2:18080。
    & $adb -s $Device reverse tcp:18082 tcp:18082 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '辅助代理ADB隧道建立失败' }
    & $adb -s $Device shell settings put global http_proxy 127.0.0.1:18082 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '模拟器代理设置失败' }
    # 只安装本次构建APK；Maestro每阶段clearState确保新客户端消费代理配置，不录音或触发模型推理。
    & $adb -s $Device install -r $ApkPath *> (Join-Path $EvidenceDirectory 'install.log')
    if ($LASTEXITCODE -ne 0) { throw '最新APK安装失败' }
    $phases=if($Section -eq 'all') { @('filter','detail','chat') } else { @($Section) }
    foreach ($phase in $phases) {
        # 延迟在真实服务完成后注入；取消只停止客户端等待，不改变服务器已接受的事务。
        $rule=switch($phase) {
            'filter' { @{pathPrefix='/api/stores';method='GET';category='奶茶咖啡';seconds=15} }
            'detail' { @{pathPrefix='/api/deals/';method='GET';seconds=15} }
            'chat' { @{pathPrefix='/api/agent/sessions/';pathSuffix='/messages';method='POST';seconds=30} }
        }
        $body=@{phase=$phase;rule=$rule}|ConvertTo-Json -Depth 4 -Compress
        $null=Invoke-RestMethod -NoProxy -Method Post -ContentType 'application/json' -Body $body $proxyUrl
        $folder=Join-Path $EvidenceDirectory $phase;New-Item -ItemType Directory -Force $folder | Out-Null
        & $maestro --device $Device test --debug-output $folder --test-output-dir $folder -e "REQUEST_PHASE=$phase" (Join-Path $root 'maestro/advanced-15-request-cancellation.yaml') *> (Join-Path $folder 'raw.log')
        $phaseExit=$LASTEXITCODE
        # 统计等待真实下游断连，不能以屏幕离开推测Call已释放，也不能让零匹配规则冒称通过。
        $deadline=(Get-Date).AddSeconds(3)
        do {
            $stats=Invoke-RestMethod -NoProxy $proxyUrl
            $delayed=@($stats.requests | Where-Object delayed)
            if (@($delayed | Where-Object cancelled).Count -gt 0) { break }
            Start-Sleep -Milliseconds 100
        } while ((Get-Date) -lt $deadline)
        $stats | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 (Join-Path $folder 'safe-requests.json')
        $summary=@{phase=$phase;exitCode=$phaseExit;apkSha256=(Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash;delayed=$delayed.Count;cancelled=@($delayed | Where-Object cancelled).Count;delayedCompleted=@($delayed | Where-Object completed).Count}
        $summary | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $folder 'safe-result.json')
        Write-Output ($summary | ConvertTo-Json -Compress)
        if ($phaseExit -ne 0 -or $summary.delayed -ne 1 -or $summary.cancelled -ne 1 -or $summary.delayedCompleted -ne 0) { throw "普通HTTP取消验收失败：$phase" }
    }
    $afterFacts=Get-OrderFacts
    @{before=$beforeFacts;after=$afterFacts} | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $EvidenceDirectory 'safe-order-facts.json')
    if ($beforeFacts.sha256 -ne $afterFacts.sha256) { throw '普通请求取消验收改变交易事实' }
} finally {
    # 设备代理先恢复；只关闭本次Start-Process返回的PID，不停止其他Python服务或Docker容器。
    if ($originalProxy -in @('null','')) { & $adb -s $Device shell settings delete global http_proxy | Out-Null }
    else { & $adb -s $Device shell settings put global http_proxy $originalProxy | Out-Null }
    & $adb -s $Device reverse --remove tcp:18082 | Out-Null
    if ($proxyProcess -and !$proxyProcess.HasExited) { Stop-Process -Id $proxyProcess.Id }
    Remove-Item Env:MAESTRO_USERNAME,Env:MAESTRO_PASSWORD -ErrorAction SilentlyContinue
}

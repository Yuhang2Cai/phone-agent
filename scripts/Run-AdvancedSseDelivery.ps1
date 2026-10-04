# SSE设备验收运行器：只对既有18080/18091隔离服务注入暂停/停止，并在finally恢复原服务。
# 参数指定最新APK、已有私有夹具和忽略证据目录；账号仅通过子进程环境注入，不能写入公开报告。
param(
    [Parameter(Mandatory=$true)][string]$ApkPath,
    [Parameter(Mandatory=$true)][string]$FixturePath,
    [Parameter(Mandatory=$true)][string]$EvidenceDirectory,
    # 恢复段依赖上一段留下的真实错误页；默认all仍完整运行，不能把分段成功称整段已重复执行。
    [ValidateSet('all','recovery','confirmation')][string]$Section='all',
    [string]$FlowRoot='',
    [ValidatePattern('^emulator-\d+$')][string]$Device='emulator-5560'
)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
if (!$FlowRoot) { $FlowRoot=Join-Path $workspace 'maestro' }
$adb='C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$maestro='C:/Users/caid0/.local/maestro/bin/maestro.bat'
$agentContainer='fandazi-t12-agent-1'
$env:JAVA_HOME='C:/Users/caid0/.jdks/ms-17.0.17'
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'

# 输入校验：设备/APK/夹具和服务必须存在且运行；不读取容器环境变量或内部授权令牌。
if (!(Test-Path -LiteralPath $ApkPath) -or !(Test-Path -LiteralPath $FixturePath)) { throw '缺少已构建APK或私有隔离夹具' }
if (!(& $adb devices | Select-String "^$Device\s+device")) { throw '指定模拟器不可用' }
$initial=& docker inspect --format '{{.State.Running}} {{.State.Paused}}' $agentContainer
if ($LASTEXITCODE -ne 0 -or $initial.Trim() -ne 'true false') { throw '隔离Agent未运行或已暂停，不能改变未知状态' }
$null=Invoke-RestMethod -NoProxy 'http://127.0.0.1:18080/api/stores/cities'
$fixtures=Get-Content -LiteralPath $FixturePath -Raw | ConvertFrom-Json
# 资格规范化：历史场景名不等于当前资格，既有测试可能已消耗原券；只读后端本人订单的canRefund。
# 优先原退款场景，再检查其他已有合成账号；不创建订单、不恢复库存，也不执行退款。
$account=$null
foreach ($candidate in ($fixtures.scenarios | Sort-Object { if ($_.scenario -eq 'refundable') { 0 } else { 1 } })) {
    $loginBody=@{username=$candidate.username;password=$candidate.password} | ConvertTo-Json -Compress
    $login=Invoke-RestMethod -NoProxy -Method Post -ContentType 'application/json' -Body $loginBody 'http://127.0.0.1:18080/api/auth/login'
    $orders=Invoke-RestMethod -NoProxy -Headers @{Authorization="Bearer $($login.token)"} 'http://127.0.0.1:18080/api/group-orders'
    $eligible=@($orders | ForEach-Object { $_.vouchers } | Where-Object { $_.canRefund -eq $true })
    if ($eligible.Count -gt 0) { $account=$candidate; break }
}
# 结果构造只保留账号环境变量；不输出登录响应、JWT或订单正文，资格缺失明确阻塞确认段。
$login=$null; $loginBody=$null; $orders=$null
if (!$account) { throw '已有隔离账号均无后端允许退款的券，确认段无法验收' }
$env:MAESTRO_USERNAME=$account.username
$env:MAESTRO_PASSWORD=$account.password
New-Item -ItemType Directory -Force $EvidenceDirectory | Out-Null

function Invoke-SsePhase([string]$Phase) {
    # 阶段白名单避免未定义分支空运行被误判为通过；原始日志只写私有证据，输出阶段与退出码。
    if ($Phase -notin @('progress','terminal','error','retry','confirmation','logout')) { throw '未定义的SSE验收阶段' }
    $phaseFolder=Join-Path $EvidenceDirectory $Phase
    New-Item -ItemType Directory -Force $phaseFolder | Out-Null
    & $maestro --device $Device test --debug-output $phaseFolder --test-output-dir $phaseFolder -e "SSE_PHASE=$Phase" (Join-Path $FlowRoot 'advanced-15-sse-delivery.yaml') *> (Join-Path $phaseFolder 'raw.log')
    $phaseExit=$LASTEXITCODE
    [pscustomobject]@{phase=$Phase;exitCode=$phaseExit;apkSha256=(Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash} |
        ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $phaseFolder 'safe-result.json')
    Write-Output "SSE phase=$Phase exit=$phaseExit"
    if ($phaseExit -ne 0) { throw "SSE阶段验收失败：$Phase" }
}

function Wait-AgentReady {
    # 重启后只轮询公开健康接口，最多30秒；错误明确受阻，不用固定长睡眠掩盖服务未就绪。
    $deadline=(Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        try { $null=Invoke-RestMethod -NoProxy -TimeoutSec 2 'http://127.0.0.1:18091/internal/v1/health'; return } catch { Start-Sleep -Milliseconds 500 }
    }
    throw '隔离Agent健康入口未恢复'
}

try {
    if ($Section -eq 'all') {
    # 最新APK是唯一验收产物；只覆盖本项目应用，不清理模拟器其他应用或用户AVD。
    & $adb -s $Device install -r $ApkPath *> (Join-Path $EvidenceDirectory 'install.log')
    if ($LASTEXITCODE -ne 0) { throw '最新APK安装失败' }
    & docker pause $agentContainer | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '无法注入进度等待' }
    try { Invoke-SsePhase 'progress' } finally { & docker unpause $agentContainer | Out-Null }
    Invoke-SsePhase 'terminal'
    # 停止Agent使真实Spring网关返回服务错误；finally恢复后再通过原错误卡明确重试。
    & docker stop --time 5 $agentContainer | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '无法注入服务故障' }
    try { Invoke-SsePhase 'error' } finally { & docker start $agentContainer | Out-Null }
    }
    Wait-AgentReady
    if ($Section -ne 'confirmation') { Invoke-SsePhase 'retry' }
    Invoke-SsePhase 'confirmation'
    Invoke-SsePhase 'logout'
} finally {
    # 任一Maestro失败也恢复原来运行且未暂停的隔离Agent，不留下故障状态给下一步骤。
    $state=& docker inspect --format '{{.State.Running}} {{.State.Paused}}' $agentContainer
    if ($state -match 'true true') { & docker unpause $agentContainer | Out-Null }
    if ($state -match '^false') { & docker start $agentContainer | Out-Null }
    Remove-Item Env:MAESTRO_USERNAME,Env:MAESTRO_PASSWORD -ErrorAction SilentlyContinue
}

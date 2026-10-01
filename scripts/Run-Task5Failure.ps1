# Task5 真实失败恢复：停止唯一 8091 监听进程制造不可达/已建 NDJSON 中断，随后在原聊天页重试。
param([ValidateSet('unreachable','interrupted')][string]$Kind = 'unreachable', [string]$Suffix = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$adb = 'C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$query = if ($Kind -eq 'unreachable') { 'hello' } else { 'recommend group buying deals nearby' }
$expected = if ($Kind -eq 'unreachable') { '你好，我是饭搭子助手。' } else { '.*请提供目录中的城市代码.*' }
$result = [ordered]@{kind=$Kind;query=$query;apkSha256=(Get-FileHash -LiteralPath (Join-Path $root 'android-app/app/build/outputs/apk/debug/app-debug.apk')).Hash;startedAt=(Get-Date -Format o);stoppedPid=$null;errorExit=$null;retryExit=$null}
function Stop-ConfirmedAgent {
    # 明确核对端口拥有者，不停止 Spring、其他 Python 或模型外部依赖。
    $listener = Get-NetTCPConnection -State Listen -LocalPort 8091 | Select-Object -First 1
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    if ($process.CommandLine -notlike '*service.app:create_app*') { throw '8091 拥有者不是新 Agent' }
    $script:result.stoppedPid=$process.ProcessId
    Stop-Process -Id $process.ProcessId
}
function Capture-QuestionCount([string]$Phase) {
    # 原 UI 层级含本任务随机账号之外的公开聊天；只公开问题出现次数，不复制完整敏感调试记录。
    $remote='/sdcard/task5-hierarchy.xml'
    $local=Join-Path $root ".runtime/task5-$Kind-$Phase.xml"
    & $adb -s emulator-5554 shell uiautomator dump $remote | Out-Null
    & $adb -s emulator-5554 pull $remote $local 2>&1 | Out-Null
    [xml]$xml=Get-Content $local -Raw
    return @($xml.SelectNodes('//node') | Where-Object { $_.GetAttribute('resource-id') -eq 'com.fandazi.app:id/agent_message_text' -and $_.GetAttribute('text') -eq $query }).Count
}
try {
    if ($Kind -eq 'unreachable') {
        Stop-ConfirmedAgent
        & (Join-Path $PSScriptRoot 'Run-Task5Chat.ps1') -Flow 'task5-ai-error.yaml' -Label "$Kind$Suffix-error" -Query $query
        $result.errorExit=$LASTEXITCODE
    } else {
        # 已发送 200 响应头说明 NDJSON 连接已经建立；在下一次真实请求开始后停止进程，不能冒称初始拒绝连接。
        $agentLog=Join-Path $root '.runtime/task5-agent.out.log'
        $before=@(Get-Content $agentLog | Select-String 'POST /internal/v1/agent/runs HTTP/1.1.*200 OK').Count
        $arguments=@('-NoProfile','-File',('"'+(Join-Path $PSScriptRoot 'Run-Task5Chat.ps1')+'"'),'-Flow','task5-ai-error.yaml','-Label',"$Kind$Suffix-error",'-Query',('"'+$query+'"'))
        $runner=Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root '.runtime/task5-interrupted-runner.out.log') -RedirectStandardError (Join-Path $root '.runtime/task5-interrupted-runner.err.log')
        $connected=$false
        for($attempt=0;$attempt -lt 600;$attempt++) {
            if (@(Get-Content $agentLog | Select-String 'POST /internal/v1/agent/runs HTTP/1.1.*200 OK').Count -gt $before) { $connected=$true;break }
            if($runner.HasExited){break}
            Start-Sleep -Milliseconds 250
        }
        if(!$connected){throw '没有观察到已建真实 NDJSON 连接，不能宣称中断场景已跑'}
        $result.ndjsonConnected=$true
        Start-Sleep -Milliseconds 350
        Stop-ConfirmedAgent
        $runner.WaitForExit(120000) | Out-Null
        if(!$runner.HasExited){throw '中断 Maestro 超时'}
        $result.errorExit=$runner.ExitCode
    }
    $result.userBubbleBefore=Capture-QuestionCount 'before'
    & (Join-Path $PSScriptRoot 'Start-Task5Services.ps1') -Service Agent | Out-Null
    & (Join-Path $PSScriptRoot 'Run-Task5Chat.ps1') -Flow 'task5-ai-retry.yaml' -Label "$Kind$Suffix-recovered" -Query $query -Expected $expected
    $result.retryExit=$LASTEXITCODE
    $result.userBubbleAfter=Capture-QuestionCount 'after'
    if($result.errorExit -ne 0 -or $result.retryExit -ne 0 -or $result.userBubbleBefore -ne 1 -or $result.userBubbleAfter -ne 1) { throw '真实失败/恢复或问题去重未通过' }
} finally {
    # 不论测试成功与否恢复唯一新 Agent，释放故障状态给后续独立验收；不关闭共享业务库。
    if(!(Get-NetTCPConnection -State Listen -LocalPort 8091 -ErrorAction SilentlyContinue)) {
        & (Join-Path $PSScriptRoot 'Start-Task5Services.ps1') -Service Agent | Out-Null
    }
    $result.finishedAt=Get-Date -Format o
    # 复验后缀保留前次完整证据；不能用新版结果覆盖旧 APK、失败或中断记录。
    $result|ConvertTo-Json -Depth 5|Set-Content (Join-Path $root "docs/verification/task5-evidence/$Kind$Suffix-recovery.json") -Encoding utf8
    $result|ConvertTo-Json -Depth 5
}

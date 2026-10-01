# Task4 实际网络错误/原地重试自测：仅停确认入口的Spring，finally恢复同配置；不初始化fixture或重启新Agent。
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = 'C:/Users/caid0/.jdks/ms-17.0.17'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
$maestro = 'C:/Users/caid0/.local/maestro/bin/maestro.bat'
$adb = 'C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$destination = Join-Path $workspace 'docs/verification/task4-evidence/maestro-retry'
New-Item -ItemType Directory -Path $destination -Force | Out-Null

# 本人主浏览会话已结束；关闭App的闲置连接，再核对进程入口，禁止泛杀Java或操作业务数据。
& $adb -s emulator-5554 shell am force-stop com.fandazi.app
$listener = Get-NetTCPConnection -LocalPort 8080 -State Listen
$process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
if ($process.CommandLine -notlike '*com.fandazi.FandaziApplication*') { throw '8080并非确认的Spring入口' }
Stop-Process -Id $process.ProcessId
Write-Output "task4-network-test stoppedSpringPid=$($process.ProcessId)"
$errorCode = 1
try {
    & $maestro test --device emulator-5554 --test-output-dir $destination --debug-output $destination (Join-Path $workspace 'maestro/task4-browse-retry-error.yaml')
    $errorCode = $LASTEXITCODE
} finally {
    # 无论UI错误阶段成功与否，均先导入共享环境，再覆盖两个安全开关，恢复唯一Spring。
    . (Join-Path $workspace 'scripts/Initialize-FandaziEnvironment.ps1')
    $env:TASK2_FIXTURES_ENABLED = 'false'
    $env:TASK8_FAILURES_ENABLED = 'false'
    & (Join-Path $workspace 'start-fandazi-services.ps1')
}
if ($errorCode -ne 0) { exit $errorCode }

# 健康等待是运行器对本次必要重启的确认；不是UI中使用假数据、任意延迟或后台静默扩任务。
$healthy = $false
for ($attempt = 0; $attempt -lt 45; $attempt++) {
    try {
        $null = Invoke-RestMethod -NoProxy -TimeoutSec 2 'http://127.0.0.1:8080/api/stores/cities'
        $healthy = $true
        break
    } catch { Start-Sleep -Seconds 1 }
}
if (-not $healthy) { throw '受控重启后Spring健康检查未通过' }
# Spring旧启动脚本为后端指定JBR；Maestro继续明确使用已有完整JDK17，不修改公共启动脚本。
$env:JAVA_HOME = 'C:/Users/caid0/.jdks/ms-17.0.17'
& $maestro test --device emulator-5554 --test-output-dir $destination --debug-output $destination (Join-Path $workspace 'maestro/task4-browse-retry-recovered.yaml')
exit $LASTEXITCODE

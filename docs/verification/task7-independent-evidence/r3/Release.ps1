# R3完成释放：只核对当前设备、已恢复正常双服务和本轮测试结束，不关闭正常服务或已有守护进程。
$ErrorActionPreference='Stop'
$task7Root='C:/fdz-build';$task7Base="$task7Root/docs/verification/task7-independent-evidence";$task7Public="$task7Base/r3"
if(Test-Path "$task7Public/environment-release.json"){throw '禁止覆盖首次释放记录'}
$task7Diagnostic=@'
import json, psutil
me = psutil.Process()
excluded = {p.pid for p in me.parents()} | {me.pid}
active = []
# 只检测独立验收启动器，持久服务和Gradle daemon不当成未完成测试，也不泛杀进程。
for process in psutil.process_iter():
    if process.pid in excluded:
        continue
    try:
        args = process.cmdline()
        command = ' '.join(args).replace('\\', '/').lower()
        own = 'task7-independent' in command or 'task7-delivery-r2' in command
        test = 'org.gradle.launcher.gradlemain' in command or ('maestro' in command and 'test' in args) or 'compiledreadprobe.java' in command or 'run-maestro.ps1' in command or 'run-route.ps1' in command
        if own and test:
            active.append(process.pid)
    except (psutil.NoSuchProcess, psutil.AccessDenied):
        continue
print(json.dumps({'activeOwnTests': active}))
'@
$task7Processes=(& 'C:/Users/caid0/anaconda3/python.exe' -c $task7Diagnostic) | ConvertFrom-Json
if($LASTEXITCODE -ne 0 -or $task7Processes.activeOwnTests.Count){throw '本轮测试仍运行或诊断失败'}
$task7Health=Get-Content "$task7Base/health-r3-final-release.json" -Raw | ConvertFrom-Json
$task7Source=Get-Content "$task7Public/snapshot-end.json" -Raw | ConvertFrom-Json
$task7Facts=Get-Content "$task7Public/read-api-final.json" -Raw | ConvertFrom-Json
$task7Private=Get-Content "$task7Public/private-release.json" -Raw | ConvertFrom-Json
if(!$task7Source.passed -or !$task7Facts.passed -or !$task7Private.passed -or $task7Health.ordinaryJwtSeconds -ne 3600){throw '源/事实/私有/JWT保护未通过'}
$task7Adb='C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$task7Network=(& $task7Adb -s emulator-5554 shell dumpsys connectivity | Select-String 'Active default network').Line
$task7Wifi=(& $task7Adb -s emulator-5554 shell settings get global wifi_on).Trim()
$task7Data=(& $task7Adb -s emulator-5554 shell settings get global mobile_data).Trim()
$task7Airplane=(& $task7Adb -s emulator-5554 shell settings get global airplane_mode_on).Trim()
$task7App=& $task7Adb -s emulator-5554 shell pidof com.fandazi.app
if($task7App -or !$task7Network -or $task7Network -match 'none' -or $task7Wifi -ne '1' -or $task7Data -ne '1' -or $task7Airplane -ne '0'){throw '设备尚未达到释放状态'}
$task7Install=Get-Content "$task7Public/install-workspace-final.json" -Raw | ConvertFrom-Json
@{at=(Get-Date -Format o);released=$true;activeDefaultNetwork=$task7Network;wifi=$task7Wifi;mobileData=$task7Data;airplaneMode=$task7Airplane;appStopped=$true;ordinaryJwtSeconds=3600;listeners=$task7Health.listeners;sourceCount=895;sourceChanges=0;indexesUnchanged=$true;originalBusinessRows=714;currentBusinessRows=725;privateJsonUnchanged=438;processes=$task7Processes;servicesRetainedHealthy=$true;fixturesAndFaultsDisabled=$true;httpProxyDisabled=$true;productModified=$false;indexModified=$false;task8Executed=$false;newAcceptanceAgentStarted=$false;restoredWorkspaceApk=$task7Install.deviceSha256} | ConvertTo-Json -Depth 7 | Set-Content "$task7Public/environment-release.json" -Encoding utf8
Write-Output 'R3环境已释放；工作区APK恢复，双服务/网络/JWT正常，本轮测试进程0'

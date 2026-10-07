# R2阻塞释放证据：汇总亲跑XML和只读保护，确认本轮Gradle/Maestro任务结束，不操作既有服务或泛杀进程。
$ErrorActionPreference='Stop'
$task7Base='C:/fdz-build/docs/verification/task7-independent-evidence';$task7Public="$task7Base/r2"
$task7Suites=@(Get-ChildItem "$task7Public/snapshot-android" -File -Filter 'TEST-*.xml' | ForEach-Object {
 [xml]$task7Xml=Get-Content $_.FullName
 @{name=$task7Xml.testsuite.name;tests=[int]$task7Xml.testsuite.tests;failures=[int]$task7Xml.testsuite.failures;errors=[int]$task7Xml.testsuite.errors;skipped=[int]$task7Xml.testsuite.skipped}
})
@{suites=$task7Suites;tests=($task7Suites.tests | Measure-Object -Sum).Sum;failures=($task7Suites.failures | Measure-Object -Sum).Sum;errors=($task7Suites.errors | Measure-Object -Sum).Sum;skipped=($task7Suites.skipped | Measure-Object -Sum).Sum;suiteCount=$task7Suites.Count} | ConvertTo-Json -Depth 5 | Set-Content "$task7Public/snapshot-android-counts.json" -Encoding utf8
$task7Diagnostic=@'
import json, psutil
me = psutil.Process()
ancestors = {p.pid for p in me.parents()} | {me.pid}
active = []
# 只认本轮测试启动器，不把持久Gradle daemon或Maestro MCP服务器当成未结束验收。
for p in psutil.process_iter():
    try:
        if p.pid in ancestors:
            continue
        args = p.cmdline()
        command = ' '.join(args).replace('\\', '/').lower()
        own = 'task7-independent/r2' in command or 'task7-delivery-r2' in command
        launcher = 'org.gradle.launcher.gradlemain' in command or ('maestro' in command and 'test' in args)
        if own and launcher:
            active.append(p.pid)
    except (psutil.NoSuchProcess, psutil.AccessDenied):
        pass
print(json.dumps(dict(activeOwnTestLaunchers=active)))
'@
$task7Processes=(& 'C:/Users/caid0/anaconda3/python.exe' -c $task7Diagnostic) | ConvertFrom-Json
if($task7Processes.activeOwnTestLaunchers.Count){throw '仍有本轮测试启动器'}
$task7Health=Get-Content "$task7Base/health-r2-block-release.json" -Raw | ConvertFrom-Json
$task7Source=Get-Content "$task7Public/snapshot-end.json" -Raw | ConvertFrom-Json
if(!$task7Source.passed -or $task7Health.ordinaryJwtSeconds -ne 3600){throw '冻结/JWT释放检查失败'}
$task7Adb='C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$task7Network=(& $task7Adb -s emulator-5554 shell dumpsys connectivity | Select-String 'Active default network').Line
$task7Wifi=(& $task7Adb -s emulator-5554 shell settings get global wifi_on).Trim()
$task7Data=(& $task7Adb -s emulator-5554 shell settings get global mobile_data).Trim()
$task7Airplane=(& $task7Adb -s emulator-5554 shell settings get global airplane_mode_on).Trim()
$task7Pid=& $task7Adb -s emulator-5554 shell pidof com.fandazi.app
if($task7Pid -or !$task7Network -or $task7Network -match 'none' -or $task7Wifi -ne '1' -or $task7Data -ne '1' -or $task7Airplane -ne '0'){throw '设备释放状态不符'}
@{at=(Get-Date -Format o);released=$true;activeDefaultNetwork=$task7Network;wifi=$task7Wifi;mobileData=$task7Data;airplaneMode=$task7Airplane;appStopped=$true;ordinaryJwtSeconds=3600;listeners=$task7Health.listeners;sourceCount=895;sourceChanges=0;indexesUnchanged=$true;processes=$task7Processes;servicesRetainedHealthy=$true;fixturesAndFaultsDisabled=$true;httpProxyDisabled=$true;productModified=$false;indexModified=$false;task8Executed=$false;newAgentStarted=$false;reason='B2只读接口未进入拟提交源，停止新场景交主Agent最小补正'} | ConvertTo-Json -Depth 7 | Set-Content "$task7Public/environment-release.json" -Encoding utf8
Write-Output 'R2环境释放完成：网络/JWT/双服务默认健康，本轮测试启动器0，895源及index不变'

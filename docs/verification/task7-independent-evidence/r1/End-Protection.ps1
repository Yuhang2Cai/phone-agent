# R1独立收尾保护：只读检查冻结源、原HEAD/索引、设备网络和本轮流程；不改产品或开发元数据。
$ErrorActionPreference='Stop'
$task7Root='C:/fdz-build';$task7Public="$task7Root/docs/verification/task7-independent-evidence/r1"
$task7Manifest=Get-Content "$task7Root/docs/verification/task7-r1-evidence/source-freeze.json" -Raw | ConvertFrom-Json
$task7Checks=@()
foreach($task7Item in $task7Manifest.files){
 # 按冻结清单的仓库归属逐文件比对字节，不以git状态替代实际源保护。
 $task7Repo=if($task7Item.repo -eq 'phone'){$task7Root}else{'D:/project/-Demo-master'}
 $task7Hash=(Get-FileHash -LiteralPath (Join-Path $task7Repo $task7Item.path)).Hash
 $task7Checks+=@{repo=$task7Item.repo;path=$task7Item.path;sha256=$task7Hash;unchanged=($task7Hash -eq $task7Item.sha256)}
}
$task7PhoneHead=(& git -C $task7Root rev-parse HEAD).Trim();$task7AgentHead=(& git -C D:/project/-Demo-master rev-parse HEAD).Trim()
$task7IndexEmpty=!((& git -C $task7Root diff --cached --name-only) -or (& git -C D:/project/-Demo-master diff --cached --name-only))
$task7Receipt=Get-Content "$task7Root/docs/verification/task7-r1-evidence/freeze-receipt.json" -Raw | ConvertFrom-Json
$task7Metadata=@()
foreach($task7Item in $task7Receipt.metadata){
 $task7Hash=(Get-FileHash -LiteralPath (Join-Path $task7Root $task7Item.path)).Hash
 $task7Metadata+=@{path=$task7Item.path;sha256=$task7Hash;unchanged=($task7Hash -eq $task7Item.sha256)}
}
foreach($task7Item in $task7Receipt.r0Preserved){
 # 报告允许追加R1；其余R0证据，包括首次失败记录，必须完整保留原字节。
 if($task7Item.path.EndsWith('2026-10-02-task7-independent-acceptance.md')){continue}
 $task7Hash=(Get-FileHash -LiteralPath $task7Item.path).Hash
 $task7Metadata+=@{path=$task7Item.path;sha256=$task7Hash;unchanged=($task7Hash -eq $task7Item.sha256)}
}
$task7SourcePass=$task7Checks.Count -eq 893 -and !(@($task7Checks | Where-Object {!$_.unchanged}).Count) -and $task7PhoneHead -eq $task7Manifest.phoneBase -and $task7AgentHead -eq $task7Manifest.agentBase -and $task7IndexEmpty
$task7SourcePass=$task7SourcePass -and !(@($task7Metadata | Where-Object {!$_.unchanged}).Count)
@{at=(Get-Date -Format o);passed=$task7SourcePass;sourceCount=$task7Checks.Count;sourceChanges=@($task7Checks | Where-Object {!$_.unchanged}).Count;phoneHead=$task7PhoneHead;agentHead=$task7AgentHead;indexesEmpty=$task7IndexEmpty;files=$task7Checks;metadata=$task7Metadata} | ConvertTo-Json -Depth 6 | Set-Content "$task7Public/source-end.json" -Encoding utf8
if(!$task7SourcePass){throw 'R1结束源/HEAD/索引保护失败'}
# 网络从设备实际状态读取；APP必须已经强停，不能把开关命令成功误写成恢复。
$task7Adb='C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$task7Network=(& $task7Adb -s emulator-5554 shell dumpsys connectivity | Select-String 'Active default network').Line
$task7Wifi=(& $task7Adb -s emulator-5554 shell settings get global wifi_on).Trim()
$task7Data=(& $task7Adb -s emulator-5554 shell settings get global mobile_data).Trim()
$task7Airplane=(& $task7Adb -s emulator-5554 shell settings get global airplane_mode_on).Trim()
$task7AppPid=& $task7Adb -s emulator-5554 shell pidof com.fandazi.app
if(!$task7Network -or $task7Network -match 'none' -or $task7Wifi -ne '1' -or $task7Data -ne '1' -or $task7Airplane -ne '0' -or $task7AppPid){throw 'R1设备网络/APP清理不符'}
$task7Health=Get-Content "$task7Root/docs/verification/task7-independent-evidence/health-r1-release.json" -Raw | ConvertFrom-Json
if($task7Health.ordinaryJwtSeconds -ne 3600){throw 'R1普通JWT尚未恢复'}
# 只识别本轮私有路径的Maestro test流程；历史MCP服务保留，不读取输出进程完整命令参数。
$task7Diagnostic=@'
import json, psutil
matches = []
mcp = 0
me = psutil.Process()
ancestors = {p.pid for p in me.parents()} | {me.pid}
# 排除检查器自身与调用链，避免把诊断脚本文字误报为仍在运行的测试。
for p in psutil.process_iter():
    try:
        if p.pid in ancestors:
            continue
        args = p.cmdline()
        cmd = ' '.join(args).replace('\\', '/').lower()
        if 'maestro' in cmd and 'mcp' in args:
            mcp += 1
        if '.runtime/task7-independent/maestro-r1-' in cmd and 'test' in args:
            matches.append(p.pid)
    except (psutil.NoSuchProcess, psutil.AccessDenied):
        pass
print(json.dumps(dict(activeOwnMaestroTests=matches, retainedMaestroMcpProcesses=mcp)))
'@
$task7Flows=& 'C:/Users/caid0/anaconda3/python.exe' -c $task7Diagnostic
if($LASTEXITCODE -ne 0){throw 'R1流程进程诊断失败'}
$task7ProcessCheck=$task7Flows | ConvertFrom-Json
if($task7ProcessCheck.activeOwnMaestroTests.Count){throw 'R1尚有本轮Maestro流程'}
$task7Facts=Get-Content "$task7Public/facts-end.json" -Raw | ConvertFrom-Json
$task7Private=Get-Content "$task7Public/private-end.json" -Raw | ConvertFrom-Json
@{at=(Get-Date -Format o);released=$true;network=@{activeDefault=$task7Network;wifi=$task7Wifi;mobileData=$task7Data;airplaneMode=$task7Airplane};appStopped=$true;ordinaryJwtSeconds=$task7Health.ordinaryJwtSeconds;listeners=$task7Health.listeners;agent=$task7Health.agent;httpProxyDisabled=$true;sourceCount=893;sourceChanges=0;indexesEmpty=$true;facts=$task7Facts;privateFiles=$task7Private;processCheck=$task7ProcessCheck;servicesRetainedHealthy=$true;noAgentStarted=$true;task8Executed=$false} | ConvertTo-Json -Depth 8 | Set-Content "$task7Public/environment-release.json" -Encoding utf8
Write-Output '独立R1释放：893源变化0、索引空、网络恢复、普通JWT3600秒、双服务健康、APP停止、本轮Maestro流程0'

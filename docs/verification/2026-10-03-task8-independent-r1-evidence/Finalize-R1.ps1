<# R1收尾只读检查：核验索引、旧失败脚本/私有JSON、当前服务与设备网络。
不重启、不注入故障、不清理业务数据；结果仅保存非敏感摘要。
#>
$ErrorActionPreference='Stop'
$root='C:/fdz-build'
$private="$root/.runtime/task8-independent-final/r1"
$source=Get-Content "$PSScriptRoot/source-closure.json" -Raw|ConvertFrom-Json
foreach($repo in $source.repositories){
    if((& git -C $repo.repository write-tree).Trim() -ne $repo.tree -or (& git -C $repo.repository rev-parse HEAD).Trim() -ne $repo.head){throw 'R1结束版本变化'}
}
foreach($file in $source.tests){if((Get-FileHash (Join-Path $root $file.path) -Algorithm SHA256).Hash -ne $file.workingSha256){throw 'R1冻结测试文件变化'}}
$oldCopy="$root/docs/verification/2026-10-02-task8-independent-acceptance-evidence/test_live_write_boundaries_current.py"
if((Get-FileHash $oldCopy -Algorithm SHA256).Hash -ne $source.historicalFailedCopySha256){throw '旧失败脚本被修改'}
$old=Get-Content "$root/.runtime/task8-resumed/old-json-start.json" -Raw|ConvertFrom-Json
foreach($file in $old){if((Get-FileHash -LiteralPath $file.path -Algorithm SHA256).Hash -ne $file.sha256){throw '原775私有JSON改变'}}
$listeners=@()
foreach($port in @(8080,8091,8768)){
    $listener=Get-NetTCPConnection -State Listen -LocalPort $port|Select-Object -First 1
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    $expected=switch($port){8080{'com.fandazi.FandaziApplication'}8091{'service.app:create_app'}8768{'image_server.py'}}
    if($process.CommandLine -notlike "*$expected*"){throw '监听归属变化'}
    $listeners+=@{port=$port;pid=$listener.OwningProcess;sourceVerified=$true}
}
$null=Invoke-RestMethod -NoProxy http://127.0.0.1:8080/api/stores/cities
$agent=Invoke-RestMethod -NoProxy http://127.0.0.1:8091/internal/v1/health
if(Test-Path "$root/.runtime/task8-resumed/image-server/blocked"){throw '图片故障标记存在'}
$adb='C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$network=@{
    wifi=((& $adb -s emulator-5554 shell settings get global wifi_on)-join '').Trim()
    data=((& $adb -s emulator-5554 shell settings get global mobile_data)-join '').Trim()
    airplane=((& $adb -s emulator-5554 shell cmd connectivity airplane-mode)-join '').Trim()
    proxy=((& $adb -s emulator-5554 shell settings get global http_proxy)-join '').Trim()
}
$tests=@{}
foreach($name in @('guards','live')){
    [xml]$xml=Get-Content "$private/$name.xml" -Raw
    $tests[$name]=@{tests=@($xml.SelectNodes('//testcase')).Count;failures=@($xml.SelectNodes('//failure')).Count;errors=@($xml.SelectNodes('//error')).Count;skipped=@($xml.SelectNodes('//skipped')).Count}
}
$result=@{at=(Get-Date -Format o);tests=$tests;indexTreesUnchanged=$true;frozenTestsUnchanged=$true;historicalFailedCopyUnchanged=$true;oldPrivateJsonUnchanged=$old.Count;listeners=$listeners;springHealthy=$true;agentHealth=$agent;imageFaultAbsent=$true;network=$network;servicesRestartedByR1=$false;deviceAppModifiedByR1=$false}
if(Test-Path "$PSScriptRoot/final-checks.json"){throw '禁止覆盖收尾证据'}
$result|ConvertTo-Json -Depth 8|Set-Content "$PSScriptRoot/final-checks.json" -Encoding utf8
Write-Output 'R1收尾通过：冻结索引/测试、旧失败脚本、775私有文件均未变，服务健康，网络未改。'

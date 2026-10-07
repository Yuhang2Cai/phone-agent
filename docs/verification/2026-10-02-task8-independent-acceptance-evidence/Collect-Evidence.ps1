<# 独立验收收尾采集：读取已有测试结果、冻结摘要、旧私有文件哈希和环境状态。
不重跑已通过测试，不修改索引或业务记录；公开JSON仅输出计数、路径和非敏感状态。
#>
$ErrorActionPreference='Stop'
$root='C:/fdz-build'
$private="$root/.runtime/task8-independent-final"
$output="$PSScriptRoot/final-evidence.json"
if(Test-Path -LiteralPath $output){throw '禁止覆盖已生成的收尾证据'}

function Read-Junit([string]$Path){
    # 两种JUnit根结构统一遍历叶子用例，避免重复计算testsuites汇总属性。
    $files=if(Test-Path -LiteralPath $Path -PathType Container){@(Get-ChildItem -LiteralPath $Path -Recurse -Filter 'TEST-*.xml')}else{@(Get-Item -LiteralPath $Path)}
    $counts=@{files=$files.Count;tests=0;failures=0;errors=0;skipped=0}
    foreach($file in $files){
        [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw
        $counts.tests+=@($xml.SelectNodes('//testcase')).Count
        $counts.failures+=@($xml.SelectNodes('//testcase/failure')).Count
        $counts.errors+=@($xml.SelectNodes('//testcase/error')).Count
        $counts.skipped+=@($xml.SelectNodes('//testcase/skipped')).Count
    }
    $counts.passed=$counts.tests-$counts.failures-$counts.errors-$counts.skipped
    return $counts
}

$tests=[ordered]@{
    android=(Read-Junit "$private/phone/android-app/app/build/test-results/testDebugUnitTest")
    backend=(Read-Junit "$private/phone/backend/target/surefire-reports")
}
foreach($name in @('agent-offline','agent-mysql','live-boundaries-coupons','live-boundaries-r1','support-live','support-503','actual-ownership')){
    $tests[$name]=Read-Junit "$private/$name.xml"
}
# 首次失败与跳过保持原样，不用复验覆盖原始计数；具体原因由独立报告解释。
$maestro=@()
foreach($file in Get-ChildItem -LiteralPath $private -Recurse -Filter commands.json){
    $commands=Get-Content -LiteralPath $file.FullName -Raw|ConvertFrom-Json
    $states=@{}
    foreach($group in ($commands|Group-Object {$_.metadata.status})){$states[$group.Name]=$group.Count}
    $maestro+=@{path=$file.FullName;states=$states}
}
$screens=@(Get-ChildItem -LiteralPath $private -Recurse -Filter '*.png'|Where-Object {$_.FullName -match 'takeScreenshot|screenshots'}|ForEach-Object{
    @{path=$_.FullName;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
})

# 冻结校验只读工作文件、HEAD与索引树，不添加文件，不接受来源变更后的相近版本。
$freeze=Get-Content "$root/docs/verification/task8-resumed-evidence/freeze-preacceptance.json" -Raw|ConvertFrom-Json
$repositories=@()
foreach($repository in $freeze.repositories){
    $head=(& git -C $repository.repository rev-parse HEAD).Trim()
    $tree=(& git -C $repository.repository write-tree).Trim()
    if($head -ne $repository.head -or $tree -ne $repository.indexTree){throw '冻结HEAD或索引发生变化'}
    foreach($file in $repository.files){
        if((Get-FileHash -LiteralPath (Join-Path $repository.repository $file.path) -Algorithm SHA256).Hash -ne $file.workingSha256){throw "冻结文件发生变化：$($file.path)"}
    }
    $repositories+=@{repository=$repository.repository;head=$head;indexTree=$tree;frozenFilesUnchanged=$repository.files.Count}
}
$old=Get-Content "$root/.runtime/task8-resumed/old-json-start.json" -Raw|ConvertFrom-Json
foreach($file in $old){if((Get-FileHash -LiteralPath $file.path -Algorithm SHA256).Hash -ne $file.sha256){throw '旧私有JSON改变'}}

# 释放状态以真实监听与请求结果为准，不记录进程参数中的凭据或完整业务响应。
$listeners=@()
foreach($port in @(8080,8091,8768)){
    $listener=Get-NetTCPConnection -State Listen -LocalPort $port|Select-Object -First 1
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    $expected=switch($port){8080{'com.fandazi.FandaziApplication'}8091{'service.app:create_app'}8768{'image_server.py'}}
    if($process.CommandLine -notlike "*$expected*"){throw '服务监听归属变化'}
    $listeners+=@{port=$port;pid=$listener.OwningProcess;sourceVerified=$true}
}
$null=Invoke-RestMethod -NoProxy http://127.0.0.1:8080/api/stores/cities
$agent=Invoke-RestMethod -NoProxy http://127.0.0.1:8091/internal/v1/health
$image=Invoke-WebRequest -NoProxy http://127.0.0.1:8768/meal.jpg
if(Test-Path "$root/.runtime/task8-resumed/image-server/blocked"){throw '图片故障未恢复'}
$adb='C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$network=[ordered]@{
    wifi=((& $adb -s emulator-5554 shell settings get global wifi_on)-join '').Trim()
    data=((& $adb -s emulator-5554 shell settings get global mobile_data)-join '').Trim()
    airplane=((& $adb -s emulator-5554 shell cmd connectivity airplane-mode)-join '').Trim()
    proxy=((& $adb -s emulator-5554 shell settings get global http_proxy)-join '').Trim()
}
$connectivity=(& $adb -s emulator-5554 shell dumpsys connectivity)-join "`n"
$network.connected=$connectivity -match 'Active default network:\s*\d+'
$connectivity|Set-Content "$private/network-final.txt" -Encoding utf8
$result=[ordered]@{collectedAt=(Get-Date -Format o);tests=$tests;maestro=$maestro;screenshots=$screens;repositories=$repositories;oldPrivateJsonUnchanged=$old.Count;apkSha256=(Get-FileHash "$private/phone/android-app/app/build/outputs/apk/debug/app-debug.apk" -Algorithm SHA256).Hash;listeners=$listeners;springHealthy=$true;agentHealth=$agent;imageStatus=$image.StatusCode;imageFaultRestored=$true;network=$network}
$result|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $output -Encoding utf8
Write-Output '测试计数、冻结保护和环境状态已采集；原始内容仍留私有目录。'

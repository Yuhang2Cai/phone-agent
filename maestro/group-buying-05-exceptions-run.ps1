# Task8异常运行器：同一隔离账号真实503与模拟器断网；不修改服务源，不输出凭据，最后恢复原网络设置。
param([ValidateSet('server','network')][string]$Mode, [string]$Scenario='pending_payment',
      [string]$FixtureManifest='.runtime/task2-fixtures.json', [string]$RunName, [string]$ApkPath)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$fixture=Get-Content (Join-Path $root $FixtureManifest) -Raw|ConvertFrom-Json
$account=$fixture.scenarios|Where-Object scenario -eq $Scenario|Select-Object -First 1
if(!$account){throw '缺少指定真实账号'}
$env:MAESTRO_USERNAME=$account.username
$env:MAESTRO_PASSWORD=$account.password
$adb='C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$maestro='C:/Users/caid0/.local/maestro/bin/maestro.bat'
$evidence=Join-Path $root ".runtime/task8/$(if($RunName){$RunName}else{"8.6-$Mode"})"
if(Test-Path -LiteralPath $evidence){throw '不能覆盖既有异常验证证据'}
New-Item -ItemType Directory -Force -Path $evidence|Out-Null
# 此复合流程仅在开始卸载安装一次；后续登录→注入/断网→原地重试必须保留同一会话。
& $adb -s emulator-5554 uninstall com.fandazi.app *> (Join-Path $evidence 'uninstall.log')
& $adb -s emulator-5554 uninstall com.dirror.music *> (Join-Path $evidence 'music-uninstall.log')
$present=& $adb -s emulator-5554 shell pm list packages com.fandazi.app
if($present -match 'package:com.fandazi.app'){throw '旧主包未卸载，停止新流程'}
# 验收可指定精确索引产物，原地重试的各阶段仍共用一次安装和同一账号会话。
$apk=if($ApkPath){$ApkPath}else{Join-Path $root 'android-app/app/build/outputs/apk/debug/app-debug.apk'}
& $adb -s emulator-5554 install $apk *> (Join-Path $evidence 'install.log')
if($LASTEXITCODE -ne 0){throw '最新APK安装失败'}
$wifi=(& $adb -s emulator-5554 shell settings get global wifi_on).Trim()
$data=(& $adb -s emulator-5554 shell settings get global mobile_data).Trim()
$airplane=(& $adb -s emulator-5554 shell cmd connectivity airplane-mode).Trim()

# 等待实际默认网络消失或恢复，不能仅以开关设置成功声称真实断网；完整系统证据仅留私有目录。
function Wait-Network([bool]$Connected,[string]$Label){
    for($attempt=0;$attempt -lt 30;$attempt++){
        $dump=(& $adb -s emulator-5554 shell dumpsys connectivity) -join "`n"
        $offline=$dump -match 'Active default network:\s*none'
        if(($Connected -and !$offline -and $dump -match 'Active default network:\s*\d+') -or (!$Connected -and $offline)){
            $dump | Set-Content (Join-Path $evidence "network-$Label.txt") -Encoding utf8
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "实际网络状态未达到要求：$Label"
}

# 每个阶段输出独立退出码；原始命令/登录日志仅写忽略目录，不向控制台暴露。
function Invoke-Stage([string]$flow,[string]$stage){
    $directory=Join-Path $evidence $stage
    New-Item -ItemType Directory -Force -Path $directory|Out-Null
    & $maestro --device emulator-5554 test --debug-output $directory --test-output-dir $directory (Join-Path $root "maestro/$flow") *> (Join-Path $directory 'raw-maestro.log')
    $code=$LASTEXITCODE
    [pscustomobject]@{mode=$Mode;stage=$stage;exitCode=$code;evidence=$directory}|ConvertTo-Json -Compress
    if($code -ne 0){throw "阶段失败：$stage"}
}

Push-Location $evidence
try{
    Invoke-Stage 'group-buying-05-login.yaml' 'login'
    if($Mode -eq 'server'){
        # 只认证和注入下一次读取失败；不抢先GET订单，确保真实503由UI消费。
        $auth=Invoke-RestMethod -NoProxy -Uri http://127.0.0.1:8080/api/auth/login -Method Post -ContentType application/json -Body (@{username=$account.username;password=$account.password}|ConvertTo-Json)
        Invoke-RestMethod -NoProxy -Uri http://127.0.0.1:8080/api/development/order-failure -Method Post -Headers @{Authorization="Bearer $($auth.token)"}|Out-Null
        Invoke-Stage 'group-buying-05-orders-error.yaml' 'error-and-retry'
    }else{
        # 仅关闭真实网络；避免飞行模式广播触发此前记录的模拟器蓝牙崩溃，与业务无关。
        & $adb -s emulator-5554 shell svc wifi disable|Out-Null
        & $adb -s emulator-5554 shell svc data disable|Out-Null
        Wait-Network $false 'off'
        Invoke-Stage 'group-buying-05-orders-network.yaml' 'network-off'
        if($wifi -eq '1'){& $adb -s emulator-5554 shell svc wifi enable|Out-Null}
        if($data -eq '1'){& $adb -s emulator-5554 shell svc data enable|Out-Null}
        Wait-Network $true 'restored'
        Invoke-Stage 'group-buying-05-orders-network-recover.yaml' 'network-recover'
    }
}finally{
    # 无论Maestro成功、断言失败或进程异常，都恢复原始开关，不影响控制端下一轮资源。
    $nowAirplane=(& $adb -s emulator-5554 shell cmd connectivity airplane-mode).Trim()
    if($nowAirplane -ne $airplane){& $adb -s emulator-5554 shell cmd connectivity airplane-mode $(if($airplane -eq 'enabled'){'enable'}else{'disable'})|Out-Null}
    & $adb -s emulator-5554 shell svc wifi $(if($wifi -eq '1'){'enable'}else{'disable'})|Out-Null
    & $adb -s emulator-5554 shell svc data $(if($data -eq '1'){'enable'}else{'disable'})|Out-Null
    Pop-Location
    Remove-Item Env:MAESTRO_USERNAME,Env:MAESTRO_PASSWORD -ErrorAction SilentlyContinue
}

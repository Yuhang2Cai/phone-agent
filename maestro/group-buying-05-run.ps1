# Task8 开发流程运行器：只读开发夹具凭据，原始 Maestro 输出置于忽略目录，控制台仅显示退出码和证据路径。
param(
    [Parameter(Mandatory=$true)][string]$Scenario,
    [Parameter(Mandatory=$true)][string]$Flow,
    [string]$RunName = 'task8',
    [int]$VoucherIndex = 0,
    [string]$FixtureManifest = '.runtime/task2-fixtures.json',
    [switch]$KeepInstalledState,
    [string]$ApkPath
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
# 独立复验清单使用相同结构但不同文件；默认仍读旧七夹具，绝不覆盖或打印清单正文。
$fixture = Get-Content -LiteralPath (Join-Path $taskRoot $FixtureManifest) -Raw | ConvertFrom-Json
$account = if ($fixture.scenarios) {
    $fixture.scenarios | Where-Object scenario -eq $Scenario | Select-Object -First 1
} elseif ($Scenario -eq 'coupon_owner') {
    # 控制端提供的八券清单是单对象；只取owner登录，不领取或修改记录，不将observer混入正向查询。
    $fixture.owner
} elseif ($Scenario -eq 'coupon_observer') {
    $fixture.observer
} else { $null }
if ($null -eq $account) { throw '指定夹具场景不存在' }
# 凭据只进入子进程环境；不把密码放入 CLI 参数、Git 文件或可见报告。
$env:MAESTRO_USERNAME = $account.username
$env:MAESTRO_PASSWORD = $account.password
$env:MAESTRO_VOUCHER_INDEX = $VoucherIndex.ToString()
$evidence = Join-Path $taskRoot ".runtime/task8/$RunName"
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
# 独立用户流程开始按AGENTS卸载并安装最新APK；仅当前回执尾段等同一流程续段允许保留安装/会话状态。
if (!$KeepInstalledState) {
    $adb='C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe'
    & $adb -s emulator-5554 uninstall com.fandazi.app *> (Join-Path $evidence 'uninstall.log')
    & $adb -s emulator-5554 uninstall com.dirror.music *> (Join-Path $evidence 'music-uninstall.log')
    $present=& $adb -s emulator-5554 shell pm list packages com.fandazi.app
    if($present -match 'package:com.fandazi.app'){throw '旧主包未卸载，停止新流程'}
    # 源码闭合验收可明确安装索引快照 APK，不能用混合工作区构建替代拟提交产物。
    $apk=if($ApkPath){$ApkPath}else{Join-Path $taskRoot 'android-app/app/build/outputs/apk/debug/app-debug.apk'}
    & $adb -s emulator-5554 install $apk *> (Join-Path $evidence 'install.log')
    if($LASTEXITCODE -ne 0){throw '本轮APK安装失败'}
}
$env:MAESTRO_CITY_CODE = $null
if ($Flow -like '*agent-composite.yaml') {
    # 当前城市仅取真实目录返回值，未取得时阻止流程，不写死demo/sh或自行生成城市。
    $stores=Invoke-RestMethod -NoProxy -Uri http://127.0.0.1:8080/api/stores
    $city=@($stores)[0].cityCode
    if([string]::IsNullOrWhiteSpace($city)){throw '真实门店目录未取得cityCode'}
    $env:MAESTRO_CITY_CODE=$city
}
Push-Location $evidence
try {
    & 'C:/Users/caid0/.local/maestro/bin/maestro.bat' --device emulator-5554 test --debug-output $evidence --test-output-dir $evidence (Join-Path $taskRoot $Flow) *> (Join-Path $evidence 'raw-maestro.log')
    $resultCode = $LASTEXITCODE
    [pscustomobject]@{scenario=$Scenario;flow=$Flow;exitCode=$resultCode;evidence=$evidence} | ConvertTo-Json -Compress
    exit $resultCode
} finally {
    Pop-Location
    Remove-Item Env:MAESTRO_USERNAME,Env:MAESTRO_PASSWORD,Env:MAESTRO_VOUCHER_INDEX,Env:MAESTRO_CITY_CODE -ErrorAction SilentlyContinue
}

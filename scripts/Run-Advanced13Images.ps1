# Task13 图片页面验收运行器：身份来自当前公开目录与私有隔离夹具，不在 YAML 写死账号或 UUID。
param(
    [ValidateSet('all','browse','protected','cards')][string]$Section='all',
    [string]$EvidenceDirectory=''
)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
$api='http://127.0.0.1:18080/api'
$adb='C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$maestro='C:/Users/caid0/.local/maestro/bin/maestro.bat'
$env:JAVA_HOME='C:/Users/caid0/.jdks/ms-17.0.17'
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'

# 输入校验：设备和后端必须属于固定隔离环境，禁止在未知服务上运行交易相关页面。
if (-not (& $adb devices | Select-String '^emulator-5554\s+device')) { throw '固定模拟器不可用' }
$stores=Invoke-RestMethod -NoProxy "$api/stores"
$store=$stores | Where-Object { $_.category -eq '盖饭' -and $_.name -notlike '*集成验收*' } | Select-Object -First 1
if (!$store) { throw '没有可验收的真实盖饭门店' }
$deals=Invoke-RestMethod -NoProxy "$api/stores/$($store.publicId)/deals"
$drinks=Invoke-RestMethod -NoProxy "$api/stores?category=$([uri]::EscapeDataString('奶茶咖啡'))"
$hotpots=Invoke-RestMethod -NoProxy "$api/stores?category=$([uri]::EscapeDataString('火锅'))"
if ($deals.Count -lt 2 -or $drinks.Count -lt 1 -or $hotpots.Count -lt 1) { throw '当前公开目录不足以覆盖两个套餐及筛选状态' }
$fixture=Get-Content (Join-Path $workspace '.runtime/task2-fixtures.json') -Raw | ConvertFrom-Json
$account=$fixture.scenarios | Where-Object scenario -eq 'refund_requested' | Select-Object -First 1
if (!$account) { throw '隔离只读退款夹具不存在' }

# 账号只交给子进程环境；公开业务身份通过 Maestro 的 -e 变量传入，不打印私有凭据。
$env:MAESTRO_USERNAME=$account.username
$env:MAESTRO_PASSWORD=$account.password
$env:MAESTRO_CITY_CODE=$store.cityCode
$out=if ($EvidenceDirectory) { $EvidenceDirectory } else { Join-Path $workspace ".runtime/advanced/task13-maestro/$Section" }
New-Item -ItemType Directory -Path $out -Force | Out-Null
$flow=switch($Section){
    'browse' { 'group-buying-01-browse.yaml' }
    'protected' { 'advanced-13-protected-images.yaml' }
    'cards' { 'group-buying-12-chat-cards.yaml' }
    default { 'advanced-13-images.yaml' }
}
$arguments=@('test','--device','emulator-5554','--debug-output',$out,'--test-output-dir',$out,
    '-e',"CITY_CODE=$($store.cityCode)",'-e',"STORE_ID=$($store.publicId)",'-e',"STORE_NAME=$($store.name)",
    '-e',"DEAL_A=$($deals[0].publicId)",'-e',"DEAL_B=$($deals[1].publicId)",
    '-e',"PRICE_A=$($deals[0].priceCents)",'-e',"PRICE_B=$($deals[1].priceCents)",
    '-e',"DRINK_ID=$($drinks[0].publicId)",'-e',"HOTPOT_ID=$($hotpots[0].publicId)",
    '-e',"MAESTRO_CITY_CODE=$($store.cityCode)",(Join-Path $workspace "maestro/$flow"))
try {
    & $maestro @arguments *> (Join-Path $out 'raw.log')
    $code=$LASTEXITCODE
    # 结果只记录流程、公开 ID、APK 摘要和退出码，私有账号不进入证据。
    $apk=Join-Path $workspace 'android-app/app/build/outputs/apk/debug/app-debug.apk'
    [pscustomobject]@{section=$Section;exitCode=$code;storePublicId=$store.publicId;
        apkSha256=(Get-FileHash $apk -Algorithm SHA256).Hash;evidence=$out} |
        ConvertTo-Json | Set-Content (Join-Path $out 'result.json') -Encoding UTF8
    Write-Output "Task13 $Section Maestro exit=$code evidence=$out"
    exit $code
} finally {
    Remove-Item Env:MAESTRO_USERNAME,Env:MAESTRO_PASSWORD,Env:MAESTRO_CITY_CODE -ErrorAction SilentlyContinue
}

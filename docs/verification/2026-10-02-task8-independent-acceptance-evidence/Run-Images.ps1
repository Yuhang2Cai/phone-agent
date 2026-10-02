<# 独立图片验收：复用冻结快照运行器和独占图片账号，验证真实503、原URL恢复及空图刷新。
依赖本地8080/8768、Maestro和最新快照APK；原始日志只写.runtime，公开结果不含登录信息。
#>
$ErrorActionPreference='Stop'
$root='C:/fdz-build'
$private="$root/.runtime/task8-independent-final"
$snapshot="$private/phone"
$server="$root/.runtime/task8-resumed/image-server"
$marker=Join-Path $server 'blocked'
$runner="$snapshot/maestro/group-buying-05-run.ps1"
$apk="$snapshot/android-app/app/build/outputs/apk/debug/app-debug.apk"
$manifest='../../task8-resumed/fixtures-images.json'
if(Test-Path -LiteralPath $marker){throw '初始图片服务不是正常状态'}
if(Test-Path "$private/images-start.json"){throw '禁止覆盖首次图片验收证据'}
$beforeHash=(Get-FileHash "$server/meal.jpg" -Algorithm SHA256).Hash
$startLines=@(Get-Content "$server/requests.jsonl").Count
@{sha256=$beforeHash;requestLines=$startLines;marker=$false}|ConvertTo-Json|Set-Content "$private/images-start.json" -Encoding utf8
# 输入只来自真实接口；先验证资格与受控URL，避免误用旧业务或硬编码图片说明。
$fixture=Get-Content "$root/.runtime/task8-resumed/fixtures-images.json" -Raw|ConvertFrom-Json
$account=$fixture.scenarios|Where-Object scenario -eq reviewable
$auth=Invoke-RestMethod -NoProxy http://127.0.0.1:8080/api/auth/login -Method Post -ContentType application/json -Body (@{username=$account.username;password=$account.password}|ConvertTo-Json)
$orders=Invoke-RestMethod -NoProxy http://127.0.0.1:8080/api/group-orders -Headers @{Authorization="Bearer $($auth.token)"}
if(@($orders).Count -ne 1 -or !$orders[0].canReview -or $orders[0].imageUrl -ne 'http://10.0.2.2:8768/meal.jpg'){throw '图片夹具资格或URL不匹配'}
$env:MAESTRO_IMAGE_ALT=$orders[0].imageAlt
if([string]::IsNullOrWhiteSpace($env:MAESTRO_IMAGE_ALT)){throw '真实图片说明缺失'}
$results=@()
try{
    # 相同URL通过标记返回真实503；此阶段只输入评价，不发布任何业务写入。
    New-Item -ItemType File -Path $marker|Out-Null
    & $runner -Scenario reviewable -Flow maestro/group-buying-05-image-retry.yaml -FixtureManifest $manifest -RunName independent-final-image-fail-r0 -ApkPath $apk
    $results+=@{stage='same-url-503';exitCode=$LASTEXITCODE}
    if($LASTEXITCODE -ne 0){throw '图片失败阶段未通过'}
    Remove-Item -LiteralPath $marker
    # 连续恢复段保留同一安装、登录、评分与输入，仅恢复原URL原字节。
    & $runner -Scenario reviewable -Flow maestro/group-buying-05-image-retry-recover.yaml -FixtureManifest $manifest -RunName independent-final-image-recover-r0 -ApkPath $apk -KeepInstalledState
    $results+=@{stage='same-url-200';exitCode=$LASTEXITCODE}
    if($LASTEXITCODE -ne 0){throw '图片恢复阶段未通过'}
    & $runner -Scenario refundable -Flow maestro/group-buying-05-image-null.yaml -FixtureManifest $manifest -RunName independent-final-image-null-r0 -ApkPath $apk
    $results+=@{stage='null-refresh';exitCode=$LASTEXITCODE}
    if($LASTEXITCODE -ne 0){throw '空图刷新阶段未通过'}
}finally{
    # 无论测试断言或工具失败，都只删除自己的单一故障标记并保留完整原始请求证据。
    if(Test-Path -LiteralPath $marker){Remove-Item -LiteralPath $marker}
    Remove-Item Env:MAESTRO_IMAGE_ALT -ErrorAction SilentlyContinue
    $requests=@(Get-Content "$server/requests.jsonl"|Select-Object -Skip $startLines|ForEach-Object{$_|ConvertFrom-Json})
    $requests|ConvertTo-Json -Depth 5|Set-Content "$private/images-requests.json" -Encoding utf8
    @{stages=$results;originalBytesUnchanged=((Get-FileHash "$server/meal.jpg" -Algorithm SHA256).Hash -eq $beforeHash);markerRestored=!(Test-Path -LiteralPath $marker);requests=$requests}|ConvertTo-Json -Depth 6|Set-Content "$PSScriptRoot/images-result.json" -Encoding utf8
}

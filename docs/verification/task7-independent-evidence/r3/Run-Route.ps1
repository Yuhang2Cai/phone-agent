# R3额外路由UI运行器：使用同一Task本人账号与既有券，原命令树含密码只保留私有目录，公开仅截图和状态。
$ErrorActionPreference='Stop'
$task7Root='C:/fdz-build';$task7Base="$task7Root/docs/verification/task7-independent-evidence";$task7Private="$task7Root/.runtime/task7-independent"
$task7Label='r3-unregistered';$task7Raw="$task7Private/maestro-$task7Label";$task7Output="$task7Base/maestro-$task7Label"
if(Test-Path "$task7Output/result.json"){throw '禁止覆盖首次结果'}
New-Item -ItemType Directory -Force $task7Raw,$task7Output | Out-Null
$task7Account=Get-Content "$task7Private/account.json" -Raw | ConvertFrom-Json
$task7Orders=Get-Content "$task7Private/orders.json" -Raw | ConvertFrom-Json
if(!$task7Account.username.StartsWith('task7_')){throw '不允许原Task账号'}
$task7Install=Get-Content "$task7Base/r3/install-projection.json" -Raw | ConvertFrom-Json
$env:JAVA_HOME='C:/Users/caid0/.jdks/ms-17.0.17'
$env:HTTP_PROXY='';$env:HTTPS_PROXY='';$env:ALL_PROXY='';$env:NO_PROXY='*'
$task7Args=@('--device','emulator-5554','test','--test-output-dir',$task7Raw,'--debug-output',$task7Raw,
 '-e',"MAESTRO_USERNAME=$($task7Account.username)",'-e',"MAESTRO_PASSWORD=$($task7Account.password)",
 '-e',"TASK7_ORDER_A=$($task7Orders[0].orderId)",'-e',"TASK7_VOUCHER_A=$($task7Orders[0].vouchers[0].publicId)",
 "$task7Base/r3/unregistered-route.yaml")
$task7Started=Get-Date -Format o
& 'C:/Users/caid0/.local/maestro/bin/maestro.bat' @task7Args *> "$task7Private/maestro-$task7Label.log"
$task7Exit=$LASTEXITCODE
# 复制只含UI的截图，不导出Maestro环境变量和完整命令树。
Get-ChildItem $task7Raw -Recurse -File -Filter '*.png' | Where-Object {$_.FullName -match 'takeScreenshot'} | ForEach-Object {
 $task7Destination=Join-Path $task7Output ([IO.Path]::GetRelativePath($task7Raw,$_.FullName))
 New-Item -ItemType Directory -Force (Split-Path $task7Destination) | Out-Null
 Copy-Item -LiteralPath $_.FullName -Destination $task7Destination
}
@{exitCode=$task7Exit;flow='r3/unregistered-route.yaml';startedAt=$task7Started;finishedAt=(Get-Date -Format o);apkSha256=$task7Install.deviceSha256;accountRole='account'} | ConvertTo-Json | Set-Content "$task7Output/result.json" -Encoding utf8
Write-Output "R3未注册UI exit=$task7Exit"
exit $task7Exit

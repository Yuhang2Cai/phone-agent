# Task7 交接门禁：核对冻结源码未漂移、原私有清单/业务保护结果、APK一致及公开文本未泄露真实密钥。
$ErrorActionPreference='Stop'
$root='C:/fdz-build'
$public="$root/docs/verification/task7-evidence"
$manifest=Get-Content "$public/source-freeze.json" -Raw | ConvertFrom-Json
foreach($file in $manifest.files) {
 $repo=if($file.repo -eq 'phone') {$root} else {'D:/project/-Demo-master'}
 $path=Join-Path $repo $file.path
 if(!(Test-Path -LiteralPath $path) -or (Get-FileHash -LiteralPath $path).Hash -ne $file.sha256) {throw "冻结后源变化：$($file.repo):$($file.path)"}
}
if((& git -C $root rev-parse HEAD) -ne $manifest.phoneBase -or (& git -C 'D:/project/-Demo-master' rev-parse HEAD) -ne $manifest.agentBase) {throw '冻结后HEAD变化'}
if((& git -C $root diff --cached --name-only) -or (& git -C 'D:/project/-Demo-master' diff --cached --name-only)) {throw '索引不为空，必须核对外部暂存来源'}
# 读取本任务及服务授权秘密只作内存精确比对，不输出值，不读取Task6或Task8账号口令。
$secrets=@()
foreach($role in @('account','outsider')) {$value=Get-Content "$root/.runtime/task7/$role.json" -Raw | ConvertFrom-Json;$secrets+=[string]$value.password}
$service=Get-Content "$root/.runtime/service-secrets.json" -Raw | ConvertFrom-Json
foreach($property in $service.PSObject.Properties) {if($property.Name -match '(secret|token|key|password)' -and $property.Value -is [string]) {$secrets+=$property.Value}}
$scanned=0
foreach($file in (Get-ChildItem -LiteralPath $public -Recurse -File | Where-Object {$_.Extension -in @('.json','.xml','.txt','.md','.patch','.ps1','.py')})) {
 $text=Get-Content -LiteralPath $file.FullName -Raw
 foreach($secret in $secrets) {if($secret.Length -gt 8 -and $text.Contains($secret)) {throw "公开文件含真实秘密：$($file.Name)"}}
 if($text -match 'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}') {throw "公开文件含JWT：$($file.Name)"}
 $scanned++
}
$installed=Get-Content "$public/apk-installed.json" -Raw | ConvertFrom-Json
$apk=(Get-FileHash "$root/android-app/app/build/outputs/apk/debug/app-debug.apk").Hash
if($apk -ne $installed.apkSha256 -or $installed.apkSha256 -ne $installed.deviceSha256) {throw 'APK证据版本不同'}
$health=Get-Content "$public/health-release.json" -Raw | ConvertFrom-Json
if($health.ordinaryJwtSeconds -ne 3600 -or $health.activeDefaultNetwork -match 'none') {throw '环境未恢复'}
$facts=Get-Content "$public/original-facts-after.json" -Raw | ConvertFrom-Json
foreach($property in $facts.PSObject.Properties) {if($property.Value.before -ne $property.Value.after) {throw '原业务事实变化'}}
$before=Get-Content "$public/protected-start.json" -Raw | ConvertFrom-Json
$after=Get-Content "$public/protected-after.json" -Raw | ConvertFrom-Json
if(($before | ConvertTo-Json -Depth 5 -Compress) -ne ($after | ConvertTo-Json -Depth 5 -Compress)) {throw '原私有清单变化'}
@{verifiedAt=(Get-Date -Format o);frozenSources=$manifest.files.Count;publicTextFilesScanned=$scanned;knownSecretsMatches=0;protectedPrivateFiles=$before.Count;originalBusinessTables=@($facts.PSObject.Properties).Count;apkSha256=$apk;independentAcceptance='待控制端派发';stageOrCommit=$false} | ConvertTo-Json -Depth 4 | Set-Content "$public/handoff-gate.json" -Encoding utf8
Write-Output "Task7 freeze/evidence gate PASS sources=$($manifest.files.Count) public=$scanned protected=$($before.Count); independent pending"

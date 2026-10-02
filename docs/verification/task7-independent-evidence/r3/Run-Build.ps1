# R3剩余独立构建：隔离Maven/Agent原样测试，工作区仅重建APK；不重复R2同源69项Android回归。
param([ValidateSet('snapshot-backend','snapshot-agent','snapshot-agent-retry','workspace-apk')][string]$Phase)
$ErrorActionPreference='Stop'
$task7Root='C:/fdz-build';$task7Public="$task7Root/docs/verification/task7-independent-evidence/r3";$task7Private="$task7Root/.runtime/task7-independent/r3"
New-Item -ItemType Directory -Force $task7Public,$task7Private | Out-Null
if(Test-Path "$task7Public/$Phase-result.json"){throw '禁止覆盖首次运行结果'}
. "$task7Root/scripts/Initialize-FandaziEnvironment.ps1"
$env:JAVA_HOME='C:/Users/caid0/.jdks/ms-17.0.17';$env:PYTHONIOENCODING='utf-8'
$env:HTTP_PROXY='';$env:HTTPS_PROXY='';$env:ALL_PROXY='';$env:NO_PROXY='*'
$env:TASK2_FIXTURES_ENABLED='false';$env:TASK8_FAILURES_ENABLED='false'
$task7Started=Get-Date -Format o;$task7Log="$task7Private/$Phase.log";$task7Xml=@()
switch($Phase){
 'snapshot-backend' {
  # 适用七套加原已有进度恢复回归，均在注解隔离H2；clean不沿用开发的target结果。
  $task7Cwd="$task7Root/.runtime/task7-delivery-r2/phone/backend"
  $task7Exe='D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd'
  $task7Args=@('-q','-Dtest=GroupOrderSummaryContractTest,VoucherLifecycleTest,PurchaseConfirmationTest,GroupBuyOrderIdempotencyTest,AgentCardEnrichmentTest,InternalV1ToolIntegrationTest,AgentToolFieldBoundaryTest,RefundEligibilityTest','clean','test')
  $task7Xml=@("$task7Cwd/target/surefire-reports/TEST-*.xml")
 }
 {$_ -in @('snapshot-agent','snapshot-agent-retry')} {
  foreach($task7Name in @('DB_URL','DB_USERNAME','DB_PASSWORD')){Remove-Item "Env:$task7Name" -ErrorAction SilentlyContinue}
  $env:PYTHONPATH='';$task7Cwd=if($Phase -eq 'snapshot-agent-retry'){"$task7Private/agent-source/-Demo-master"}else{"$task7Root/.runtime/task7-delivery-r2/agent"}
  $task7Exe='C:/Users/caid0/anaconda3/envs/pinnT/python.exe'
  $task7Junit=if($Phase -eq 'snapshot-agent-retry'){"$task7Public/agent-tests-retry.xml"}else{"$task7Public/agent-tests.xml"}
  $task7Args=@('-m','pytest','tests/test_agent_service_reads.py','tests/test_agent_service_api.py','tests/test_agent_service_dag.py','tests/test_dag_models.py','tests/test_group_buying_intent_migration.py','-q','-p','no:cacheprovider',"--basetemp=$task7Private/pytest-$Phase", "--junitxml=$task7Junit")
 }
 'workspace-apk' {
  $task7Cwd="$task7Root/android-app";$task7Exe="$task7Cwd/gradlew.bat"
  $task7Args=@(':app:assembleDebug','--rerun-tasks','--console=plain')
 }
}
Push-Location $task7Cwd
try { & $task7Exe @task7Args *> $task7Log; $task7Exit=$LASTEXITCODE } finally { Pop-Location }
$task7Results="$task7Public/$Phase";New-Item -ItemType Directory -Force $task7Results | Out-Null
foreach($task7Glob in $task7Xml){if(Test-Path $task7Glob){Copy-Item $task7Glob $task7Results}}
$task7Apk="$task7Cwd/app/build/outputs/apk/debug/app-debug.apk"
$task7Sha=if(Test-Path $task7Apk){(Get-FileHash $task7Apk).Hash}else{$null}
@{phase=$Phase;cwd=$task7Cwd;executable=$task7Exe;arguments=$task7Args;startedAt=$task7Started;finishedAt=(Get-Date -Format o);exitCode=$task7Exit;apkSha256=$task7Sha} | ConvertTo-Json -Depth 5 | Set-Content "$task7Public/$Phase-result.json" -Encoding utf8
Get-Content $task7Log -Tail 12
exit $task7Exit

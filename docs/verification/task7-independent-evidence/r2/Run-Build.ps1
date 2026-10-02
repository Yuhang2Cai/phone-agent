# R2独立构建运行器：隔离源先clean后强制重跑；原始日志留私有，首次失败不覆盖。
param([ValidateSet('snapshot-android','snapshot-backend','snapshot-agent','workspace-android')][string]$Phase)
$ErrorActionPreference='Stop'
$task7Root='C:/fdz-build';$task7Public="$task7Root/docs/verification/task7-independent-evidence/r2";$task7Private="$task7Root/.runtime/task7-independent/r2"
New-Item -ItemType Directory -Force $task7Public,$task7Private | Out-Null
if(Test-Path "$task7Public/$Phase-result.json"){throw '禁止覆盖首次构建结果'}
. "$task7Root/scripts/Initialize-FandaziEnvironment.ps1"
$env:JAVA_HOME='C:/Users/caid0/.jdks/ms-17.0.17';$env:PYTHONIOENCODING='utf-8'
$env:HTTP_PROXY='';$env:HTTPS_PROXY='';$env:ALL_PROXY='';$env:NO_PROXY='*'
$env:TASK2_FIXTURES_ENABLED='false';$env:TASK8_FAILURES_ENABLED='false'
$task7Started=Get-Date -Format o
$task7Log="$task7Private/$Phase.log"
$task7Xml=@();$task7Command=@();$task7Cwd='';$task7Exit=1
switch($Phase){
 'snapshot-backend' {
  # 适用原七套保持断言不变，不运行会写Task2共享清单的其它套；clean清除开发旧target。
  $task7Cwd="$task7Root/.runtime/task7-delivery-r2/phone/backend"
  $task7Exe='D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd'
  $task7Command=@('-q','-Dtest=GroupOrderSummaryContractTest,VoucherLifecycleTest,PurchaseConfirmationTest,GroupBuyOrderIdempotencyTest,AgentCardEnrichmentTest,InternalV1ToolIntegrationTest,AgentToolFieldBoundaryTest','clean','test')
  $task7Xml=@("$task7Cwd/target/surefire-reports/TEST-*.xml")
 }
 'snapshot-agent' {
  # Agent库环境不继承Spring DB配置，五文件按index实际集计数，绝不补入未来Task8测试凑95。
  foreach($task7Name in @('DB_URL','DB_USERNAME','DB_PASSWORD')){Remove-Item "Env:$task7Name" -ErrorAction SilentlyContinue}
  $env:PYTHONPATH='';$task7Cwd="$task7Root/.runtime/task7-delivery-r2/agent"
  $task7Exe='C:/Users/caid0/anaconda3/envs/pinnT/python.exe'
  $task7Command=@('-m','pytest','tests/test_agent_service_reads.py','tests/test_agent_service_api.py','tests/test_agent_service_dag.py','tests/test_dag_models.py','tests/test_group_buying_intent_migration.py','-q','-p','no:cacheprovider',"--basetemp=$task7Private/pytest-snapshot", "--junitxml=$task7Public/agent-tests.xml")
 }
 default {
  $task7Cwd=if($Phase -eq 'snapshot-android'){"$task7Root/.runtime/task7-delivery-r2/phone/android-app"}else{"$task7Root/android-app"}
  $task7Exe="$task7Cwd/gradlew.bat"
  # 券、可选导航、聊天与购买覆盖共同依赖；clean和rerun防止沿用开发自测XML和APK。
  $task7Command=@('clean',':app:assembleDebug',':app:testDebugUnitTest','--rerun-tasks','--tests','com.fandazi.app.feature.voucher.VoucherRepositoryTest','--tests','com.fandazi.app.core.navigation.RegisteredBusinessRouteTest','--tests','com.fandazi.app.feature.agent.*','--tests','com.fandazi.app.feature.purchase.*','--console=plain')
  $task7Xml=@("$task7Cwd/app/build/test-results/testDebugUnitTest/TEST-*.xml")
 }
}
Push-Location $task7Cwd
try { & $task7Exe @task7Command *> $task7Log; $task7Exit=$LASTEXITCODE } finally { Pop-Location }
$task7Results="$task7Public/$Phase"
New-Item -ItemType Directory -Force $task7Results | Out-Null
foreach($task7Glob in $task7Xml){if(Test-Path $task7Glob){Copy-Item $task7Glob $task7Results}}
$task7Apk="$task7Cwd/app/build/outputs/apk/debug/app-debug.apk"
$task7Sha=if(Test-Path $task7Apk){(Get-FileHash $task7Apk).Hash}else{$null}
@{phase=$Phase;cwd=$task7Cwd;executable=$task7Exe;arguments=$task7Command;startedAt=$task7Started;finishedAt=(Get-Date -Format o);exitCode=$task7Exit;apkSha256=$task7Sha} | ConvertTo-Json -Depth 5 | Set-Content "$task7Public/$Phase-result.json" -Encoding utf8
Get-Content $task7Log -Tail 18
exit $task7Exit

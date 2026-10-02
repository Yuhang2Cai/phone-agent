# Task7 独立验收命令运行器：准备批准环境，固定工具链，将原始日志留在忽略目录。
param([ValidateSet('setup','reads','agent','identity','protect','release','backend','android','python')][string]$Phase)
$ErrorActionPreference='Stop'
$task7Root='C:/fdz-build'
$task7Public="$task7Root/docs/verification/task7-independent-evidence"
$task7Private="$task7Root/.runtime/task7-independent"
. "$task7Root/scripts/Initialize-FandaziEnvironment.ps1"
# 所有请求显式禁代理和故障夹具，运行期密钥只从忽略目录和容器环境导入。
$env:HTTP_PROXY=''; $env:HTTPS_PROXY=''; $env:ALL_PROXY=''; $env:NO_PROXY='*'
$env:TASK2_FIXTURES_ENABLED='false'; $env:TASK8_FAILURES_ENABLED='false'
$env:JAVA_HOME='C:/Users/caid0/.jdks/ms-17.0.17'
$task7Started=Get-Date -Format o
$task7Log="$task7Private/$Phase.log"
if(Test-Path "$task7Public/$Phase-result.json"){throw '该阶段已有结果，禁止覆盖首次失败'}
switch($Phase){
 'backend' {
  # 定向测试不运行会写共享清单的 Task2DevelopmentFixtureTest；测试断言保持冻结原样。
  Push-Location "$task7Root/backend"
  try { & 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' '-q' '-Dtest=GroupOrderSummaryContractTest,VoucherLifecycleTest,PurchaseConfirmationTest,GroupBuyOrderIdempotencyTest,AgentCardEnrichmentTest,InternalV1ToolIntegrationTest,AgentToolFieldBoundaryTest' 'test' *> $task7Log; $task7Exit=$LASTEXITCODE } finally { Pop-Location }
  New-Item -ItemType Directory -Force "$task7Public/backend" | Out-Null
  Copy-Item "$task7Root/backend/target/surefire-reports/TEST-*.xml" "$task7Public/backend"
 }
 'android' {
  # 强制实际重新执行本轮适用测试，避免把旧 XML 或 UP-TO-DATE 当成新验收。
  Push-Location "$task7Root/android-app"
  try { & './gradlew.bat' ':app:assembleDebug' ':app:testDebugUnitTest' '--rerun-tasks' '--tests' 'com.fandazi.app.feature.voucher.VoucherRepositoryTest' '--tests' 'com.fandazi.app.feature.agent.*' '--tests' 'com.fandazi.app.feature.purchase.*' *> $task7Log; $task7Exit=$LASTEXITCODE } finally { Pop-Location }
  New-Item -ItemType Directory -Force "$task7Public/android" | Out-Null
  Copy-Item "$task7Root/android-app/app/build/test-results/testDebugUnitTest/TEST-*.xml" "$task7Public/android"
 }
 'python' {
  # 新 Agent 使用独立库配置；禁止继承 Spring DB_URL 将业务库误当 Agent 库。
  foreach($task7Name in @('DB_URL','DB_USERNAME','DB_PASSWORD')){Remove-Item "Env:$task7Name" -ErrorAction SilentlyContinue}
  Push-Location 'D:/project/-Demo-master'
  try { & 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' '-m' 'pytest' 'tests/test_agent_service_reads.py' 'tests/test_agent_service_api.py' 'tests/test_agent_service_dag.py' 'tests/test_dag_models.py' 'tests/test_group_buying_intent_migration.py' '-q' '-p' 'no:cacheprovider' "--junitxml=$task7Public/agent-tests.xml" *> $task7Log; $task7Exit=$LASTEXITCODE } finally { Pop-Location }
 }
 default { & 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' "$task7Public/Verify-Independent.py" $Phase *> $task7Log; $task7Exit=$LASTEXITCODE }
}
# 公开仅记录命令阶段、退出码和时间；原始日志须脱敏后才可作为公开附件。
@{phase=$Phase;startedAt=$task7Started;finishedAt=(Get-Date -Format o);exitCode=$task7Exit} | ConvertTo-Json | Set-Content "$task7Public/$Phase-result.json" -Encoding utf8
Get-Content $task7Log -Tail 15
exit $task7Exit

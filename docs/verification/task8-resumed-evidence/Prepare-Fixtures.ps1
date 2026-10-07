<# 创建一组全新的 Task8 开发夹具；只取测试报告中的依赖 JAR，禁止带入 test-classes 的 H2 配置。 #>
param([Parameter(Mandatory=$true)][string]$Name)
$ErrorActionPreference = 'Stop'
if ($Name -notmatch '^[a-z0-9-]+$') { throw '夹具名只允许小写字母、数字和连字符' }
. 'C:/fdz-build/scripts/Initialize-FandaziEnvironment.ps1'
$private = 'C:/fdz-build/.runtime/task8-resumed'
$output = "$private/fixtures-$Name.json"
if (Test-Path -LiteralPath $output) { throw '不能覆盖已有夹具' }
# 已有 Maven 报告仅提供本机依赖路径；主资源固定置前，不能把测试数据库配置带进实际运行。
[xml]$report = Get-Content -LiteralPath 'C:/fdz-build/backend/target/surefire-reports/TEST-com.fandazi.core.money.CentsDecimalConverterTest.xml'
$jars = (($report.testsuite.properties.property | Where-Object name -eq 'java.class.path').value -split ';') | Where-Object { $_ -like '*.jar' }
$cp = (@('C:/fdz-build/backend/target/classes') + $jars) -join ';'
& 'C:/Users/caid0/.jdks/ms-17.0.17/bin/java.exe' -cp $cp "$PSScriptRoot/PrepareFixtures.java" $output *> "$private/prepare-$Name.log"
if ($LASTEXITCODE -ne 0) { throw '夹具创建失败，详情保留于私有日志；不自动重试写入' }
Write-Output "新夹具已创建：$Name；凭据不输出。"

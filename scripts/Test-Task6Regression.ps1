# Task6 Spring 收尾回归：完整源码字节复制到无链接隔离快照，历史 H2 测试只能写快照 .runtime。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$snapshot = Join-Path $root ('.runtime/task6-regression-' + [guid]::NewGuid().ToString('N'))
$evidence = Join-Path $root 'docs/verification/task6-evidence'
$privateBefore = @(Get-ChildItem (Join-Path $root '.runtime') -File -Filter '*.json' | Where-Object { $_.Name -match 'fixture|service-secrets' } | ForEach-Object {
    [pscustomobject]@{path=$_.FullName;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}
})
New-Item -ItemType Directory -Force -Path "$snapshot/backend","$snapshot/data" | Out-Null
# 只复制编译输入，先拒绝任何重解析点；不复制原运行目录、target 或既有真实清单。
# 历史 SpringBootToolHttpContractTest 只导入旧客户端库并连接随机端口 H2 Spring，不启动旧 Agent 服务。
New-Item -ItemType Directory -Force -Path "$snapshot/agent-service" | Out-Null
foreach ($relative in @('backend/src','backend/pom.xml','contracts','data/seed','agent-service/src')) {
    $inputPath = Join-Path $root $relative
    $item = Get-Item -LiteralPath $inputPath -Force
    $items = @($item)
    if ($item.PSIsContainer) { $items += @(Get-ChildItem -LiteralPath $inputPath -Recurse -Force) }
    if (@($items | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) { throw '输入包含链接，拒绝复制' }
    $target = Join-Path $snapshot $relative
    Copy-Item -LiteralPath $inputPath -Destination $target -Recurse
    foreach ($file in @($items | Where-Object { !$_.PSIsContainer })) {
        $copied = Join-Path $snapshot ([IO.Path]::GetRelativePath($root, $file.FullName))
        if ((Get-FileHash -LiteralPath $file.FullName).Hash -ne (Get-FileHash -LiteralPath $copied).Hash) { throw '同源快照字节不一致' }
    }
}
$output = [IO.Path]::GetFullPath((Join-Path "$snapshot/backend" '../.runtime/task2-test-fixtures.json'))
if (!$output.StartsWith([IO.Path]::GetFullPath($snapshot) + [IO.Path]::DirectorySeparatorChar)) { throw '测试输出越过快照边界' }
$env:JAVA_HOME = 'C:/Users/caid0/.jdks/ms-17.0.17'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
$env:PYTHON_EXECUTABLE = 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe'
$env:TASK2_FIXTURES_ENABLED = 'false'
$env:TASK8_FAILURES_ENABLED = 'false'
# H2 测试身份来自 test/resources，不能用运行期内部令牌覆盖测试默认凭据。
Remove-Item Env:AGENT_INTERNAL_TOKEN -ErrorAction SilentlyContinue
Push-Location "$snapshot/backend"
try {
    & 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' -q test *> "$snapshot/maven.log"
    $code = $LASTEXITCODE
} finally { Pop-Location }
$rows = @(Get-ChildItem "$snapshot/backend/target/surefire-reports" -Filter 'TEST-*.xml' | ForEach-Object {
    [xml]$xml = Get-Content -LiteralPath $_.FullName -Raw
    [pscustomobject]@{suite=$xml.testsuite.name;tests=[int]$xml.testsuite.tests;failures=[int]$xml.testsuite.failures;errors=[int]$xml.testsuite.errors;skipped=[int]$xml.testsuite.skipped}
})
$protected = @($privateBefore | ForEach-Object { [pscustomobject]@{path=$_.path;before=$_.sha256;after=(Get-FileHash -LiteralPath $_.path).Hash} })
$result = [pscustomobject]@{snapshot=$snapshot;exitCode=$code;sourceCopyVerified=$true;testOutput=$output;suites=$rows;protectedFiles=$protected;finishedAt=(Get-Date -Format o)}
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidence 'backend-final-regression.json') -Encoding utf8
$rows | Format-Table
if (@($protected | Where-Object { $_.before -ne $_.after }).Count) { throw '原夹具或密钥文件被改变' }
exit $code

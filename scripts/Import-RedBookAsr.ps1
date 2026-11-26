# redBook离线资源受控迁移：核对固定字节摘要，再复制到本手机仓；不下载、不替换模型、不移除ABI。
# 模型再分发许可尚待核实，IncludeLocalModel只用于用户授权的本机开发构建，不是发布授权。
param(
    [string]$SourceRoot = 'D:\project\redBook',
    [switch]$IncludeLocalModel
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'android-app\app\build.gradle.kts'))) {
    throw '迁移脚本必须放在手机项目scripts目录'
}
# 路径与摘要来自规格5.1，保持原双层资产目录，不能按同名的其他Paraformer文件替换。
$modelBase = 'app\src\main\assets\sherpa-onnx\sherpa-onnx-paraformer-zh-small-2024-03-09\sherpa-onnx-paraformer-zh-small-2024-03-09'
$resources = @(
    @{ Path='app\libs\sherpa-onnx-1.12.39.aar'; SHA256='D228A731EC3125170C962E2D52AD3FE012F3616E5047A87E2743D851D69C176D'; Model=$false },
    @{ Path="$modelBase\model.int8.onnx"; SHA256='3EF6C19369B912F7CAF3CEF8E545C5CCD1A33D9D7EC792A46668DC41C4B229EC'; Model=$true },
    @{ Path="$modelBase\tokens.txt"; SHA256='4B2D964E18B9CF139B473003B6698FB2ED9A2A5EC55B93DAA677B28F578897AA'; Model=$true }
)
# 先核对全部需要迁移的输入，再写输出；摘要异常不会先复制一半模型。
$selected = @($resources | Where-Object { -not $_.Model -or $IncludeLocalModel })
foreach ($resource in $selected) {
    $source = Join-Path $SourceRoot $resource.Path
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $resource.SHA256) { throw "固定ASR资源摘要不符：$($resource.Path)" }
}
foreach ($resource in $selected) {
    $source = Join-Path $SourceRoot $resource.Path
    $target = Join-Path (Join-Path $projectRoot 'android-app') $resource.Path
    # 只写三个明确目标，保留已有目录其他文件；复制后再次逐项核对。
    New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $target -Force
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $resource.SHA256) { throw "ASR目标资源摘要不符：$($resource.Path)" }
    [pscustomobject]@{ Resource=$resource.Path; SHA256=$resource.SHA256; Bytes=(Get-Item -LiteralPath $target).Length }
}

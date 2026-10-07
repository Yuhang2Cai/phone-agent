<# 正式本地启动入口：只复用已验证的新团购双服务启动器，不启动旧Agent或重导入业务数据。 #>
param([ValidateSet('Spring','Agent')][string]$Service='Spring')
$ErrorActionPreference='Stop'
# 既有启动器核对端口进程归属、私有环境、真实健康及服务来源；不接受旧服务降级。
& (Join-Path $PSScriptRoot 'Start-Task8Services.ps1') -Service $Service
if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw '新服务启动失败，请查看私有日志' }

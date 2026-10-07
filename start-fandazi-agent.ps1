# 新 Agent 唯一启动入口。保留旧 agent-service 源码，但启动失败不能退回旧服务。
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\Initialize-FandaziEnvironment.ps1')
$workdir = if ($env:FANDAZI_NEW_AGENT_ROOT) { $env:FANDAZI_NEW_AGENT_ROOT } else { 'D:\project\-Demo-master' }
$python = if ($env:FANDAZI_AGENT_PYTHON) { $env:FANDAZI_AGENT_PYTHON } else { 'C:\Users\caid0\anaconda3\envs\pinnT\python.exe' }
if (-not (Test-Path -LiteralPath (Join-Path $workdir 'service\app.py'))) { throw '新 Agent 入口不存在' }
if (Get-NetTCPConnection -LocalPort $agentPort -State Listen -ErrorAction SilentlyContinue) { throw "Agent 端口 $agentPort 已占用，请核对来源后再启动" }
$env:AGENT_BUILD_VERSION = (& git -C $workdir rev-parse --short HEAD).Trim()
if (& git -C $workdir status --porcelain) { $env:AGENT_BUILD_VERSION += '-working-tree' }
# 重定向日志使用 UTF-8，确保中文层级、失败原因与业务步骤可直接查看。
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
# 单进程保存同一任务的异步互斥锁；不要使用多 worker 绕过确认串行约束。
$process = Start-Process -FilePath $python -ArgumentList '-m','uvicorn','service.app:create_app','--factory','--host','127.0.0.1','--port',$agentPort -WorkingDirectory $workdir -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDirectory 'agent.out.log') -RedirectStandardError (Join-Path $runtimeDirectory 'agent.err.log')
Write-Output "new-agent-start-requested pid=$($process.Id) port=$agentPort source=$workdir"

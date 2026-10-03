<# 恢复本地既有图片端点；只提供已保存的真实图片字节，不改后端套餐URL和原事实。 #>
param([string]$ImagePath, [int]$Port=8768)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
if(!$ImagePath){$ImagePath=Join-Path $root '.runtime/task8-resumed/image-server/meal.jpg'}
if(!(Test-Path -LiteralPath $ImagePath)){throw '真实图片文件缺失，请提供原后端对应资产，不生成替代业务图片'}
$listener=Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |Select-Object -First 1
if($listener){throw '图片端口已占用，请先核对归属；本脚本不停止其他服务'}
$env:FANDAZI_IMAGE_PATH=(Resolve-Path -LiteralPath $ImagePath).Path
$env:FANDAZI_IMAGE_PORT=$Port.ToString()
$log=Join-Path $root ('.runtime/images-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
# 只启动本仓只读图片服务，文件路径经环境传递，避免拼接shell命令。
$p=Start-Process 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -ArgumentList @((Join-Path $PSScriptRoot 'image_server.py')) -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput "$log.out.log" -RedirectStandardError "$log.err.log"
for($i=0;$i -lt 20;$i++){
 try{$response=Invoke-WebRequest -NoProxy -Uri "http://127.0.0.1:$Port/meal.jpg" -TimeoutSec 2
  if($response.StatusCode -eq 200 -and $response.Headers['Content-Type'] -match 'image/jpeg'){Write-Output "图片端点就绪 PID=$($p.Id)";exit 0}
 }catch{Start-Sleep -Seconds 1}
}
throw '图片端点未就绪，请检查私有日志'

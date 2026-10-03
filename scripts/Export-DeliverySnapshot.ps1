# 导出暂存区的拟交付源码：不包含未暂存文件，不修改共享工作区，也不执行安装钩子。
param([Parameter(Mandatory)][string]$GitRoot,[Parameter(Mandatory)][string]$Destination)
$ErrorActionPreference='Stop'
$target=[IO.Path]::GetFullPath($Destination)
# 快照必须使用全新忽略目录；保留已有证据，不覆盖、不递归删除。
if($target -notmatch '[\\/]\.runtime[\\/]' -or (Test-Path -LiteralPath $target)){throw '快照必须是新的.runtime子目录'}
$parent=Split-Path -Parent $target
New-Item -ItemType Directory -Path $parent -Force|Out-Null
Push-Location $GitRoot
try{
 $tree=git write-tree
 if($LASTEXITCODE -ne 0){throw '无法读取拟提交树'}
 $zip=$target+'.zip'
 git archive --format=zip -o $zip $tree
 if($LASTEXITCODE -ne 0){throw '源码归档失败'}
 Expand-Archive -LiteralPath $zip -DestinationPath $target
 Write-Output "sourceTree=$tree snapshot=$target"
}finally{Pop-Location}

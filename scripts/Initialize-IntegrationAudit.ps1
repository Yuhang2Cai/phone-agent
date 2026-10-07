# 已有集成卷的幂等审计升级：只在明确的Compose项目执行既有001/002，不清空卷或改生产库。
param([string]$Project='fandazi-t12')
$ErrorActionPreference='Stop'
if($Project -notmatch '^fandazi-t12(?:-[a-z0-9-]+)?$'){throw '只能操作本任务独立集成项目'}
# 口令只在MySQL容器内部环境展开；禁止在PowerShell命令、公共日志中拼接口令。
docker compose -p $Project -f (Join-Path (Split-Path -Parent $PSScriptRoot) 'docker-compose.integration.yml') exec -T agent-db sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < /docker-entrypoint-initdb.d/001-intent.sql && mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < /docker-entrypoint-initdb.d/002-intent.sql'
if($LASTEXITCODE -ne 0){throw '幂等审计升级失败；保留卷并检查私有日志'}

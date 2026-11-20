param([string]$Database='community_elder_service',[string]$MySqlUser='root',[string]$MySqlHost='127.0.0.1',[int]$MySqlPort=3306,[string]$MySqlExe='D:\Desktop\install\bin\mysql.exe')
$ErrorActionPreference='Stop'
if($Database -notmatch '^[A-Za-z0-9_]+$'){throw '数据库名仅允许英文、数字及下划线'}
if(-not(Test-Path -LiteralPath $MySqlExe)){throw "找不到MySQL客户端：$MySqlExe"}
[Console]::OutputEncoding=New-Object System.Text.UTF8Encoding($false)
$OutputEncoding=[Console]::OutputEncoding
Write-Host "升级已有数据库 $Database（$MySqlHost`:$MySqlPort），不重建业务库、不修改密码。"
Write-Host '执行前请停止后端并完成数据库备份。按提示输入现有MySQL密码。'
$taskMigration=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'migrations/V002__multi_community.sql') -Raw -Encoding UTF8
$taskSql="USE ``$Database``;`n"+$taskMigration+"`nSELECT version,applied_at FROM app_schema_version WHERE version=2;"
$taskSql | & $MySqlExe --no-defaults --protocol=TCP "--host=$MySqlHost" "--port=$MySqlPort" "--user=$MySqlUser" --password --default-character-set=utf8mb4 --connect-timeout=10
if($LASTEXITCODE -ne 0){throw '升级未完成。请保留SQL错误信息，修复后可重新执行本增量脚本；不要重导初始化库。'}
Write-Host '多社区权限升级完成。请重新构建并启动1.2.0后端。' -ForegroundColor Green

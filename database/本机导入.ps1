param(
    [string]$MySqlUser = 'root',
    [string]$MySqlHost = '127.0.0.1',
    [int]$MySqlPort = 3306,
    [string]$MySqlExe = 'D:\Desktop\install\bin\mysql.exe'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$OutputEncoding = [Console]::OutputEncoding
$taskStatusPath = Join-Path $PSScriptRoot 'local-import-status.json'
$taskSqlPath = Join-Path $PSScriptRoot '导入数据库.sql'
$taskVerifyPath = Join-Path $PSScriptRoot 'verify_database.sql'

function Save-ImportStatus([string]$State, [string]$Detail) {
    @{ status = $State; detail = $Detail; updated_at = [DateTime]::UtcNow.ToString('o'); host = $MySqlHost; port = $MySqlPort; database = 'community_elder_service' } |
        ConvertTo-Json | Set-Content -LiteralPath $taskStatusPath -Encoding UTF8
}

try {
    if (-not (Test-Path -LiteralPath $MySqlExe)) { throw "找不到mysql.exe：$MySqlExe" }
    if (-not (Test-Path -LiteralPath $taskSqlPath)) { throw '缺少导入数据库.sql' }
    $taskService = Get-Service -Name 'MySQL80' -ErrorAction SilentlyContinue
    if ($MySqlHost -eq '127.0.0.1' -and $MySqlPort -eq 3306 -and $taskService -and $taskService.Status -ne 'Running') {
        Write-Host 'MySQL80尚未启动，正在尝试启动；权限不足时请用管理员PowerShell执行Start-Service MySQL80。'
        Start-Service -Name 'MySQL80'
    }
    Write-Host '社区老年服务系统 数据库首次导入' -ForegroundColor Cyan
    Write-Host "服务器 $MySqlHost`:$MySqlPort 账号 $MySqlUser"
    Write-Host '请输入现有MySQL账号密码。输入时不显示字符；请勿把密码发到聊天。'
    Write-Host '脚本遇到同名数据库会停止；不会删除、覆盖或修改其他数据库。'
    Write-Host ''
    Save-ImportStatus 'WAITING_FOR_PASSWORD' '请在本窗口输入MySQL密码'

    # mysql原生密码提示，密码不写入脚本、命令参数、环境变量或状态文件。
    # 用标准输入传递整个UTF-8批处理，不使用source，确保任何SQL错误均停止并返回非零退出码。
    # mysql批处理默认不继续执行错误后的语句，禁止添加--force。
    $taskSqlText = Get-Content -LiteralPath $taskSqlPath -Raw -Encoding UTF8
    $taskSqlText += "`n" + (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'migrations/V002__multi_community.sql') -Raw -Encoding UTF8)
    $taskSqlText | & $MySqlExe --no-defaults --protocol=TCP "--host=$MySqlHost" "--port=$MySqlPort" "--user=$MySqlUser" --password --default-character-set=utf8mb4 --connect-timeout=10
    if ($LASTEXITCODE -ne 0) { throw "MySQL导入失败，退出码$LASTEXITCODE；请保留窗口错误信息，不要反复重导。" }
    Save-ImportStatus 'SUCCESS' '数据库、31张业务表、4张多社区相关表与示例数据已导入；AI表在后端首次启动时增量建立'
    Write-Host ''
    Write-Host '导入成功：community_elder_service' -ForegroundColor Green
    Write-Host 'DBeaver：MySQL / 127.0.0.1 / 3306 / 数据库community_elder_service / 当前数据库账号及密码。'
    Write-Host "进一步查看：$taskVerifyPath"
} catch {
    Save-ImportStatus 'FAILED' $_.Exception.Message
    Write-Host $_.Exception.Message -ForegroundColor Red
}
Write-Host ''
Read-Host '按回车关闭此窗口'

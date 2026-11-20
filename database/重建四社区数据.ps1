param(
    [string]$ConfirmReset = '',
    [string]$MySqlUser = 'root',
    [string]$MySqlHost = '127.0.0.1',
    [int]$MySqlPort = 3306,
    [string]$Database = 'community_elder_service',
    [string]$MySqlExe = 'D:\Desktop\install\bin\mysql.exe',
    [string]$StoragePath = '',
    [switch]$UseEnvironmentPassword,
    [switch]$CheckOnly,
    [switch]$RestartBackend
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$OutputEncoding = [Console]::OutputEncoding
Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Security/Microsoft.PowerShell.Security.psd1') -ErrorAction Stop
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskJar = Join-Path $taskRoot 'backend/target/community-service-1.5.0.jar'
if ($MySqlHost -notin @('127.0.0.1','localhost') -or $Database -notmatch '^community_elder_service(?:_[a-z0-9_]+_(?:test|regression))?$') { throw '仅支持本机项目数据库或明确命名的隔离测试库。' }
if (-not $CheckOnly -and $ConfirmReset -ne $Database) { throw "此操作会清空项目数据。请显式提供 -ConfirmReset $Database；执行前自动备份，不会删除其他数据库。" }
if ($RestartBackend -and ($Database -ne 'community_elder_service' -or $MySqlPort -ne 3306)) { throw '仅正式本机项目库可使用-RestartBackend。' }
if (-not (Test-Path -LiteralPath $taskJar)) { throw '请先进入backend执行mvn package，生成1.5.0。' }
$taskDump = Join-Path (Split-Path $MySqlExe -Parent) 'mysqldump.exe'
if (-not (Test-Path -LiteralPath $taskDump)) { throw "找不到备份程序：$taskDump" }
if (-not $StoragePath) { $StoragePath = Join-Path $taskRoot 'backend/storage' }
$taskStorage = [IO.Path]::GetFullPath($StoragePath)
if (-not $taskStorage.StartsWith($taskRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw '运行数据目录必须位于项目内。' }
if (-not $UseEnvironmentPassword) {
    $taskSecret = Read-Host '请输入现有MySQL账号密码（不保存；不是应用密码123456）' -AsSecureString
    $taskPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskSecret)
    try { $env:DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskPtr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskPtr) }
}
try {
    $env:IMPORT_PROJECT_ROOT = $taskRoot
    $env:IMPORT_MYSQLDUMP = $taskDump
    $env:DB_URL = "jdbc:mysql://${MySqlHost}:${MySqlPort}/${Database}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8"
    $env:DB_USERNAME = $MySqlUser
    $env:APP_STORAGE = $taskStorage
    $taskKeys = Get-Content -LiteralPath (Join-Path $taskRoot 'database/演示数据密钥.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not $env:APP_AES_KEY) { $env:APP_AES_KEY = $taskKeys.aes256_key_hex }
    if (-not $env:APP_HMAC_KEY) { $env:APP_HMAC_KEY = $taskKeys.hmac_sha256_key_hex }
    $taskKeyFile = Join-Path $taskRoot 'backend/config/ai-key.local'
    if (-not $env:DEEPSEEK_API_KEY -and (Test-Path -LiteralPath $taskKeyFile)) {
        $taskKeySecret = (Get-Content -LiteralPath $taskKeyFile -Raw).Trim() | ConvertTo-SecureString
        $taskKeyPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskKeySecret)
        try { $env:DEEPSEEK_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskKeyPtr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskKeyPtr) }
    }
    & java "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" -jar $taskJar --check-initial-data
    if ($LASTEXITCODE -ne 0) { throw '预检查失败，未执行清理。' }
    if ($CheckOnly) { return }
    $taskBackendProcesses = @()
    foreach ($taskPort in $(if ($Database -eq 'community_elder_service' -and $MySqlPort -eq 3306) { @(8080,8443) } else { @() })) {
        $taskListener = Get-NetTCPConnection -LocalPort $taskPort -State Listen -ErrorAction SilentlyContinue
        if ($taskListener) {
            $taskProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($taskListener.OwningProcess)"
            $taskIsProject = $taskProcess.Name -eq 'java.exe' -and $taskProcess.CommandLine.Contains((Join-Path $taskRoot 'backend/target').Replace('/','\')) -and $taskProcess.CommandLine -match 'community-service-[0-9.]+\.jar'
            if ($taskIsProject) { $taskBackendProcesses += $taskListener.OwningProcess }
            elseif ($RestartBackend -and $taskPort -eq 8443) { throw '8443被其他程序占用，停止更新；不会结束该程序。' }
        }
    }
    if ($taskBackendProcesses.Count -and -not $RestartBackend) { throw '请先停止本项目后端，或显式使用-RestartBackend由脚本停止并重启。' }
    foreach ($taskPid in ($taskBackendProcesses | Select-Object -Unique)) { Stop-Process -Id $taskPid -ErrorAction Stop }
    Write-Host '正在备份并重建项目数据。备份失败会停止；V003结构升级后，导入事务失败会回滚业务数据及运行目录。' -ForegroundColor Cyan
    & java "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" -jar $taskJar --install-initial-data "--confirm-reset=$Database"
    if ($LASTEXITCODE -ne 0) { throw '重建未成功。请检查database/backups/four-communities中的manifest.json，不要忽略错误。' }
    if ($RestartBackend) {
        & (Join-Path $taskRoot 'backend/生成本机HTTPS证书.ps1')
        $env:SERVER_PORT = '8443'
        $env:SERVER_ADDRESS = '127.0.0.1'
        $taskLogs = Join-Path $taskRoot 'backend/logs'
        New-Item -ItemType Directory -Path $taskLogs -Force | Out-Null
        $taskBackend = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList @('-jar',('"'+$taskJar+'"'),('--logging.file.name='+('"'+(Join-Path $taskLogs 'initial-data-backend.log')+'"'))) -WorkingDirectory (Join-Path $taskRoot 'backend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskLogs 'initial-data-console.log') -RedirectStandardError (Join-Path $taskLogs 'initial-data-error.log') -PassThru
        Write-Host "已启动新版HTTPS后端，PID $($taskBackend.Id)。日志：backend/logs/initial-data-backend.log"
    }
    Write-Host '完成。账号admin、stats、grid1至grid5，初始密码123456。旧账号和旧登录会话不可继续使用。' -ForegroundColor Green
} finally {
    Remove-Item Env:DB_PASSWORD,Env:DEEPSEEK_API_KEY,Env:SERVER_SSL_KEY_STORE_PASSWORD,Env:LOCAL_HTTPS_PFX_PASSWORD -ErrorAction SilentlyContinue
}

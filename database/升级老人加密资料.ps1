param([string]$MySqlUser='root',[string]$MySqlExe='D:\Desktop\install\bin\mysql.exe')
$ErrorActionPreference='Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$OutputEncoding = [Console]::OutputEncoding
Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Security/Microsoft.PowerShell.Security.psd1')
$taskRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskJar=Join-Path $taskRoot 'backend/target/community-service-1.5.0.jar'
if(-not(Test-Path -LiteralPath $taskJar)){throw '请先构建v1.5.0后端。'}
foreach($taskPort in @(8080,8443)) {
    $taskListener=Get-NetTCPConnection -LocalPort $taskPort -State Listen -ErrorAction SilentlyContinue
    if($taskListener) {
        $taskProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$($taskListener.OwningProcess)"
        if($taskProcess.CommandLine -and $taskProcess.CommandLine.IndexOf($taskRoot,[StringComparison]::OrdinalIgnoreCase)-ge 0){throw '请先停止本项目后端，再升级；脚本不会自动停止服务。'}
    }
}
$taskSecret=Read-Host '请输入MySQL密码（不是网站123456）' -AsSecureString
$taskPtr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskSecret)
try {$env:DB_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskPtr)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskPtr)}
try {
    $env:DB_URL='jdbc:mysql://127.0.0.1:3306/community_elder_service?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8'
    $env:DB_USERNAME=$MySqlUser
    $env:IMPORT_PROJECT_ROOT=$taskRoot
    $env:IMPORT_MYSQLDUMP=Join-Path (Split-Path $MySqlExe -Parent) 'mysqldump.exe'
    $env:APP_STORAGE=Join-Path $taskRoot 'backend/storage'
    $taskKeys=Get-Content (Join-Path $taskRoot 'database/演示数据密钥.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if(-not $env:APP_AES_KEY){$env:APP_AES_KEY=$taskKeys.aes256_key_hex}
    if(-not $env:APP_HMAC_KEY){$env:APP_HMAC_KEY=$taskKeys.hmac_sha256_key_hex}
    & java '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -jar $taskJar --upgrade-elder-private-info
    if($LASTEXITCODE -ne 0){throw '升级失败，先检查备份和错误信息。'}
} finally {Remove-Item Env:DB_PASSWORD -ErrorAction SilentlyContinue}

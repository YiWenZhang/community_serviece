param([string]$DbUser='root',[int]$Port=8080)
$ErrorActionPreference='Stop'
$taskBackendPath=$PSScriptRoot
Set-Location -LiteralPath $taskBackendPath
$taskJar=Join-Path $taskBackendPath 'target/community-service-1.1.0.jar'
if(-not(Test-Path -LiteralPath $taskJar)) { throw '请先在backend目录执行 mvn clean package' }
$taskKeys=Get-Content -LiteralPath (Join-Path $taskBackendPath '../database/演示数据密钥.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$env:APP_AES_KEY=$taskKeys.aes256_key_hex
$env:APP_HMAC_KEY=$taskKeys.hmac_sha256_key_hex
$env:DB_USERNAME=$DbUser
$env:SERVER_PORT=$Port
$env:SERVER_ADDRESS='127.0.0.1'
$env:APP_STORAGE=Join-Path $taskBackendPath 'storage'
# 本机DPAPI密文仅首次初始化AI配置；已有数据库配置优先，不会覆盖管理员修改。
$taskAiKeyFile=Join-Path $taskBackendPath 'config/ai-key.local'
if (-not $env:DEEPSEEK_API_KEY -and (Test-Path -LiteralPath $taskAiKeyFile)) {
    # 指定当前PowerShell自带模块，兼容从PowerShell 7启动Windows PowerShell 5.1的环境。
    Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Security/Microsoft.PowerShell.Security.psd1') -ErrorAction Stop
    $taskAiSecret=(Get-Content -LiteralPath $taskAiKeyFile -Raw).Trim() | ConvertTo-SecureString
    $taskAiPtr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskAiSecret)
    try { $env:DEEPSEEK_API_KEY=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskAiPtr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskAiPtr) }
}
$taskSecret=Read-Host '请输入现有MySQL账号密码（不保存）' -AsSecureString
$taskPtr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskSecret)
try { $env:DB_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskPtr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskPtr) }
Write-Host '首次运行可设置admin_demo的应用登录密码；后续直接回车，绝不会重置已启用管理员。'
do {
    $taskAdminSecret=Read-Host '首次管理员应用密码（12至72字符；已有账号可留空）' -AsSecureString
    $taskPasswordValid=($taskAdminSecret.Length -eq 0 -or ($taskAdminSecret.Length -ge 12 -and $taskAdminSecret.Length -le 72))
    if (-not $taskPasswordValid) { Write-Host '应用密码长度不符合要求，请重新输入12至72个字符。' -ForegroundColor Yellow }
} until ($taskPasswordValid)
$taskAdminPtr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskAdminSecret)
try { $env:BOOTSTRAP_ADMIN_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskAdminPtr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskAdminPtr) }
try {
    Write-Host "正在启动 http://127.0.0.1:$Port ，按Ctrl+C停止。"
    $taskLogPath=Join-Path $taskBackendPath 'logs/local-startup.log'
    & java -jar $taskJar "--logging.file.name=$taskLogPath"
    if ($LASTEXITCODE -ne 0) { throw "后端启动失败，请查看 $taskLogPath 或上方报错。" }
} finally {
    Remove-Item Env:DB_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:DEEPSEEK_API_KEY -ErrorAction SilentlyContinue
}

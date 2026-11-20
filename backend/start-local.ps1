param([string]$DbUser='root',[int]$Port=8080)
$ErrorActionPreference='Stop'
$taskBackendPath=$PSScriptRoot
Set-Location -LiteralPath $taskBackendPath
$taskJar=Join-Path $taskBackendPath 'target/community-service-1.3.0.jar'
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
Write-Host '应用初始密码统一为123456，已修改的密码不会因重启被覆盖。'
try {
    $taskScheme=if($env:SERVER_SSL_ENABLED -eq 'true'){'https'}else{'http'}
    Write-Host "正在启动 ${taskScheme}://127.0.0.1:$Port ，按Ctrl+C停止。"
    $taskLogPath=Join-Path $taskBackendPath 'logs/local-startup.log'
    & java -jar $taskJar "--logging.file.name=$taskLogPath"
    if ($LASTEXITCODE -ne 0) { throw "后端启动失败，请查看 $taskLogPath 或上方报错。" }
} finally {
    Remove-Item Env:DB_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:SERVER_SSL_KEY_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:LOCAL_HTTPS_PFX_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:DEEPSEEK_API_KEY -ErrorAction SilentlyContinue
}

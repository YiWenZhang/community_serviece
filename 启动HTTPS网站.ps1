$ErrorActionPreference = 'Stop'
$taskProjectRoot = $PSScriptRoot
foreach ($taskPort in @(8443,5443)) {
    if (Get-NetTCPConnection -LocalPort $taskPort -State Listen -ErrorAction SilentlyContinue) { throw "端口$taskPort已被占用，请先检查对应服务。脚本不会停止其他程序。" }
}
if (-not (Test-Path -LiteralPath (Join-Path $taskProjectRoot 'backend/target/community-service-1.6.0.jar'))) { throw '请先进入backend执行 mvn package。' }
$taskService = Get-Service MySQL80 -ErrorAction Stop
if ($taskService.Status -ne 'Running') { Start-Service MySQL80 -ErrorAction Stop }
& (Join-Path $taskProjectRoot 'backend/生成本机HTTPS证书.ps1')
$taskPowerShell = Join-Path $PSHOME 'pwsh.exe'
if (-not (Test-Path -LiteralPath $taskPowerShell)) { $taskPowerShell = Join-Path $PSHOME 'powershell.exe' }
try {
    Start-Process -FilePath $taskPowerShell -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-NoExit','-File',('"'+(Join-Path $taskProjectRoot 'backend/start-local.ps1')+'"'),'-Port','8443') -WindowStyle Normal
    Start-Process -FilePath $taskPowerShell -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-NoExit','-File',('"'+(Join-Path $taskProjectRoot 'frontend/start-local.ps1')+'"')) -WindowStyle Normal
    Write-Host '请在后端窗口输入MySQL密码。应用初始密码为123456；已修改的密码不会被覆盖。'
    Write-Host '启动完成后访问 https://127.0.0.1:5443；本机证书信任步骤见《密码与安全检查说明》。'
} finally {
    Remove-Item Env:LOCAL_HTTPS_PFX_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:SERVER_SSL_KEY_STORE_PASSWORD -ErrorAction SilentlyContinue
}

$ErrorActionPreference = 'Stop'
$taskProjectRoot = $PSScriptRoot
$taskPowerShell = 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
$taskService = Get-Service -Name MySQL80 -ErrorAction Stop
if ($taskService.Status -ne 'Running') {
    try { Start-Service -Name MySQL80 }
    catch { throw 'MySQL尚未启动。请在管理员PowerShell执行 Start-Service MySQL80，再运行本脚本。' }
}
if (-not (Test-Path -LiteralPath (Join-Path $taskProjectRoot 'backend/target/community-service-1.2.0.jar'))) {
    throw '缺少后端JAR，请先进入backend执行 mvn package。'
}
foreach ($taskPort in @(8080,5173)) {
    if (Get-NetTCPConnection -LocalPort $taskPort -State Listen -ErrorAction SilentlyContinue) {
        throw "端口$taskPort已被占用。如已启动本项目，直接访问http://127.0.0.1:5173；否则先检查占用程序。脚本不会终止其他服务。"
    }
}
$taskBackendScript = Join-Path $taskProjectRoot 'backend/start-local.ps1'
$taskFrontendScript = Join-Path $taskProjectRoot 'frontend/start-local.ps1'
# 两个窗口供用户输入密码、查看启动日志以及按Ctrl+C停止服务。
Start-Process -FilePath $taskPowerShell -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-NoExit','-File',('"'+$taskBackendScript+'"')) -WindowStyle Normal
Start-Process -FilePath $taskPowerShell -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-NoExit','-File',('"'+$taskFrontendScript+'"')) -WindowStyle Normal
Write-Host '已打开前后端窗口。请在后端窗口输入MySQL密码，并按提示设置首次应用管理员密码。'
Write-Host '后端出现 Started CommunityApplication、前端出现 Local 后，访问 http://127.0.0.1:5173'

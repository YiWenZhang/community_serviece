$ErrorActionPreference = 'Stop'
$env:NODE_TLS_REJECT_UNAUTHORIZED = '1'
Set-Location -LiteralPath $PSScriptRoot
if (-not (Test-Path -LiteralPath 'node_modules/vite/package.json')) {
    npm.cmd ci --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败，请检查网络。' }
}
if ($env:LOCAL_HTTPS_PFX) {
    Write-Host '前端地址 https://127.0.0.1:5443 ，按Ctrl+C停止。'
    npm.cmd run dev -- --port 5443 --strictPort
} else {
    Write-Host '前端地址 http://127.0.0.1:5173 ，仅供本机调试，按Ctrl+C停止。'
    npm.cmd run dev -- --strictPort
}

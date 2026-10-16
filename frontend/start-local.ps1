$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (-not (Test-Path -LiteralPath 'node_modules/vite/package.json')) {
    npm.cmd ci --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败，请检查网络。' }
}
Write-Host '前端地址 http://127.0.0.1:5173 ，请保留窗口，按Ctrl+C停止。'
npm.cmd run dev

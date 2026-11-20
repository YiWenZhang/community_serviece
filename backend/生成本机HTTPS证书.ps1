$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Security/Microsoft.PowerShell.Security.psd1') -ErrorAction Stop
$taskConfig = Join-Path $PSScriptRoot 'config'
$taskStore = Join-Path $taskConfig 'local-https.p12'
$taskCaStore = Join-Path $taskConfig 'local-ca.p12'
$taskCa = Join-Path $taskConfig 'local-ca.crt'
$taskCert = Join-Path $taskConfig 'local-https.crt'
$taskCsr = Join-Path $taskConfig 'local-https.csr'
$taskSecretFile = Join-Path $taskConfig 'https-password.local'
$taskKeytoolCommand = Get-Command keytool.exe -ErrorAction SilentlyContinue
if ($taskKeytoolCommand) { $taskKeytool = $taskKeytoolCommand.Source }
else {
    # Java把版本信息写到stderr；使用Process捕获，避免PowerShell 5.1将其视为终止错误。
    $taskJavaStart = New-Object System.Diagnostics.ProcessStartInfo
    $taskJavaStart.FileName = (Get-Command java.exe -ErrorAction Stop).Source
    $taskJavaStart.Arguments = '-XshowSettings:properties -version'
    $taskJavaStart.UseShellExecute = $false
    $taskJavaStart.RedirectStandardError = $true
    $taskJavaStart.RedirectStandardOutput = $true
    $taskJavaStart.CreateNoWindow = $true
    $taskJavaProcess = New-Object System.Diagnostics.Process
    $taskJavaProcess.StartInfo = $taskJavaStart
    try {
        [void]$taskJavaProcess.Start()
        $taskJavaInfo = $taskJavaProcess.StandardError.ReadToEnd() + $taskJavaProcess.StandardOutput.ReadToEnd()
        $taskJavaProcess.WaitForExit()
        if ($taskJavaProcess.ExitCode -ne 0) { throw '读取Java安装路径失败。' }
    } finally { $taskJavaProcess.Dispose() }
    if ($taskJavaInfo -notmatch 'java.home\s*=\s*([^\r\n]+)') { throw '未找到JDK，请安装Java21或更高版本。' }
    $taskKeytool = Join-Path $Matches[1].Trim() 'bin/keytool.exe'
    if (-not (Test-Path -LiteralPath $taskKeytool)) { throw '当前Java未包含keytool，请安装完整JDK。' }
}
if (-not (Test-Path -LiteralPath $taskStore)) {
    if (Test-Path -LiteralPath $taskCaStore) { throw '存在未完成的本机证书，请检查config/local-*，脚本不会覆盖已有私钥。' }
    $taskBytes = New-Object byte[] 32
    $taskRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $taskRandom.GetBytes($taskBytes) } finally { $taskRandom.Dispose() }
    $taskPassword = [Convert]::ToBase64String($taskBytes)
    $env:COMMUNITY_TLS_PASSWORD = $taskPassword
    try {
        $taskCommands = @(
            @('-genkeypair','-alias','community-ca','-dname','CN=Community Local Development CA','-keyalg','RSA','-keysize','3072','-validity','365','-ext','BC=ca:true','-ext','KU=keyCertSign,cRLSign','-storetype','PKCS12','-keystore',$taskCaStore),
            @('-genkeypair','-alias','community-local','-dname','CN=localhost','-keyalg','RSA','-keysize','3072','-validity','365','-ext','SAN=dns:localhost,ip:127.0.0.1','-storetype','PKCS12','-keystore',$taskStore),
            @('-certreq','-alias','community-local','-keystore',$taskStore,'-file',$taskCsr),
            @('-gencert','-alias','community-ca','-keystore',$taskCaStore,'-infile',$taskCsr,'-outfile',$taskCert,'-rfc','-validity','365','-ext','SAN=dns:localhost,ip:127.0.0.1','-ext','KU=digitalSignature,keyEncipherment','-ext','EKU=serverAuth'),
            @('-exportcert','-alias','community-ca','-keystore',$taskCaStore,'-rfc','-file',$taskCa),
            @('-importcert','-noprompt','-alias','community-ca','-keystore',$taskStore,'-file',$taskCa),
            @('-importcert','-noprompt','-alias','community-local','-keystore',$taskStore,'-file',$taskCert)
        )
        foreach ($taskCommand in $taskCommands) {
            & $taskKeytool @taskCommand '-storepass:env' 'COMMUNITY_TLS_PASSWORD'
            if ($LASTEXITCODE -ne 0) { throw '生成HTTPS证书失败，请检查上方keytool输出。' }
        }
        $taskPassword | ConvertTo-SecureString -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath $taskSecretFile -Encoding UTF8
    } finally { Remove-Item Env:COMMUNITY_TLS_PASSWORD -ErrorAction SilentlyContinue }
} else {
    if (-not (Test-Path -LiteralPath $taskSecretFile)) { throw '证书口令文件缺失，不能覆盖已有证书。' }
    $taskSecret = (Get-Content -LiteralPath $taskSecretFile -Raw).Trim() | ConvertTo-SecureString
    $taskPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskSecret)
    try { $taskPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskPtr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskPtr) }
}
$env:LOCAL_HTTPS_PFX = $taskStore
$env:LOCAL_HTTPS_PFX_PASSWORD = $taskPassword
$env:LOCAL_HTTPS_CA = $taskCa
$env:SERVER_SSL_ENABLED = 'true'
$env:SERVER_SSL_KEY_STORE = ([Uri]$taskStore).AbsoluteUri
$env:SERVER_SSL_KEY_STORE_PASSWORD = $taskPassword
$env:SERVER_SSL_KEY_ALIAS = 'community-local'
$env:APP_REQUIRE_HTTPS = 'true'
$env:COOKIE_SECURE = 'true'
$env:VITE_API_TARGET = 'https://127.0.0.1:8443'
Write-Host "本机HTTPS证书已准备：$taskCa"
Write-Host '脚本没有安装Windows受信任根证书。首次浏览器使用请按《密码与安全检查说明》核验并信任此本机CA。'

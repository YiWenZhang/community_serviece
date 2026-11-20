param([string]$MySqlUser='root',[string]$MySqlExe='D:\Desktop\install\bin\mysql.exe')
$ErrorActionPreference='Stop'
# The shared upgrade entry backs up the database and applies V003 and V004, without clearing data.
& (Join-Path $PSScriptRoot '升级老人加密资料.ps1') -MySqlUser $MySqlUser -MySqlExe $MySqlExe

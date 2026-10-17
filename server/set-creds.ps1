# Writes server/navi-creds.txt (gitignored) with a masked password prompt.
# Run from the project root:  ! powershell -ExecutionPolicy Bypass -File server\set-creds.ps1
$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot 'navi-creds.txt'
$sec  = Read-Host 'Navidrome password for enoluca' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
$pw   = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
if ([string]::IsNullOrWhiteSpace($pw)) { Write-Host 'ERROR: empty password, nothing written'; exit 1 }
# ASCII, no BOM, exactly two lines: username then password
[System.IO.File]::WriteAllText($path, "enoluca`n$pw", (New-Object System.Text.ASCIIEncoding))
$lines = (Get-Content $path).Count
Write-Host "saved $path ($lines lines)"

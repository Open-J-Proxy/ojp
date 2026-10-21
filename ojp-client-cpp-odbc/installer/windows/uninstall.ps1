#Requires -Version 5.1
<#
.SYNOPSIS
    Unregisters the OJP ODBC driver (32-bit and 64-bit) installed by install.ps1 and,
    optionally, removes its files.

.DESCRIPTION
    For an installation made with the Inno Setup installer, use "Installed apps" instead.

.PARAMETER KeepFiles
    Keep the files in the install directories and only remove the registry entries.

.PARAMETER InstallDir64
    Default: "Program Files\OJP ODBC".

.PARAMETER InstallDir86
    Default: "Program Files (x86)\OJP ODBC".
#>
[CmdletBinding()]
param(
    [switch]$KeepFiles,
    [string]$InstallDir64 = (Join-Path $(if ($env:ProgramW6432) { $env:ProgramW6432 } else { $env:ProgramFiles }) 'OJP ODBC'),
    [string]$InstallDir86 = (Join-Path $(if (${env:ProgramFiles(x86)}) { ${env:ProgramFiles(x86)} } else { $env:ProgramFiles }) 'OJP ODBC')
)

$ErrorActionPreference = 'Stop'
$DriverName = 'OJP'

$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script as Administrator.'
}

function Unregister-OjpDriver {
    param([Microsoft.Win32.RegistryView]$View)

    $hklm = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::LocalMachine, $View)
    try {
        $odbcInst = $hklm.OpenSubKey('SOFTWARE\ODBC\ODBCINST.INI', $true)
        if ($null -eq $odbcInst) { return }

        $drivers = $odbcInst.OpenSubKey('ODBC Drivers', $true)
        if ($null -ne $drivers -and $null -ne $drivers.GetValue($DriverName)) {
            $drivers.DeleteValue($DriverName)
        }
        $odbcInst.DeleteSubKeyTree($DriverName, $false)
    }
    finally {
        $hklm.Dispose()
    }
}

Unregister-OjpDriver -View ([Microsoft.Win32.RegistryView]::Registry32)
if ([Environment]::Is64BitOperatingSystem) {
    Unregister-OjpDriver -View ([Microsoft.Win32.RegistryView]::Registry64)
}
Write-Host "Driver '$DriverName' unregistered (32-bit and 64-bit)."

if (-not $KeepFiles) {
    foreach ($dir in $InstallDir64, $InstallDir86 | Select-Object -Unique) {
        if (Test-Path -LiteralPath $dir) {
            Remove-Item -LiteralPath $dir -Recurse -Force
            Write-Host "Removed '$dir'."
        }
    }
}

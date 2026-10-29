#Requires -Version 5.1
<#
.SYNOPSIS
    Installs and registers the OJP ODBC driver (32-bit and/or 64-bit) without an installer.

.DESCRIPTION
    Copies the DLLs of each build directory (driver, setup DLL and dependency DLLs copied by
    vcpkg) to the install directory and registers the "OJP" driver with the ODBC Driver
    Manager of that architecture. If ojp_odbc_setup.dll is present it is registered as the
    Setup DLL, which enables the DSN dialog in the ODBC Data Source Administrator.

.PARAMETER SourceDir64
    Build directory of the 64-bit driver. Skipped when empty.

.PARAMETER SourceDir86
    Build directory of the 32-bit driver. Skipped when empty.

.PARAMETER InstallDir64
    Default: "Program Files\OJP ODBC".

.PARAMETER InstallDir86
    Default: "Program Files (x86)\OJP ODBC".

.EXAMPLE
    .\install.ps1 -SourceDir64 ..\..\build\release -SourceDir86 ..\..\build\release-x86
#>
[CmdletBinding()]
param(
    [string]$SourceDir64,
    [string]$SourceDir86,
    [string]$InstallDir64 = (Join-Path $(if ($env:ProgramW6432) { $env:ProgramW6432 } else { $env:ProgramFiles }) 'OJP ODBC'),
    [string]$InstallDir86 = (Join-Path $(if (${env:ProgramFiles(x86)}) { ${env:ProgramFiles(x86)} } else { $env:ProgramFiles }) 'OJP ODBC')
)

$ErrorActionPreference = 'Stop'
$DriverName = 'OJP'
$DriverDll = 'ojp_odbc.dll'
$SetupDll = 'ojp_odbc_setup.dll'

if (-not $SourceDir64 -and -not $SourceDir86) {
    throw 'Pass -SourceDir64 and/or -SourceDir86.'
}

$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script as Administrator.'
}

function Register-OjpDriver {
    param([string]$TargetDir, [Microsoft.Win32.RegistryView]$View)

    $hklm = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::LocalMachine, $View)
    try {
        $odbcInst = $hklm.CreateSubKey('SOFTWARE\ODBC\ODBCINST.INI')
        $drivers = $odbcInst.CreateSubKey('ODBC Drivers')
        $drivers.SetValue($DriverName, 'Installed', [Microsoft.Win32.RegistryValueKind]::String)

        $key = $odbcInst.CreateSubKey($DriverName)
        $key.SetValue('Driver', (Join-Path $TargetDir $DriverDll), [Microsoft.Win32.RegistryValueKind]::String)

        $setupPath = Join-Path $TargetDir $SetupDll
        if (Test-Path -LiteralPath $setupPath) {
            $key.SetValue('Setup', $setupPath, [Microsoft.Win32.RegistryValueKind]::String)
        }
        elseif ($null -ne $key.GetValue('Setup')) {
            $key.DeleteValue('Setup')
        }
    }
    finally {
        $hklm.Dispose()
    }
}

function Install-OjpArch {
    param([string]$Name, [string]$Source, [string]$Target, [Microsoft.Win32.RegistryView]$View)

    if (-not (Test-Path -LiteralPath (Join-Path $Source $DriverDll))) {
        throw "[$Name] $DriverDll not found in '$Source'."
    }

    New-Item -ItemType Directory -Path $Target -Force | Out-Null
    Copy-Item -Path (Join-Path $Source '*.dll') -Destination $Target -Force
    Register-OjpDriver -TargetDir $Target -View $View

    $hasSetup = Test-Path -LiteralPath (Join-Path $Target $SetupDll)
    Write-Host "[$Name] Installed in '$Target' (setup DLL: $(if ($hasSetup) { 'yes' } else { 'no' }))."
}

$is64 = [Environment]::Is64BitOperatingSystem

if ($SourceDir64) {
    if ($is64) { Install-OjpArch -Name 'x64' -Source $SourceDir64 -Target $InstallDir64 -View ([Microsoft.Win32.RegistryView]::Registry64) }
    else { Write-Warning '32-bit Windows: the x64 driver was skipped.' }
}

if ($SourceDir86) {
    Install-OjpArch -Name 'x86' -Source $SourceDir86 -Target $InstallDir86 -View ([Microsoft.Win32.RegistryView]::Registry32)
}

Write-Host "`nRegistered drivers:"
if ($is64) { Get-OdbcDriver -Name $DriverName -Platform '64-bit' -ErrorAction SilentlyContinue | Format-List Name, Platform, Attribute }
Get-OdbcDriver -Name $DriverName -Platform '32-bit' -ErrorAction SilentlyContinue | Format-List Name, Platform, Attribute

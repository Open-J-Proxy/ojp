# Windows installer for the OJP ODBC driver

This folder builds a Windows installer that copies the 64-bit and/or 32-bit OJP ODBC driver to
`Program Files` and registers it as `OJP` with the ODBC Driver Manager of each architecture. It also
contains scripts that do the same without an installer.

| File | Purpose |
|---|---|
| `ojp-odbc.iss` | [Inno Setup](https://jrsoftware.org/isinfo.php) script that produces `Output\OJP-ODBC-Setup-<version>.exe` |
| `install.ps1` | Installs and registers the driver straight from the build directories, without an installer |
| `uninstall.ps1` | Unregisters the driver installed by `install.ps1` and removes its files |

The installer is a **preview**: it is not signed and it is not the MSI proposed in the
[ODBC driver distribution analysis](../../../documents/analysis/ODBC_DRIVER_DISTRIBUTION_ANALYSIS.md).

## What gets installed

| | 64-bit | 32-bit |
|---|---|---|
| Files | `C:\Program Files\OJP ODBC` | `C:\Program Files (x86)\OJP ODBC` |
| Registry | `HKLM\SOFTWARE\ODBC\ODBCINST.INI\OJP` | `HKLM\SOFTWARE\WOW6432Node\ODBC\ODBCINST.INI\OJP` |
| ODBC Administrator | `%windir%\System32\odbcad32.exe` | `%windir%\SysWOW64\odbcad32.exe` |

Both architectures register `Driver` (`ojp_odbc.dll`) and, when the build has it, `Setup`
(`ojp_odbc_setup.dll`), which opens the OJP dialog for **Add** and **Configure** in the ODBC
Administrator. Install the architecture that matches the application: a 32-bit application, such as a
32-bit Delphi program or 32-bit Office, needs the 32-bit driver.

The files copied are all `*.dll` files of the build directory: the driver, the setup DLL and the gRPC,
Protobuf, OpenSSL, Abseil, c-ares, RE2 and zlib DLLs that vcpkg copies next to them.

## Requirements

- The driver built for each architecture you want to ship. Build the 64-bit driver as described in the
  [Windows build instructions](../../README.md#windows), and the 32-bit driver the same way, in its own
  build directory, from an **x86 Native Tools** prompt with the `x86-windows` triplet:

  ```powershell
  & "$env:VCPKG_ROOT/vcpkg.exe" install grpc:x86-windows protobuf:x86-windows
  Set-Location ojp-client-cpp-odbc
  cmake -S . -B build/release-x86 -G Ninja -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF `
    "-DGOOGLEAPIS_PROTO_DIR=C:/sources/googleapis" `
    "-DCMAKE_TOOLCHAIN_FILE=$env:VCPKG_ROOT/scripts/buildsystems/vcpkg.cmake" -DVCPKG_TARGET_TRIPLET=x86-windows
  cmake --build build/release-x86 --target ojp_odbc ojp_odbc_setup --parallel
  ```

  Build both `ojp_odbc` and `ojp_odbc_setup`; the `release` build preset builds only `ojp_odbc`. With the
  vcpkg toolchain, the dependency DLLs are copied to the build directory, which is the installer input.
- [Inno Setup](https://jrsoftware.org/isdl.php) 6.3 or later, to build the installer.
- On the target machines, the
  [Microsoft Visual C++ 2015-2022 Redistributable](https://learn.microsoft.com/cpp/windows/latest-supported-vc-redist)
  for each installed architecture. The driver links the Visual C++ runtime dynamically and the installer
  does not bundle it. The installer warns when it is missing.

## Build the installer

Pass absolute paths to the build directories. At least one is required; leave one out to ship a single
architecture.

```powershell
Set-Location ojp-client-cpp-odbc\installer\windows
$build = (Resolve-Path ..\..\build).Path
& "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe" `
  "/DBuildDir64=$build\release" "/DBuildDir86=$build\release-x86" /DAppVersion=1.0.0 ojp-odbc.iss
```

Use the `ISCC.exe` of your Inno Setup installation (per-user installs live under
`%LOCALAPPDATA%\Programs`, machine-wide ones under `C:\Program Files (x86)`). The result is
`Output\OJP-ODBC-Setup-1.0.0.exe`; `Output\` is ignored by git.

| Define | Required | Default | Description |
|---|---|---|---|
| `BuildDir64` | One of the two | - | Build directory with the 64-bit `ojp_odbc.dll` |
| `BuildDir86` | One of the two | - | Build directory with the 32-bit `ojp_odbc.dll` |
| `AppVersion` | No | `1.0.0` | Installer version and file name suffix |

Run the installer as Administrator; it also accepts the standard Inno Setup `/SILENT` and
`/VERYSILENT` options. Remove the driver from **Settings > Apps > Installed apps** (*OJP ODBC Driver*).
Data sources (DSNs) created by users are not removed.

## Install without an installer

From an elevated PowerShell:

```powershell
Set-Location ojp-client-cpp-odbc\installer\windows
.\install.ps1 -SourceDir64 ..\..\build\release -SourceDir86 ..\..\build\release-x86
```

`install.ps1` copies the DLLs to the same directories as the installer and writes the same registry
entries. Use `-InstallDir64` / `-InstallDir86` to install elsewhere. To remove it:

```powershell
.\uninstall.ps1            # unregister and delete the files
.\uninstall.ps1 -KeepFiles # unregister only
```

Do not mix both methods on the same machine: `uninstall.ps1` does not update the installer's entry in
**Installed apps**.

## Verify the installation

```powershell
Get-OdbcDriver -Name OJP | Format-List Name, Platform, Attribute
```

If an application reports that the driver cannot be loaded, check that the Visual C++ Redistributable is
installed and that the application and driver architectures match.

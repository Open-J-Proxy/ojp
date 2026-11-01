; Inno Setup script for the OJP ODBC driver (32-bit and 64-bit).
; Build (paths must be absolute; at least one build directory is required):
;   ISCC.exe /DBuildDir64=C:\...\build\release /DBuildDir86=C:\...\build\release-x86 /DAppVersion=1.0.0 ojp-odbc.iss
; Output: Output\OJP-ODBC-Setup-<AppVersion>.exe

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

#if !Defined(BuildDir64) && !Defined(BuildDir86)
  #error Define BuildDir64 and/or BuildDir86 with /DBuildDir64=<dir> /DBuildDir86=<dir>
#endif
#ifdef BuildDir64
  #if !FileExists(BuildDir64 + "\ojp_odbc.dll")
    #error BuildDir64 does not contain ojp_odbc.dll
  #endif
#endif
#ifdef BuildDir86
  #if !FileExists(BuildDir86 + "\ojp_odbc.dll")
    #error BuildDir86 does not contain ojp_odbc.dll
  #endif
#endif

#define AppName "OJP ODBC Driver"
#define DriverName "OJP"
#define InstallSubDir "OJP ODBC"
#define RegBase "SOFTWARE\ODBC\ODBCINST.INI"

[Setup]
AppId={{6F1D2B9C-4E57-4C0A-9B7A-0F6E3A8D5C21}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Open J Proxy
DefaultDirName={commonpf}\{#InstallSubDir}
DisableDirPage=yes
DisableProgramGroupPage=yes
PrivilegesRequired=admin
#ifdef BuildDir86
ArchitecturesAllowed=x86compatible
#else
ArchitecturesAllowed=x64compatible
#endif
#ifdef BuildDir64
ArchitecturesInstallIn64BitMode=x64compatible
#endif
OutputDir=Output
OutputBaseFilename=OJP-ODBC-Setup-{#AppVersion}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayName={#AppName}

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
; The build directory holds the driver, the setup DLL and the dependency DLLs copied by vcpkg.
#ifdef BuildDir64
Source: "{#BuildDir64}\*.dll"; DestDir: "{commonpf64}\{#InstallSubDir}"; Flags: ignoreversion; Check: Is64BitInstallMode
#endif
#ifdef BuildDir86
Source: "{#BuildDir86}\*.dll"; DestDir: "{commonpf32}\{#InstallSubDir}"; Flags: ignoreversion
#endif

[Registry]
#ifdef BuildDir64
; 64-bit registry view
Root: HKLM64; Subkey: "{#RegBase}\ODBC Drivers"; ValueType: string; ValueName: "{#DriverName}"; ValueData: "Installed"; Flags: uninsdeletevalue; Check: Is64BitInstallMode
Root: HKLM64; Subkey: "{#RegBase}\{#DriverName}"; ValueType: string; ValueName: "Driver"; ValueData: "{commonpf64}\{#InstallSubDir}\ojp_odbc.dll"; Flags: uninsdeletekey; Check: Is64BitInstallMode
  #if FileExists(BuildDir64 + "\ojp_odbc_setup.dll")
Root: HKLM64; Subkey: "{#RegBase}\{#DriverName}"; ValueType: string; ValueName: "Setup"; ValueData: "{commonpf64}\{#InstallSubDir}\ojp_odbc_setup.dll"; Check: Is64BitInstallMode
  #endif
#endif
#ifdef BuildDir86
; 32-bit registry view (WOW6432Node on 64-bit Windows)
Root: HKLM32; Subkey: "{#RegBase}\ODBC Drivers"; ValueType: string; ValueName: "{#DriverName}"; ValueData: "Installed"; Flags: uninsdeletevalue
Root: HKLM32; Subkey: "{#RegBase}\{#DriverName}"; ValueType: string; ValueName: "Driver"; ValueData: "{commonpf32}\{#InstallSubDir}\ojp_odbc.dll"; Flags: uninsdeletekey
  #if FileExists(BuildDir86 + "\ojp_odbc_setup.dll")
Root: HKLM32; Subkey: "{#RegBase}\{#DriverName}"; ValueType: string; ValueName: "Setup"; ValueData: "{commonpf32}\{#InstallSubDir}\ojp_odbc_setup.dll"
  #endif
#endif

[Code]
// The driver links the Visual C++ runtime dynamically and does not bundle it.
function VCRuntimeInstalled(RootKey: Integer; const Arch: String): Boolean;
var
  Installed: Cardinal;
begin
  Result := RegQueryDWordValue(RootKey, 'SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\' + Arch,
    'Installed', Installed) and (Installed = 1);
end;

function InitializeSetup(): Boolean;
var
  Missing: String;
begin
  Missing := '';
#ifdef BuildDir86
  if not VCRuntimeInstalled(HKLM32, 'x86') then
    Missing := Missing + #13#10 + '  - x86 (https://aka.ms/vs/17/release/vc_redist.x86.exe)';
#endif
#ifdef BuildDir64
  if IsX64Compatible and not VCRuntimeInstalled(HKLM64, 'x64') then
    Missing := Missing + #13#10 + '  - x64 (https://aka.ms/vs/17/release/vc_redist.x64.exe)';
#endif
  Result := (Missing = '') or
    (SuppressibleMsgBox('The Microsoft Visual C++ 2015-2022 Redistributable was not found for:' + Missing + #13#10#13#10 +
      'The driver cannot load until it is installed. Continue anyway?',
      mbConfirmation, MB_YESNO, IDYES) = IDYES);
end;

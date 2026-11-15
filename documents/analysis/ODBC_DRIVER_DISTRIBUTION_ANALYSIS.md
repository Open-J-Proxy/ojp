# OJP ODBC Driver Distribution Analysis

**Status:** Proposal — release workflow not implemented (19 October 2026)

## Summary

**Goal:** Deliver prebuilt releases of
[`ojp-client-cpp-odbc`](../../ojp-client-cpp-odbc/README.md) for Windows,
Linux, and macOS, with clear architecture and compatibility rules.
This is analysis only: no workflows, packages, or repository are created.

**Recommendation:** Keep the client source, CMake build, and integration tests
in `Open-J-Proxy/ojp`. Use a separate distribution repository under the same
organization (proposed name: `Open-J-Proxy/ojp-odbc`) for packaging, releases,
installation instructions, and downloads. Build from an exact OJP tag resolved
to a commit, not a moving branch. The repository name is not yet approved.

Start with archives for Windows x64/x86, Linux x86_64/ARM64, and macOS
x86_64/ARM64, subject to the target decisions below. Native installers can
follow. Put compiled packages in GitHub Releases, not Git history.

**Confidence:** High for this approach; Medium for the porting effort.
Shared-library builds and Linux integration suites exist, but Windows/macOS
build instructions do not prove those platforms work.

---

## 1. Current State

| Area | Current state and gap |
|---|---|
| Release | `.github/workflows/release.yml` publishes Maven Central artifacts and Docker images, not C++ packages. Users must build the driver. |
| Build/install | C++17, CMake release preset, and install rules produce `.dll`, `.so`, or `.dylib`. The preset disables tests; installing a build does not prove it is ready to release. |
| Dependencies | CMake links gRPC/protobuf; Linux CI uses system development packages. Install rules do not bundle third-party runtimes or check how the installed driver finds them. |
| Exports | `CXX_VISIBILITY_PRESET default`, `WINDOWS_EXPORT_ALL_SYMBOLS ON`, and Windows XA export annotations exist. Limit exports to supported APIs and check for exposed dependency symbols. |
| Platforms | Windows UTC handling and cross-platform instructions exist; ODBC integration jobs in `main.yml` run on Ubuntu. Windows/macOS still need build and installed-package tests. |
| Connections | ANSI/Unicode APIs exist; standard connections are DSN-less. There is no Windows installer or setup-dialog DLL. DSN and BI-tool support are separate work. |
| Versions | CMake uses `0.1.0`; `SQL_DRIVER_VER` reports `00.01.0000`. Use one version source and define driver/server compatibility. |
| Build inputs | Users need CMake, gRPC, protobuf, a platform ODBC manager/headers, and googleapis sources. `GOOGLEAPIS_PROTO_DIR` supplies `google/type/date.proto` and `timeofday.proto`. |

---

## 2. Installation and OS Differences

An installation normally includes a driver library and a registration entry
pointing to it. Applications use the ODBC Driver Manager to load the driver
named in a connection string or DSN, such as `DRIVER={OJP};...`.
OJP's extra XA APIs must also remain exported.

| OS | Driver Manager | Driver registration | Typical delivery | Real-world examples |
|---|---|---|---|---|
| **Windows** | Built into the OS (`odbc32.dll`) | Registry: `HKLM\SOFTWARE\ODBC\ODBCINST.INI` (32-bit drivers under `WOW6432Node`) | Signed **MSI installer**; sometimes winget/Chocolatey | Microsoft ODBC Driver for SQL Server (`msodbcsql`), psqlODBC MSI, MySQL Connector/ODBC MSI |
| **Linux** | Usually unixODBC | `/etc/odbcinst.ini` or `$ODBCSYSINI` | `.deb` / `.rpm`, often from a vendor repository, and `.tar.gz` for manual installs | `msodbcsql18` from packages.microsoft.com; Debian/Ubuntu `odbc-postgresql` |
| **macOS** | No built-in manager is commonly used; unixODBC (Homebrew) or iODBC | `odbcinst.ini` (location depends on the manager) | **Notarized .pkg** and/or **Homebrew tap** | `brew tap microsoft/mssql-release` |

**Windows**
- Has separate 32-bit and 64-bit ODBC Administrator tools.
- Plan Authenticode signing for public DLLs/installers. Unsigned downloads may
  face SmartScreen warnings or company restrictions, but can still load.
- ODBC Administrator uses `ConfigDSN`, often in a separate setup DLL, for a
  DSN dialog. Without it, an installer or registry edits must create DSNs.
- Check binary compatibility: `SQL_API` calling conventions, undecorated x86
  API names, Unicode, and the matching Visual C++ runtime.

**Linux**
- Test glibc and C++ runtime requirements on the oldest supported distribution;
  building on the newest runner does not prove compatibility.
- Alpine/musl needs a separate build from glibc.
- Target unixODBC.

**macOS**
- Plan Developer ID signing and notarization for public installers.
  Gatekeeper behavior depends on packaging, quarantine, and the host app;
  unsigned `.dylib` files are not always blocked.
- A universal binary needs both architecture slices in every bundled dependency.
- Choose and test a minimum macOS version per architecture.
- Target Homebrew's unixODBC; do not mix it with iODBC.

---

## 3. Repository and Package Location

| Option | Advantages | Costs |
|---|---|---|
| Folder in the existing module | Change source/build/packaging together; reuse CI and permissions; no cross-repository trigger. | Native and main-project releases share one location. |
| Separate distribution repository; source stays here | Dedicated downloads, release rules, signing permissions, and installation instructions. | Must pin source commits and coordinate packaging changes and triggers across repositories. |
| Move client source to a new repository | Independent source ownership and releases. | Must extract shared protobuf inputs, reconnect server tests, and maintain cross-repository compatibility. |

Prefer the second option: publishing packages does not require moving source.

For module-local packaging, keep definitions in
`ojp-client-cpp-odbc/packaging/`, split by platform, and generated packages in
an ignored folder such as `ojp-client-cpp-odbc/build/packages/`.
Either approach must record each release's source commit, packaging revision,
and dependency/toolchain versions.

## 4. Architecture Matrix

“64-bit” alone is not enough: x86_64 and ARM64 need different binaries.
The application, driver, and loaded dependencies must match architectures:
32-bit Excel cannot load a 64-bit driver, even on 64-bit Windows.
The separate OJP server process need not match the client's bitness.

| OS | Initial proposed targets | 32-bit policy |
|---|---|---|
| Windows | x64 and x86 | Include x86 for 32-bit Office/legacy users; test in a real 32-bit process on supported Windows. |
| Linux | x86_64 and ARM64/aarch64 | Defer x86 unless demand justifies its dependencies, tools, and tests. |
| macOS | Intel x86_64 and Apple Silicon arm64 | Do not target 32-bit: modern macOS does not run 32-bit applications. |

Windows ARM64, Linux musl/x86, and macOS universal packages are later options.
Start with separate macOS archives. Pin runner images and check architecture
availability before implementation.

## 5. Build and Test Requirements

### Repeatable builds and dependencies

- Pin compilers, SDKs, dependencies, and googleapis inputs. A pinned package
  manager such as vcpkg is one option, not a requirement.
- Use compatible `protoc`, protobuf libraries, and gRPC generator versions.
  Cross-builds need generators that run on the build host and libraries for
  the target architecture.
- Prefer statically linking private gRPC/protobuf and their dependencies.
  Otherwise bundle libraries where licenses allow, with reliable loader paths
  (for example relative RPATHs on Linux/macOS).
- Keep the Driver Manager as a system dependency. OS/compiler runtimes still
  matter; do not promise a dependency-free binary.
- Check Linux position-independent static libraries and Windows runtime linkage.
  Scan bundled/static dependencies for vulnerabilities and plan updates.
- Export only the supported ODBC and `OjpXA*` APIs:
  - Linux: linker version script plus `-fvisibility=hidden`.
  - macOS: exported-symbols list.
  - Windows: `.def` file.

  This avoids clashes with another gRPC/protobuf in the app. Static linkage
  alone does not hide symbols.
- Download pinned googleapis sources with integrity checks, or include the
  needed proto files and licenses. Keep the canonical OJP protocol here.
- Use one release version for CMake, packages, driver reporting, and binary
  metadata. Keep prerelease labels separate where numeric versions are required.
- Include OJP/third-party notices, the XA header where needed, registration
  templates/instructions, and OS/runtime requirements.

### Test installed packages

- Test every published architecture on native runners where available, or
  cross-build and run on the target using a tested setup.
- Port POSIX process control, dynamic loading, and environment handling in tests
  to Windows. Use Windows driver registration, not unixODBC's `odbcinst.ini`.
- Install in a clean environment and load through the real Driver Manager.
  Build-tree libraries/development packages must not hide missing dependencies,
  bad loader paths, or registration errors.
- Against a real server, test connections, ANSI/Unicode, parameters,
  transactions, cleanup, TLS/mTLS, and applicable failover/XA behavior.
- Keep CSV-driven database tests; fail if required tests are skipped.
- Run all suites required for each claimed level/database on each platform.
  Linux results do not certify Windows/macOS.
- Test upgrades, installer removal/unregistration, and Windows x86/x64
  coexistence without deleting unrelated driver entries.

The driver build needs no Java. Server tests need Java 25, UTC, and JDBC
drivers. Do not add client-side pooling to packaging or tests.

## 6. Release Workflow

Start with a manually triggered workflow in the selected repository:

1. Validate the version and fixed OJP source revision; record source and
   packaging commits.
2. Build all required OS/architecture targets with pinned inputs.
3. Package and test the installed contents.
4. Generate license notices, SBOMs (dependency inventories), and build provenance
   (records of source and build inputs).
5. Sign binaries/installers and notarize macOS installers as configured.
   Generate final SHA-256 checksums after signing changes the files.
6. Publish one GitHub Release only when every required target passes.
   Keep incomplete results unpublished or in a draft.

Support dry runs, release approval, one release at a time, prereleases, and
publication retries. Never silently replace a published version's binaries.

Keep build jobs read-only; grant write/signing permissions only to protected
publication jobs. Use protected environments or a signing service for
credentials. Never put credentials in packages.

Later, the main OJP workflow could trigger distribution with the exact release
commit. Cross-repository triggers/writes may need a narrowly scoped GitHub App
or fine-grained token; do not assume the default token can do this. Give version
management one clear owner.

The current Maven/Docker release is one Ubuntu job with no native-package gate.
Adding uploads alone is not enough: if native packages must block an OJP
release, run their checks before any public publication. Otherwise document
their separate release schedule.

## 7. Delivery Stages

### Stage 1: GitHub Release archives

Proposed assets:

- `ojp-odbc-<version>-linux-x86_64.tar.gz` and `-linux-aarch64.tar.gz`
  (the `.so`, a sample `odbcinst.ini`, and a README)
- `ojp-odbc-<version>-windows-x64.zip` and `-windows-x86.zip` if selected
- `ojp-odbc-<version>-macos-x86_64.tar.gz` and `-macos-arm64.tar.gz`
- SHA-256 checksums, SBOMs, and provenance/signatures as configured

Include manual installation/registration instructions, system dependencies,
and signing status. A preview label may describe the new delivery channel,
not understate the client's existing features.

### Stage 2: Native installers and package managers

- **Windows:** MSI (WiX, or CPack with WiX) that writes the `ODBCINST.INI`
  registry keys; then publish to winget.
- **Linux:** `.deb` and `.rpm` packages (for example using CPack) whose install script
  runs `odbcinst -i -d` and whose removal script unregisters the driver.
  Publish to GitHub Releases first; an apt/yum repository can follow.
- **macOS:** Homebrew tap and/or signed, notarized `.pkg`, after testing
  installation and Driver Manager compatibility.
- **Docker:** an example base image with unixODBC and the OJP driver
  preinstalled, for containerized applications.

### Stage 3: Optional DSN and GUI/BI improvements

- `SQLConnect` with DSNs, reading `SERVER` and `DATABASE` from `odbc.ini` or
  the registry via `SQLGetPrivateProfileString`.
- Optionally, a Windows `ConfigDSN` setup dialog.
- Test intended BI tools, including the existing Unicode APIs.

These need not block archives: DSN-less use needs no setup dialog, and an
installer does not prove BI-tool compatibility.

---

## 8. Decisions Before Implementation

1. Separate distribution repository or module-local packaging?
2. Include Windows x86 for 32-bit Office/legacy users?
3. Include Linux ARM64 from the first release?
4. Which minimum OS, glibc/C++ runtime, and Driver Manager versions?
5. Shared OJP version or independent driver versions? Either needs a tested
   driver/server compatibility table and backward-compatible protocol.
6. Which static/bundled dependency, update, and license policy?
7. Archives first, or signed installers immediately? Arrange signing identities,
   notarization access, and secure key storage.
8. Must native checks block the main release, or are releases independent?

---

## 9. Source References

- [CMake build and install rules](../../ojp-client-cpp-odbc/CMakeLists.txt)
- [Release preset](../../ojp-client-cpp-odbc/CMakePresets.json)
- [Platform build, registration, and support status](../../ojp-client-cpp-odbc/README.md)
- [Driver implementation and version reporting](../../ojp-client-cpp-odbc/src/ojp_odbc_driver.cpp)
- [Public XA API](../../ojp-client-cpp-odbc/include/ojp_odbc_xa.h)
- [Current integration workflow](../../.github/workflows/main.yml)
- [Current Maven/Docker release workflow](../../.github/workflows/release.yml)
- [Client implementation level requirements](../multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md)

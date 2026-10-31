# OJP ODBC Driver Distribution Analysis

**Status:** 📋 Draft (October 2026)

## Executive Summary

**Question:** Users must currently clone the repository and compile
[`ojp-client-cpp-odbc`](../../ojp-client-cpp-odbc/README.md) themselves. What is
the best way to deliver the OJP ODBC driver, should it be a DLL, how are ODBC
drivers usually made available, and does the operating system matter?

**Answer:** The driver already *is* a shared library ("DLL of sorts"):
`add_library(ojp_odbc SHARED ...)` produces `libojp_odbc.so` (Linux),
`libojp_odbc.dylib` (macOS), or `ojp_odbc.dll` (Windows). The missing part is
**distribution**. Ship prebuilt, self-contained, per-OS/per-architecture
binaries plus registration with each platform's ODBC Driver Manager. That is
how every mainstream ODBC driver (Microsoft SQL Server, psqlODBC, MySQL
Connector/ODBC) is delivered.

**Confidence:** High for the overall direction (it mirrors industry practice).
Medium for the Windows porting effort, because the driver has never been built
on Windows.

---

## 1. Current State

| Area | Today | Impact |
|---|---|---|
| Release pipeline | `.github/workflows/release.yml` publishes only Maven Central artefacts and the Docker image. No C++ artefacts. | Users need CMake, gRPC, protobuf, unixODBC, and a googleapis checkout to build. |
| Dependencies | Dynamically linked against system gRPC/protobuf (CI uses Ubuntu `libgrpc++-dev`). | A binary only works on machines with the exact same library versions. |
| Symbol visibility | `CXX_VISIBILITY_PRESET default` exports every symbol. | On Linux, gRPC/protobuf/abseil symbols leak and can clash with other copies in the host process. On Windows (MSVC), nothing is exported without `__declspec(dllexport)` or a `.def` file. |
| Windows support | No `_WIN32` handling in `src/ojp_odbc_driver.cpp`; CI builds on Linux only. | Probably does not build or load on Windows as-is. |
| Connection methods | Only `SQLDriverConnect`; `SQLConnect` with a DSN returns `IM002`. ANSI only, no `W` (Unicode) entry points. | Most GUI/BI tools (Excel, Power BI, Tableau, DBeaver, LibreOffice) expect DSNs, and many call `W` functions. |
| Versioning | CMake `project(... VERSION 0.1.0)`, not tied to the OJP release version. | Users cannot match a driver build to a server version. |
| Proto inputs | Requires `GOOGLEAPIS_PROTO_DIR` for `google/type/date.proto` and `timeofday.proto`. | Extra, non-obvious build dependency. |

---

## 2. How ODBC Drivers Are Usually Delivered

An ODBC driver always has two parts:

1. **The driver library** (`.dll` / `.so` / `.dylib`).
2. **A registration entry** that tells the platform's ODBC Driver Manager
   where the library is (`odbcinst.ini` or the Windows registry).

Applications never link to the driver directly. They link to the Driver
Manager, which loads the driver named in the connection string or DSN, for
example `DRIVER={OJP};...`.

### Per operating system

| OS | Driver Manager | Driver registration | Typical delivery | Real-world examples |
|---|---|---|---|---|
| **Windows** | Built into the OS (`odbc32.dll`) | Registry: `HKLM\SOFTWARE\ODBC\ODBCINST.INI` (32-bit drivers under `WOW6432Node`) | Signed **MSI installer**; sometimes winget/Chocolatey | Microsoft ODBC Driver for SQL Server (`msodbcsql`), psqlODBC MSI, MySQL Connector/ODBC MSI |
| **Linux** | unixODBC (iODBC is rare) | `/etc/odbcinst.ini` or `$ODBCSYSINI` | **.deb / .rpm** packages from a vendor repository, plus a **tar.gz** for manual installs. Package post-install scripts run `odbcinst -i -d`. | `msodbcsql18` from packages.microsoft.com; `odbc-postgresql` in Debian/Ubuntu |
| **macOS** | No built-in manager is commonly used; unixODBC (Homebrew) or iODBC | `odbcinst.ini` (location depends on the manager) | **Notarized .pkg** and/or **Homebrew tap** | `brew tap microsoft/mssql-release` |

### OS-specific differences that matter

**Windows**
- **Bitness:** a 64-bit driver cannot be loaded by a 32-bit application (for
  example 32-bit Excel). Ship x64, consider x86, and ARM64 later. There are two
  separate ODBC Administrator tools (32-bit and 64-bit).
- **Code signing:** without an Authenticode signature, SmartScreen and
  corporate policies block the installer.
- **Setup UI:** the ODBC Administrator calls a `ConfigDSN` entry point (often
  in a separate setup DLL) to show a configuration dialog. Without one, DSNs
  must be created by the installer or by editing the registry.
- **Unicode:** the Driver Manager maps `W` calls to an ANSI-only driver, but
  non-ASCII data can be lost in the conversion.

**Linux**
- **glibc compatibility:** build on an old glibc (for example AlmaLinux 8,
  glibc 2.28, similar to the Python `manylinux` approach) so one binary works
  on most distributions.
- **Architectures:** x86_64 and aarch64.
- **Driver Manager:** unixODBC is the de facto standard; target it.

**macOS**
- **Signing:** Developer ID signing and notarization are required, or
  Gatekeeper blocks the `.dylib`.
- **Architectures:** ship a `universal2` binary (arm64 + x86_64).
- **Driver Manager split:** unixODBC vs iODBC is a real nuisance. Most vendors
  target unixODBC for Homebrew users.

---

## 3. Recommendation (Phased)

### Phase 0: Make the binary self-contained (prerequisite)

- Statically link gRPC, protobuf, abseil, re2, c-ares, zlib, and SSL, for
  example with vcpkg static triplets on all three operating systems. The
  result is one file with no external C++ dependencies.
- Export **only** the `SQL*` entry points:
  - Linux: linker version script plus `-fvisibility=hidden`.
  - macOS: exported-symbols list.
  - Windows: `.def` file.

  This prevents symbol clashes when the host process already loads another
  protobuf or gRPC (Python, Java via JNI, other drivers). It is the biggest
  stability risk.
- Port to Windows (`windows.h`, `SQL_API` calling convention, `.def` file) and
  add Windows and macOS CI build jobs.
- Vendor the two `google/type` proto files (Apache-2.0) to remove the
  `GOOGLEAPIS_PROTO_DIR` requirement.
- Tie the driver version to the OJP release version and ship a third-party
  license NOTICE.

### Phase 1: GitHub Release archives (cheapest, covers everyone)

Add a build matrix job to `release.yml` that attaches:

- `ojp-odbc-<version>-linux-x86_64.tar.gz` and `-linux-aarch64.tar.gz`
  (the `.so`, a sample `odbcinst.ini`, and a README)
- `ojp-odbc-<version>-windows-x64.zip` (plus x86 if 32-bit Office matters)
- `ojp-odbc-<version>-macos-universal.tar.gz`
- SHA-256 checksums and an SBOM, optionally with cosign/Sigstore signatures

### Phase 2: Native installers and package managers

- **Windows:** MSI (WiX, or CPack with WiX) that writes the `ODBCINST.INI`
  registry keys; then publish to winget.
- **Linux:** `.deb` and `.rpm` packages (nfpm or CPack) whose install script
  runs `odbcinst -i -d` and whose removal script unregisters the driver.
  Attach them to the GitHub Release first; hosting an apt/yum repository can
  come later.
- **macOS:** Homebrew tap (`brew install open-j-proxy/tap/ojp-odbc`), and later
  a notarized `.pkg`.
- **Docker:** an example base image with unixODBC and the OJP driver
  preinstalled, for containerized applications.

### Phase 3: Features needed by GUI and BI tools

- `SQLConnect` with DSNs, reading `SERVER` and `DATABASE` from `odbc.ini` or
  the registry via `SQLGetPrivateProfileString`.
- Unicode (`W`) entry points, which matter most on Windows.
- Optionally, a Windows `ConfigDSN` setup dialog.

Without Phase 3, packaging mainly helps C++ developers, not BI-tool users.

---

## 4. Concerns and Open Questions

- **Signing costs and key management:** Windows Authenticode and Apple
  Developer ID certificates cost money and need secure key handling in CI
  secrets. Unsigned artefacts are acceptable for Linux archives, not for
  end-user Windows or macOS installers.
- **Maturity:** the driver is at L1–L3 (no transactions, LOBs, or multinode;
  see [Client Implementation Levels](../multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md)).
  Ship Phase 1 labelled **preview** now; hold MSI and Homebrew until at least
  L4 (transactions) works, so polished installers do not suggest more maturity
  than exists.
- **Compatibility matrix:** document which OJP server versions each driver
  release supports. Driver and server can be upgraded independently, so proto
  changes must remain backward compatible.
- **Target users:** C++ developers on Linux benefit from Phase 1 immediately.
  Windows BI users benefit only after Phases 2 and 3. Knowing the main
  audience decides the order.

---

## 5. Summary

| Question | Answer |
|---|---|
| Should it be a DLL? | It already is a shared library. The work is making it self-contained, exporting only ODBC symbols, and building it for each OS. |
| How are ODBC drivers usually delivered? | Prebuilt per-OS binaries plus Driver Manager registration: MSI on Windows, deb/rpm (and tar.gz) on Linux, pkg/Homebrew on macOS. |
| Does the OS matter? | Yes: Driver Manager and registration mechanism, 32/64-bit on Windows, glibc baseline and architecture on Linux, signing and notarization on Windows and macOS, and universal binaries on macOS. |
| First step | Self-contained static builds with restricted symbol exports, published as preview archives on GitHub Releases. |

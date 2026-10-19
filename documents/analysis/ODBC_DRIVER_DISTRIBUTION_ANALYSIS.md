# OJP ODBC Driver Distribution Analysis

**Status:** Proposal — release workflow not implemented (19 October 2026)

## Executive Summary

**Goal:** Deliver prebuilt releases of
[`ojp-client-cpp-odbc`](../../ojp-client-cpp-odbc/README.md) for Windows,
Linux, and macOS, with an explicit architecture and compatibility policy.
This document records the analysis only; it does not introduce workflows,
packages, or a new repository.

**Recommendation:** Keep the client source, CMake build, and integration tests
in `Open-J-Proxy/ojp`. Use a separate distribution repository under the same
organization, provisionally named `Open-J-Proxy/ojp-odbc`, for packaging,
release automation, installation documentation, and GitHub Release assets.
Build from an exact OJP tag resolved to a commit, not a moving branch.

Start with archives for Windows x64/x86, Linux x86_64/ARM64, and macOS
x86_64/ARM64, subject to the target decisions below. Native installers can
follow. Do not commit compiled packages into either repository.

**Confidence:** High for the distribution architecture; Medium for portability
effort. Shared-library builds and Linux integration suites already exist,
but Windows/macOS instructions are not evidence of platform qualification.

---

## 1. Current State

| Area | Today | Impact |
|---|---|---|
| Release pipeline | `.github/workflows/release.yml` publishes only Maven Central artefacts and the Docker image. No C++ artefacts. | Users need CMake, gRPC, protobuf, unixODBC, and a googleapis checkout to build. |
| Build/install | C++17 shared library, CMake release preset, and install rules exist. The release preset disables tests. | Produces `.dll`, `.so`, or `.dylib`, but installation alone is not release qualification. |
| Dependencies | CMake links gRPC/protobuf targets; Linux CI installs system development packages. Install rules do not bundle third-party runtime libraries. | Published binaries need a controlled dependency strategy and loader-path validation. |
| Symbol visibility | `CXX_VISIBILITY_PRESET default` and `WINDOWS_EXPORT_ALL_SYMBOLS ON`; XA declarations have Windows export annotations. | Replace broad exports with an explicit supported API surface and check for dependency-symbol leakage. |
| Platform support | Windows-specific UTC conversion and cross-platform build instructions exist; ODBC integration jobs in `main.yml` run on Ubuntu. | Windows and macOS need actual build and installed-package qualification. |
| Connection methods | ANSI and Unicode entry points exist. Standard connections are DSN-less; there is no Windows installer or setup-dialog DLL. | Packaging is useful now; DSN configuration and BI-tool certification are separate work. |
| Versioning | CMake uses `0.1.0`; `SQL_DRIVER_VER` independently reports `00.01.0000`. | A single version source and driver/server compatibility policy are needed. |
| Proto inputs | Requires `GOOGLEAPIS_PROTO_DIR` for `google/type/date.proto` and `timeofday.proto`. | Extra, non-obvious build dependency. |

---

## 2. How ODBC Drivers Are Usually Delivered

An ODBC driver installation normally has two parts:

1. **The driver library** (`.dll` / `.so` / `.dylib`).
2. **A registration entry** that tells the platform's ODBC Driver Manager
   where the library is (`odbcinst.ini` or the Windows registry).

Ordinary ODBC applications link to the Driver Manager, which loads the driver
named in the connection string or DSN, for example `DRIVER={OJP};...`.
OJP also exposes nonstandard XA entry points; those must remain available
when tightening exports.

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
- **Code signing:** plan Authenticode signing for public installers and DLLs.
  Unsigned downloads can encounter SmartScreen warnings or enterprise policy
  restrictions; signing is not a universal prerequisite for loading a DLL.
- **Setup UI:** the ODBC Administrator calls a `ConfigDSN` entry point (often
  in a separate setup DLL) to show a configuration dialog. Without one, DSNs
  must be created by the installer or by editing the registry.
- **ABI:** verify `SQL_API` calling conventions, undecorated entry-point
  lookup on x86, Unicode behavior, and matching Visual C++ runtime deployment.

**Linux**
- **glibc compatibility:** choose a supported baseline distribution and
  verify both glibc and C++ runtime requirements on the oldest supported
  system. Building on the newest runner alone does not establish portability.
- **musl:** Alpine Linux is a separate compatibility target, not covered by
  a glibc archive.
- **Architectures:** x86_64 and aarch64.
- **Driver Manager:** unixODBC is the de facto standard; target it.

**macOS**
- **Signing:** plan Developer ID signing and notarization for public installer
  distribution. Gatekeeper behavior depends on packaging, quarantine, and the
  host application; do not assume every unsigned `.dylib` is always blocked.
- **Architectures:** initially ship separate arm64 and x86_64 archives.
  A later universal binary requires both slices in all bundled dependencies.
- **OS baseline:** choose and test an explicit minimum deployment version
  per architecture.
- **Driver Manager split:** unixODBC vs iODBC is a real nuisance. Most vendors
  target unixODBC for Homebrew users.

---

## 3. Repository and Package Location

| Option | Advantages | Costs |
|---|---|---|
| Packaging folder inside the existing module | Atomic source/build/packaging changes; existing CI and permissions; no cross-repository trigger. | Native releases share the main project's release surface. |
| Separate distribution repository, source retained here | Dedicated download location, native release policy, signing permissions, and installation documentation. | Must pin source revisions and coordinate packaging changes and release triggers across repositories. |
| Move client source to a new repository | Independent source ownership and lifecycle. | Requires extracting shared protobuf inputs, reconnecting server integration tests, and maintaining compatibility across repositories. |

Prefer the second option initially. The proposed repository name is not an
existing or approved resource. Source extraction is not necessary to publish
native packages.

If the single-repository option is selected instead, place checked-in packaging
definitions under `ojp-client-cpp-odbc/packaging/`, with platform-specific
subdirectories. Put generated archives/installers in an ignored output
directory, such as `ojp-client-cpp-odbc/build/packages/`.

In either model, compiled downloads belong in GitHub Releases, not Git history.
A distribution repository should record the OJP source commit, packaging
revision, and dependency/toolchain versions for each published release.

## 4. Architecture Matrix

“64-bit” is not a complete target: x86_64 and ARM64 require different binaries.
The driver, application, and loaded dependencies must match architectures.
The OJP server is a separate process and need not match the client's bitness.

| OS | Initial proposed targets | 32-bit policy |
|---|---|---|
| Windows | x64 and x86 | Include x86 if 32-bit Office or legacy applications are in scope; test in a real 32-bit client process on supported Windows. |
| Linux | x86_64 and ARM64/aarch64 | Defer x86 unless a concrete requirement justifies its dependency/toolchain and testing matrix. |
| macOS | Intel x86_64 and Apple Silicon arm64 | Do not target 32-bit: modern macOS does not run 32-bit applications. |

Windows ARM64, Linux musl, Linux x86, and macOS universal packaging are later
options, not implied by these initial targets. Select explicit runner images
and verify their architecture availability before implementation.

## 5. Required Build and Qualification Work

### Reproducible, distributable binaries

- Pin compilers, SDKs, dependency versions, and googleapis inputs. A pinned
  package-manager baseline, such as vcpkg, is an option rather than a requirement.
- Ensure `protoc`, its libraries, and the gRPC code-generation plugin are
  compatible. Cross-compilation needs host-runnable generators and
  target-architecture libraries.
- Prefer static linkage of private gRPC/protobuf and transitive dependencies
  where practical. Otherwise bundle permitted runtime libraries and configure
  reliable loader paths, such as relative RPATHs on Linux and macOS.
- Keep the platform Driver Manager as a system dependency. Do not promise
  a completely dependency-free binary: OS and compiler runtimes still matter.
- Verify Linux position-independent static libraries and Windows runtime
  linkage choices. Scan bundled/static dependencies and plan security updates.
- Export only the supported ODBC and `OjpXA*` APIs:
  - Linux: linker version script plus `-fvisibility=hidden`.
  - macOS: exported-symbols list.
  - Windows: `.def` file.

  This reduces collisions with another gRPC/protobuf already loaded by the
  application. Static linkage alone does not provide symbol isolation.
- Fetch pinned googleapis sources with integrity checks, or vendor the required
  proto inputs with their licenses. Neither approach requires moving the
  canonical OJP protocol out of this repository.
- Generate CMake/package versions, reported driver version, and platform
  binary metadata from one release identity. Handle prerelease labels separately
  where platform metadata requires numeric versions.
- Include OJP and third-party license notices, the XA header where applicable,
  registration instructions/templates, and supported OS/runtime requirements.

### Installed-package qualification

- Build and test every published architecture using matching native runners
  where available, or a validated cross-build and target-execution setup.
- Adapt POSIX-specific process control, dynamic loading, and environment
  handling in the test harness for Windows.
- Use Windows Driver Manager registration for Windows tests; the existing
  generated `odbcinst.ini` is a unixODBC mechanism.
- Install into a clean test environment and load through the real Driver
  Manager. Do not let build-tree libraries or development packages hide missing
  dependencies, bad loader paths, or registration problems.
- Exercise connections, ANSI/Unicode, parameters, transactions, resource
  cleanup, TLS/mTLS, and applicable failover/XA behavior against a real server.
- Preserve CSV-driven database parameterization. Required release tests must
  not silently pass through opt-in skips.
- Run the suites required by each claimed implementation level and database.
  Linux coverage does not automatically certify Windows or macOS.
- Test upgrades, uninstall/unregistration for installers, and coexistence of
  Windows x86/x64 installations without removing unrelated driver entries.

The driver build itself does not require Java. Real-server tests require
Java 25 for OJP, a server started with UTC, and the required JDBC drivers.
Do not add client-side pooling as part of packaging or test setup.

## 6. Release Workflow

Start with manual dispatch in the selected repository. For the separate
distribution repository, the workflow should:

1. Validate the release version and an immutable OJP source revision. Record
   the resolved source commit and packaging revision.
2. Build the required OS/architecture matrix using pinned inputs.
3. Stage packages and test their installed contents.
4. Produce SHA-256 checksums, SBOMs, license notices, and build provenance.
5. Sign binaries/installers where configured; notarize macOS installers where
   applicable. Compute final checksums after signing changes package bytes.
6. Collect all required targets and publish one GitHub Release only after every
   required target passes. Keep incomplete results unpublished or in a draft.

Provide dry-run builds, protected release approval, release concurrency
controls, prerelease handling, and retryable publication. Retries must not
silently replace an already-published version with different binaries.

Keep normal build jobs read-only and grant release-write/signing permissions
only to protected publication jobs. Store credentials in protected environments
or use an appropriate signing service; never include them in packages.

Automatic coordination with the main OJP release can follow manual publication.
For example, the main workflow could dispatch the distribution workflow with
the exact release commit. Do not assume the default workflow token can trigger
or write to another repository; use a narrowly scoped GitHub App or fine-grained
token if needed. Avoid duplicate/conflicting version ownership.

The current Maven/Docker release runs as a single Ubuntu job and publishes
before any native-package gate exists. Integrating native builds therefore
requires explicit orchestration, not just appending uploads. If native packages
must gate the whole OJP release, complete their required checks before public
publication; otherwise document their independent release lifecycle.

## 7. Delivery Stages

### Stage 1: GitHub Release archives

Proposed assets:

- `ojp-odbc-<version>-linux-x86_64.tar.gz` and `-linux-aarch64.tar.gz`
  (the `.so`, a sample `odbcinst.ini`, and a README)
- `ojp-odbc-<version>-windows-x64.zip` and `-windows-x86.zip` if selected
- `ojp-odbc-<version>-macos-x86_64.tar.gz` and `-macos-arm64.tar.gz`
- SHA-256 checksums, SBOMs, and provenance/signatures as configured

Archives should support manual installation with explicit Driver Manager
registration instructions. State any remaining system dependencies and whether
artifacts are signed. A preview label can distinguish a new distribution
channel, but should not misrepresent the current client capabilities.

### Stage 2: Native installers and package managers

- **Windows:** MSI (WiX, or CPack with WiX) that writes the `ODBCINST.INI`
  registry keys; then publish to winget.
- **Linux:** `.deb` and `.rpm` packages (for example using CPack) whose install script
  runs `odbcinst -i -d` and whose removal script unregisters the driver.
  Attach them to the GitHub Release first; hosting an apt/yum repository can
  come later.
- **macOS:** a Homebrew tap and/or signed, notarized `.pkg`, once installation
  and Driver Manager compatibility are qualified.
- **Docker:** an example base image with unixODBC and the OJP driver
  preinstalled, for containerized applications.

### Stage 3: Optional DSN and GUI/BI improvements

- `SQLConnect` with DSNs, reading `SERVER` and `DATABASE` from `odbc.ini` or
  the registry via `SQLGetPrivateProfileString`.
- Optionally, a Windows `ConfigDSN` setup dialog.
- Application-specific qualification for intended BI tools, including existing
  Unicode entry points.

These are separate from archive publication. A DSN-less initial release does
not require a setup-dialog DLL, and installers alone do not establish BI-tool
compatibility.

---

## 8. Decisions Before Implementation

1. Approve a separate distribution repository or retain module-local packaging.
2. Confirm whether Windows x86 is required for 32-bit Office/legacy users.
3. Confirm Linux ARM64 is part of the initial release.
4. Choose minimum OS versions, glibc/C++ runtime baselines, and Driver Manager
   support. Initially target unixODBC on Linux/macOS, not mixed iODBC builds.
5. Align driver versions with OJP or version independently. Either way publish
   a tested driver/server compatibility table and preserve protocol compatibility.
6. Choose static/private dependency bundling and its update/license policy.
7. Decide whether archives suffice initially or signed installers are required;
   arrange signing identities, notarization access, and secure key management.
8. Decide whether native packages gate the main OJP release or publish on an
   independent schedule.

---

## 9. Summary and Source References

| Question | Answer |
|---|---|
| Should it be a DLL? | It already is a shared library. The work is controlled dependencies, explicit ODBC/XA exports, platform qualification, and distribution. |
| How are ODBC drivers usually delivered? | Prebuilt per-OS binaries plus Driver Manager registration: MSI on Windows, deb/rpm (and tar.gz) on Linux, pkg/Homebrew on macOS. |
| Does the OS matter? | Yes: architecture, ABI, registration, runtime baselines, loader paths, signing, and installed-package tests differ. |
| Where should packages live? | GitHub Release assets, preferably in a dedicated distribution repository; keep source and integration tests here initially. |
| First step | Confirm repository/target policy, then qualify reproducible builds before publishing archives. |

Repository evidence for this assessment:

- [CMake build and install rules](../../ojp-client-cpp-odbc/CMakeLists.txt)
- [Release preset](../../ojp-client-cpp-odbc/CMakePresets.json)
- [Platform build, registration, and support status](../../ojp-client-cpp-odbc/README.md)
- [Driver implementation and version reporting](../../ojp-client-cpp-odbc/src/ojp_odbc_driver.cpp)
- [Public XA API](../../ojp-client-cpp-odbc/include/ojp_odbc_xa.h)
- [Current integration workflow](../../.github/workflows/main.yml)
- [Current Maven/Docker release workflow](../../.github/workflows/release.yml)
- [Client implementation level requirements](../multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md)

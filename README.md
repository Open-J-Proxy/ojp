<img referrerpolicy="no-referrer-when-downgrade" src="https://static.scarf.sh/a.png?x-pxid=d0bcc022-901a-411e-a8de-ac19a21576dd" />

# Open J Proxy

![Release](https://img.shields.io/github/v/release/Open-J-Proxy/ojp?include_prereleases)
[![Main CI](https://github.com/Open-J-Proxy/ojp/actions/workflows/main.yml/badge.svg)](https://github.com/Open-J-Proxy/ojp/actions/workflows/main.yml)
[![Spring Boot/Micronaut/Quarkus Integration](https://github.com/Open-J-Proxy/ojp-framework-integration/actions/workflows/main.yml/badge.svg)](https://github.com/Open-J-Proxy/ojp-framework-integration/actions/workflows/main.yml)
[![License](https://img.shields.io/github/license/Open-J-Proxy/ojp.svg)](https://raw.githubusercontent.com/Open-J-Proxy/ojp/master/LICENSE)

[![security status](https://www.meterian.com/badge/gh/Open-J-Proxy/ojp/security?branch=main)](https://www.meterian.com/report/gh/Open-J-Proxy/ojp)
[![stability status](https://www.meterian.com/badge/gh/Open-J-Proxy/ojp/stability?branch=main)](https://www.meterian.com/report/gh/Open-J-Proxy/ojp)

**A smart, open-source database control plane** — delivered as a Type 3 JDBC driver, early language-native clients, and a Layer 7 proxy server. OJP sits between your applications and your relational databases and provides backpressure, rich observability, client-side reactive throttling, slow-vs-fast query segregation, and load balancing / failover.

<a id="overview"></a>
## System picture

This component map shows who communicates with whom, **not the sequence of a query**. The server owns the real database connections; applications use the OJP JDBC driver.

```mermaid
flowchart LR
    app["Application"] <-->|"JDBC calls and results"| driver["OJP JDBC driver"]
    driver <-->|"gRPC requests and responses"| server["OJP server"]
    server <-->|"Server-managed JDBC connections"| db["Database"]
```
<a id="value-proposition"></a>
OJP helps elastic applications avoid connection storms, gives operators visibility into database pressure, and supports load-aware routing and optional slow/fast query segregation. See the [problem and solution](documents/targeted-problem/README.md) for context.

## Understand what happens

Start with [executeQuery](documents/designs/EXECUTE_QUERY_FLOW.md), or choose an operation in the **[Simplified Flow Diagrams](documents/designs/MAIN_FLOWS.md)**.
Follow the normal path, read its essential assumptions, then explore specialised scenarios or source checkpoints only when your next question needs them.

## Requirements

- **OJP JDBC Driver**: Java 11 or higher
- **OJP Server**: Java 25 or higher
- **Tested through JDBC**: PostgreSQL, MySQL, MariaDB, Oracle, SQL Server, DB2, and H2.
- **Early non-Java clients** currently target single-endpoint H2 L1; they are not JDBC-feature-equivalent or production-ready. See [clients and tested coverage](documents/README.md#multi-language-clients).

## Quick Start

This Docker example runs from a cloned repository on a Linux host with an existing database accessible to the server.

> **Disable application-level connection pooling** (including HikariCP). OJP manages backend pools on the server. [Framework guides](documents/java-frameworks/README.md) explain integration and runtime dependencies.

### 1. Start OJP Server (Docker)

> **JDBC drivers are not bundled.** Download and mount them; proprietary drivers require separate installation. See [external drivers and libraries](documents/configuration/DRIVERS_AND_LIBS.md).

```bash
# Download drivers first
mkdir -p ojp-libs
cd ojp-server
bash download-drivers.sh ../ojp-libs
cd ..

# Run with drivers mounted
docker run --rm -d \
  --network host \
  -v "$(pwd)/ojp-libs:/opt/ojp/ojp-libs" \
  -e JAVA_TOOL_OPTIONS="-Duser.timezone=UTC" \
  rrobetti/ojp:1.0.0
```

### 2. Add OJP JDBC Driver to your project
```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 3. Update your JDBC URL
Replace your existing connection URL by prefixing with `ojp[host:port]_`:

```java
// Before (PostgreSQL example)
"jdbc:postgresql://localhost:5432/mydb"

// After
"jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb"
```
Use the ojp driver: `org.openjproxy.jdbc.Driver`

Supply database credentials through your application's secure configuration. For a full walkthrough and first query, see the [Quick Start Guide](documents/ebook/part1-chapter3-quickstart.md).

<a id="alternative-setup-executable-jar-no-docker"></a>
Without Docker, use the [Executable JAR Setup Guide](documents/runnable-jar/README.md); always start the server with `-Duser.timezone=UTC`.

## Documentation

### Choose your next step

<a id="further-documents"></a>
| I want to… | Start here |
|---|---|
| Browse all documentation | [Documentation hub](documents/README.md) |
| Integrate an application | [Framework guides](documents/java-frameworks/README.md) |
| Deploy OJP | [Docker](documents/configuration/DOCKER_DEPLOYMENT.md) · [Runnable JAR](documents/runnable-jar/README.md) |
| Look up settings | [JDBC reference](documents/configuration/ojp-jdbc-configuration.md) · [Server reference](documents/configuration/ojp-server-configuration.md) |
| Run and monitor production | [Production guide](documents/monitoring/PRODUCTION_DEPLOYMENT_GUIDE.md) · [Telemetry](documents/telemetry/README.md) |
| Configure high availability | [Multinode guide](documents/multinode/README.md) |

<a id="mixed-oltp--olap-workloads--enable-slow-query-segregation"></a>
<a id="mixed-oltp--olap-workloads-enable-slow-query-segregation"></a>
For mixed OLTP/OLAP workloads, see [Slow Query Segregation](documents/designs/SLOW_QUERY_SEGREGATION.md); it is disabled by default. The SQL enhancer is experimental, disabled by default, and not recommended for production.

<a id="contributing--developer-guide"></a>
To contribute, start with [CONTRIBUTING.md](CONTRIBUTING.md) and [source setup and testing](documents/code-contributions/setup_and_testing_ojp_source.md).

<a id="📘-free-open-j-proxy-ebook"></a>
<a id="-free-open-j-proxy-ebook"></a>
Learn through the [ebook reading paths](documents/ebook/README.md#reading-paths), or [download the free ebook](https://openjproxy.com/register-to-ojp-email-list.html).

## Project information

<a id="vision"></a>
<a id="roadmap"></a>
[Roadmap and vision](ROADMAP.md) · [Support policy](SUPPORT.md) · [Releases](https://github.com/Open-J-Proxy/ojp/releases) · [License](LICENSE) · [Contributor recognition](documents/contributor-badges/contributor-recognition-program.md)

**Community:** [Website](https://openjproxy.com) · [LinkedIn](https://www.linkedin.com/company/open-j-proxy) · [Discord](https://discord.gg/J5DdHpaUzu)

## Partners

Thanks to [Comunidade Brasil JUG](https://www.linkedin.com/in/devsjava/), [Switcher API](https://github.com/switcherapi), [Meterian](https://www.meterian.io/), [CyberJAR](https://www.youtube.com/@cbrjar), [Java Challengers](https://javachallengers.com/career-diagnosis), and [OmniFish](https://omnifish.ee) for supporting the community.

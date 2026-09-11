# Spring Boot

OJP provides a Spring Boot Starter (`spring-boot-starter-ojp`) that auto-configures the OJP JDBC driver
in Spring Boot 4.x projects, making integration as simple as adding a dependency and setting a single
connection URL property.

> **Requirements:** Spring Boot 4.x (Java 17+). The starter also works with Spring Boot 3.x.
> For Java 11 projects, follow the [manual configuration](#manual-configuration-spring-boot-3x--java-11) guide below.

---

## Quickstart with the Spring Boot Starter (Recommended)

### 1. Add the OJP starter

Replace any existing `spring-boot-starter-jdbc` in your `pom.xml` with the OJP starter:

```xml
<!-- Add the OJP Spring Boot Starter (replaces spring-boot-starter-jdbc) -->
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>spring-boot-starter-ojp</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
```

> **Why replace the JDBC starter?** OJP manages connection pooling centrally on the proxy server.
> The OJP Spring Boot Starter includes the necessary Spring JDBC support without a local connection
> pool, and automatically configures `SimpleDriverDataSource` to create connections on demand.

### 2. Set your connection URL in `application.yml` or `application.properties`

> **No `ojp.properties` file needed!** When you use the Spring Boot Starter, all OJP configuration
> goes directly into your `application.yml` or `application.properties`. The starter automatically
> bridges every `ojp.*` property from Spring's environment to the OJP JDBC driver — no separate
> `ojp.properties` file is required.

**`application.yml` (recommended):**

```yaml
spring:
  datasource:
    url: jdbc:ojp[localhost:1059]_postgresql://user@localhost/mydb
    username: user
    password: secret
```

**`application.properties` (alternative):**

```properties
# OJP connection URL: jdbc:ojp[<ojp-host>:<ojp-port>]_<actual-driver-url>
spring.datasource.url=jdbc:ojp[localhost:1059]_postgresql://user@localhost/mydb
spring.datasource.username=user
spring.datasource.password=secret
```

That is all! The starter automatically sets:
- `spring.datasource.driver-class-name=org.openjproxy.jdbc.Driver`
- `spring.datasource.type=org.springframework.jdbc.datasource.SimpleDriverDataSource`

> See [OJP JDBC Configuration](../../configuration/ojp-jdbc-configuration.md) for the full list of
> settings.

### 3. JPA / Hibernate DDL tip: surface schema errors eagerly

When Hibernate is configured to create or update the schema (`spring.jpa.hibernate.ddl-auto=create`,
`create-drop`, or `update`), any DDL failure (e.g. a table that could not be created) is **silently
swallowed by default**. The error only surfaces later, at runtime, when the affected table is first
accessed — which can make it very hard to diagnose.

Set the following property to make Hibernate halt immediately and print the error as soon as a DDL
operation fails:

```properties
spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true
```

Or in `application.yml`:

```yaml
spring:
  jpa:
    properties:
      hibernate:
        hbm2ddl:
          halt_on_error: true
```

> **Note:** This is standard **Hibernate behaviour**, not something specific to OJP. It applies
> regardless of which JDBC driver or connection proxy you are using. It is especially useful during
> development and CI, where catching schema problems early prevents confusing runtime failures.

---

### 4. Optional: Fine-tune the OJP server-side connection pool

OJP's connection pool lives on the proxy server. You can tune it directly from `application.yml`:

```yaml
ojp:
  connection:
    pool:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
  grpc:
    max-inbound-message-size: 16777216
```

Or in `application.properties`:

```properties
# OJP server-side connection pool settings (forwarded to the OJP server via gRPC)
ojp.connection.pool.maximum-pool-size=20
ojp.connection.pool.minimum-idle=5
ojp.connection.pool.connection-timeout=30000
ojp.connection.pool.idle-timeout=600000
ojp.connection.pool.max-lifetime=1800000

# gRPC transport settings (increase for large LOB data)
ojp.grpc.max-inbound-message-size=16777216
```

#### Property naming: kebab-case and camelCase are equivalent

Spring Boot (and the OJP starter) normalises property names, so **kebab-case and camelCase spellings are identical** — use whichever style your team finds most readable:

```yaml
# These two spellings are exactly equivalent — choose whichever your team prefers:

# kebab-case (Spring Boot convention, recommended for application.yml)
ojp:
  multinode:
    retry-attempts: -1

# camelCase (matches the underlying OJP property names)
# ojp:
#   multinode:
#     retryAttempts: -1
```

```properties
# Likewise in application.properties:
ojp.multinode.retry-attempts=-1   # same as below
ojp.multinode.retryAttempts=-1
```

> **Recommendation:** Pick the style that is most readable for your team and apply it consistently
> across your project. Kebab-case is the Spring Boot convention and is generally preferred in
> `application.yml`, while camelCase matches the underlying OJP property names more closely.

#### Per-datasource settings vs. global settings

Not all OJP properties support a datasource name prefix. Understanding the boundary is
important when you use multiple named datasources.

| Setting group | Supports `dsName.` prefix? | Examples |
|---|---|---|
| `ojp.connection.pool.*` | ✅ Yes | `webapp.ojp.connection.pool.maximum-pool-size` |
| `ojp.xa.connection.pool.*` | ✅ Yes | `webapp.ojp.xa.connection.pool.max-total` |
| `ojp.grpc.*` | ✅ Yes | `webapp.ojp.grpc.max-inbound-message-size` |
| `ojp.health.check.*` | ❌ No — global only | `ojp.health.check.interval` |
| `ojp.redistribution.*` | ❌ No — global only | `ojp.redistribution.enabled` |
| `ojp.loadaware.selection.enabled` | ❌ No — global only | — |
| `ojp.multinode.*` | ❌ No — global only | `ojp.multinode.retry-attempts` |

The datasource name is always provided in the **JDBC URL** (not as a prefix in `application.yml`).
Pool settings are then prefixed with that name in `application.yml` to target the correct pool:

```yaml
spring:
  datasource:
    url: jdbc:ojp[localhost:1059(webapp)]_postgresql://localhost:5432/mydb
    username: myuser
    password: mypassword

# ✅ Per-datasource pool settings — prefixed with the datasource name "webapp"
webapp:
  ojp:
    connection:
      pool:
        maximum-pool-size: 60
        minimum-idle: 15
        connection-timeout: 5000

# ❌ Global settings — never prefixed; they apply to the entire cluster
ojp:
  health:
    check:
      interval: 5s
      threshold: 5s
      timeout: 5s
  redistribution:
    enabled: true
  loadaware:
    selection:
      enabled: true
  multinode:
    retry-attempts: -1
    retry-delay-ms: 5000
```

> **Tip — Named datasource:** To use a named datasource pool configuration on the server, embed the
> name directly in the JDBC URL using parentheses:
> `spring.datasource.url=jdbc:ojp[localhost:1059(myApp)]_postgresql://...`

---

### Complete `application.yml` Reference

A fully annotated reference file covering every OJP setting is available at:
[`application-ojp-example.yml`](application-ojp-example.yml)

Below is a condensed version showing all sections at a glance:

```yaml
spring:
  datasource:
    url: jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb
    username: myuser
    password: mypassword
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        hbm2ddl:
          halt_on_error: true   # surface schema errors at startup, not at runtime

# ── Connection pool (✅ per-datasource with prefix) ──────────────────────────
ojp:
  connection:
    pool:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 10000
      idle-timeout: 600000
      max-lifetime: 1800000
      default-transaction-isolation: READ_COMMITTED

  # ── XA pool (✅ per-datasource with prefix) ───────────────────────────────
  xa:
    connection:
      pool:
        max-total: 20
        min-idle: 5
        connection-timeout: 20000

  # ── gRPC transport (✅ per-datasource with prefix) ────────────────────────
  grpc:
    max-inbound-message-size: 16777216   # bytes; increase for large LOBs

  # ── Multinode retry (❌ global) ───────────────────────────────────────────
  multinode:
    retry-attempts: -1        # -1 = infinite
    retry-delay-ms: 5000

  # ── Health check (❌ global) ───────────────────────────────────────────────
  health:
    check:
      interval: 5s
      threshold: 5s
      timeout: 5s

  # ── Connection redistribution (❌ global) ─────────────────────────────────
  redistribution:
    enabled: true
    idle-rebalance-fraction: 1.0
    max-close-per-recovery: 100

  # ── Load-aware server selection (❌ global) ───────────────────────────────
  loadaware:
    selection:
      enabled: true   # false = round-robin

logging:
  level:
    org.openjproxy.grpc.client: INFO   # DEBUG for detailed health-check output
```

### 5. Start the OJP Server

The OJP proxy server must be running and reachable at the host/port in the JDBC URL.

```bash
# Docker (recommended)
docker run -d --network host \
  -v $(pwd)/ojp-libs:/opt/ojp/ojp-libs \
  rrobetti/ojp:latest
```

For full server setup options see the [Server Configuration Guide](../../configuration/ojp-server-configuration.md).

---

## Manual Configuration (Spring Boot 3.x / Java 11)

If you cannot use the starter (e.g., Java 11 projects), follow these steps:

### 1. Add the JDBC driver dependency

```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
```

### 2. Remove the local connection pool

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
    <exclusions>
        <!--When using OJP proxied connection pool the local pool needs to be removed -->
        <exclusion>
            <groupId>com.zaxxer</groupId>
            <artifactId>HikariCP</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

### 3. Configure `application.properties`

```properties
spring.datasource.url=jdbc:ojp[localhost:1059]_h2:~/test
spring.datasource.driver-class-name=org.openjproxy.jdbc.Driver
# SimpleDriverDataSource creates and closes connections on demand without local pooling
spring.datasource.type=org.springframework.jdbc.datasource.SimpleDriverDataSource
```

The OJP URL pattern is `jdbc:ojp[host:port]_<actualDriver>://...`. You just need to prepend
`ojp[host:port]_` immediately after `jdbc:` in your existing URL.

---

## Multinode Health Check Configuration

When using OJP in a [multinode setup](../../multinode/README.md), the health checker is **enabled by
default**. You can configure or disable it directly in `application.yml`:

```yaml
ojp:
  redistribution:
    enabled: true              # set to false to disable the health checker entirely
  health:
    check:
      interval: 5s             # how often to probe a failed server (default: 5s)
      threshold: 5s            # how long a server must stay healthy before recovery (default: 5s)
      timeout: 5s              # gRPC deadline per probe (default: 5s)
```

To **disable** the periodic health checker completely:

```yaml
ojp:
  redistribution:
    enabled: false
```

### Viewing Health Check Logs

Health check activity is logged under the `org.openjproxy.grpc.client` package. Enable it in
`application.yml`:

```yaml
logging:
  level:
    org.openjproxy.grpc.client: DEBUG
```

- `INFO` — server recovery/failure and redistribution events
- `DEBUG` — individual probe results for each health check cycle

For full details on health check options see the [Multinode Configuration Guide](../../multinode/README.md).

---

## Runtime Dependencies

The OJP JDBC driver marks two dependencies as `provided`, meaning they are **not** bundled
inside the JAR and must be present on the classpath at runtime.

| Provided dependency | Supplied automatically by Spring Boot? |
|---|---|
| `org.slf4j:slf4j-api` | ✅ Yes — via `spring-boot-starter-logging` (Logback) |
| `jakarta.transaction:jakarta.transaction-api` | ✅ Yes — via `spring-tx` (included in `spring-boot-starter-jdbc`) |

No extra Maven dependencies are needed for Spring Boot applications. Both APIs are always on
the classpath when you use `spring-boot-starter-jdbc` or `spring-boot-starter-ojp`.

> **Classpath isolation:** Since OJP 0.5.x all third-party libraries bundled inside
> `ojp-jdbc-driver` (gRPC, Netty, Protobuf, Guava, Commons Lang) are relocated to the
> `org.openjproxy.shaded.*` namespace. Your application can use any version of these
> libraries without version conflicts.

---

## Troubleshooting

### Logging Configuration

As of OJP version 0.3.2, the logging implementation has been updated to be compatible with Spring Boot's default logging framework, Logback.

**What Changed:**
- **OJP JDBC Driver**: No longer bundles any SLF4J implementation. It only uses the SLF4J API with `provided` scope, allowing the consuming application to choose the logging implementation.
- **OJP Server**: Uses Logback as the logging implementation with configurable options.

**Benefits:**
- ✅ No more logging conflicts when using OJP JDBC driver with Spring Boot
- ✅ Seamless integration with Spring Boot's existing logging configuration
- ✅ The consuming application (like your Spring Boot app) provides the logging implementation
- ✅ Consistent logging across your entire application

**OJP Server Logging Configuration:**

For detailed information about configuring OJP Server logging (log levels, file locations, rotation policies), see the [OJP Server Configuration Guide](../../configuration/ojp-server-configuration.md#logging-settings).

**For older versions (0.3.2-beta and earlier):**

If you're using an older version of OJP, you may encounter a conflict because the OJP JDBC driver bundled SLF4J Simple, which conflicts with Spring Boot's default Logback implementation.

The error typically looks like this:

```shell
SLF4J(W): Class path contains multiple SLF4J providers.
SLF4J(W): Found provider [org.slf4j.simple.SimpleServiceProvider@75412c2f]
SLF4J(W): Found provider [ch.qos.logback.classic.spi.LogbackServiceProvider@282ba1e]
Exception in thread "main" java.lang.IllegalStateException: LoggerFactory is not a Logback LoggerContext...
```

**Solution for older versions:**

Option 1 (Recommended): Upgrade to OJP 0.3.2 or later, which has this issue resolved.

Option 2: If you must use an older version, you can work around the issue by adding a JVM argument:
```shell
JAVA_OPTS="-Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider"
```


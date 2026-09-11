# Quarkus

To integrate OJP into your Quarkus project follow the steps:

## 1 Add the maven dependency to your project.
```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>[TBD]</version>
</dependency>
```

## 2 Disable quarkus default connection pool

```properties 
## Use unpooled datasource 

quarkus.datasource.jdbc=true
quarkus.datasource.jdbc.unpooled=true
```

## 3 Change your connection URL
In your `application.properties` (or `application.yml`) file, update your database connection URL, and add the OJP jdbc driver class as in the following example:
```properties
quarkus.datasource.jdbc.url=jdbc:ojp[localhost:1059]_h2:mem:shopdb
quarkus.datasource.jdbc.driver=org.openjproxy.jdbc.Driver
```

The example above is for `h2` but it is similar to any other database, you just need to add the `ojp[host:port]_` pattern immediately after `jdbc:`. `[host:port]` indicates the host and port you have your OJP proxy server running.

> **Note:** The Quarkus connection URL (`quarkus.datasource.jdbc.url`) and driver class are
> configured in `application.properties` or `application.yml` as shown above. OJP driver-specific
> settings (connection pool sizes, health check intervals, multinode retry configuration, etc.)
> must be provided separately in an `ojp.properties` file (or an environment-specific variant such
> as `ojp-dev.properties`).
>
> See [OJP JDBC Configuration](../../configuration/ojp-jdbc-configuration.md) for the full list of
> `ojp.properties` settings.

---

## Runtime Dependencies

The OJP JDBC driver marks two dependencies as `provided`, meaning they are **not** bundled
inside the JAR and must be present on the classpath at runtime.

| Provided dependency | Supplied automatically by Quarkus? |
|---|---|
| `org.slf4j:slf4j-api` | ✅ Yes — Quarkus includes a JBoss Logging → SLF4J bridge |
| `jakarta.transaction:jakarta.transaction-api` | ✅ Yes — provided by `quarkus-narayana-jta` (pulled in by `quarkus-jdbc` and `quarkus-hibernate-orm`) |

No extra Maven dependencies are needed for standard Quarkus applications that include
the Quarkus JDBC or ORM extensions.

> **Note (XA transactions):** If you want to use OJP XA connections (`OjpXADataSource`)
> and are **not** using Quarkus's built-in JTA support, add
> `jakarta.transaction:jakarta.transaction-api` with `provided` scope to your POM.

> **Classpath isolation:** Since OJP 0.5.x all third-party libraries bundled inside
> `ojp-jdbc-driver` (gRPC, Netty, Protobuf, Guava, Commons Lang) are relocated to the
> `org.openjproxy.shaded.*` namespace. Quarkus ships its own gRPC/Netty/Protobuf stack; the
> OJP driver's internal copies are completely isolated and will not conflict with them.

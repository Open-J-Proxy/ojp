# Micronaut

To integrate OJP into your Micronaut project follow the steps:

## 1 Add the maven dependency to your project.
```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>[TBD]</version>
</dependency>
```

## 2 Disable quarkus default connection pool

### Remove HikariCP maven dependency
```xml
<dependency>
    <groupId>io.micronaut.sql</groupId>
    <artifactId>micronaut-jdbc-hikari</artifactId>
</dependency>
```

### Create a new DataSourceFactory
```java
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.openjproxy.jdbc.OjpDataSource;

import javax.sql.DataSource;

@Factory
public class DataSourceFactory {

    @Singleton
    @Named("default")
    public DataSource dataSource(
        @Value("${datasources.default.url}") String url,
        @Value("${datasources.default.username}") String user,
        @Value("${datasources.default.password}") String password
    ) {
        return new OjpDataSource(url, user, password);
    }
}
```

## 3 Change your connection URL
In your `application.properties` (or `application.yml`) file, update your database connection URL as in the following example:
```properties
datasources.default.url=jdbc:ojp[localhost:1059]_h2:mem:shopdb
datasources.default.username=myuser
datasources.default.password=mypassword
jpa.default.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect
```

The example above is for `h2` but it is similar to any other database, you just need to add the `ojp[host:port]_` pattern immediately after `jdbc:`. `[host:port]` indicates the host and port you have your OJP proxy server running.
Note that `jpa.default.properties.hibernate.dialect` has to be present.

> **Note:** The Micronaut datasource URL, username, and password are configured in `application.properties`
> or `application.yml` as shown above. OJP driver-specific settings (connection pool sizes, health
> check intervals, multinode retry configuration, etc.) must be provided separately in an
> `ojp.properties` file (or an environment-specific variant such as `ojp-dev.properties`).
>
> See [OJP JDBC Configuration](../../configuration/ojp-jdbc-configuration.md) for the full list of
> `ojp.properties` settings.

---

## Runtime Dependencies

The OJP JDBC driver marks two dependencies as `provided`, meaning they are **not** bundled
inside the JAR and must be present on the classpath at runtime.

| Provided dependency | Supplied automatically by Micronaut? |
|---|---|
| `org.slf4j:slf4j-api` | ✅ Yes — `micronaut-logging` (via Logback or SLF4J Simple) |
| `jakarta.transaction:jakarta.transaction-api` | ⚠️ Depends — see note below |

**`jakarta.transaction-api` availability in Micronaut:**

| Micronaut dependency in your project | `jakarta.transaction-api` available? |
|---|---|
| `micronaut-data-jdbc` or `micronaut-data-jpa` | ✅ Yes — pulled in transitively |
| `micronaut-transaction` | ✅ Yes — pulled in transitively |
| None of the above (JDBC only, no Micronaut Data) | ❌ Must add explicitly |

If your project does **not** use Micronaut Data or `micronaut-transaction`, and you want to
use OJP XA connections (`OjpXADataSource`), add the following dependency:

```xml
<!-- Required for OJP XA connections when micronaut-transaction is not on the classpath -->
<dependency>
    <groupId>jakarta.transaction</groupId>
    <artifactId>jakarta.transaction-api</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
```

> **Note:** For regular (non-XA) OJP connections the `jakarta.transaction-api` JAR is only
> needed at compile time to resolve `javax.transaction.xa.*`; on Java 11+ these classes are
> also available from the JDK's `java.transaction.xa` module, so in practice you can omit
> this dependency if you are not using XA transactions.

> **Classpath isolation:** Since OJP 0.5.x all third-party libraries bundled inside
> `ojp-jdbc-driver` (gRPC, Netty, Protobuf, Guava, Commons Lang) are relocated to the
> `org.openjproxy.shaded.*` namespace and will not conflict with Micronaut's own copies of
> those libraries.
# Jakarta EE

OJP integrates with Jakarta EE applications running on any compliant application server—GlassFish,
Payara, WildFly, Open Liberty, TomEE, and others. No framework-specific adapter is required;
the standard `ojp-jdbc-driver` artifact is all you need.

The examples in this guide use **GlassFish 7** (the reference implementation of Jakarta EE 10),
but the same principle applies to any Jakarta EE server—only the server-specific datasource
configuration differs.

> **Requirements:** Jakarta EE 10 compatible server, Java 25+ (Java 17+ for Jakarta EE 10 in general).
> A complete working example is available at
> [ojp-framework-integration / glassfish/shopservice](https://github.com/Open-J-Proxy/ojp-framework-integration/tree/main/glassfish/shopservice).

---

## How it works

Jakarta EE applications are commonly deployed to an **application server** (GlassFish, Payara,
WildFly, Open Liberty, TomEE, etc.). Traditionally these applications are packaged as **WAR files**
deployed to an external server. Note that modern versions of **Eclipse GlassFish** (7+) also
support an **embedded / fat-JAR deployment model**, so a WAR file is no longer strictly required.
The examples in this guide use the traditional WAR + external GlassFish server approach, which
is the most common production setup, but the OJP integration principle is identical for embedded
deployments.

Unlike embedded-server frameworks, datasources in a Jakarta EE application server are typically
declared at the **server level** and accessed by applications through **JNDI**. Jakarta EE uses
**CDI + JTA** for dependency injection and transaction management.

The OJP integration principle remains the same: disable client-side connection pooling so that
OJP can manage all pooling centrally on the proxy server.

---

## 1. Add the OJP JDBC driver dependency

```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
```

For the production WAR this dependency will be bundled inside `WEB-INF/lib`. However, GlassFish's
server-level JDBC pool manager (used by `glassfish-resources.xml`) cannot see jars inside a WAR.
For that reason you must also copy the driver to the GlassFish domain library:

```bash
cp ojp-jdbc-driver-*.jar $GLASSFISH_HOME/domains/domain1/lib/
```

---

## 2. Configure the datasource in `WEB-INF/glassfish-resources.xml`

GlassFish processes this file during deployment and creates the JDBC connection pool and JNDI
resource automatically.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE resources PUBLIC
    "-//GlassFish.org//DTD GlassFish Application Server 3.1 Resource Definitions//EN"
    "http://glassfish.org/dtds/glassfish-resources_1_5.dtd">

<!--
    IMPORTANT: steady-pool-size="0" and max-connection-usage-count="1" disable
    GlassFish's client-side connection pool so that OJP can manage all pooling
    centrally on the proxy server.  Leaving the client-side pool active would
    interfere with OJP's connection lifecycle management.
-->
<resources>

    <jdbc-connection-pool
        name="ShopServicePool"
        res-type="java.sql.Driver"
        driver-classname="org.openjproxy.jdbc.Driver"
        steady-pool-size="0"
        idle-timeout-in-seconds="0"
        max-connection-usage-count="1">
        <!--
            OJP JDBC URL: jdbc:ojp[<ojp-host>:<ojp-port>]_<backend-jdbc-url>
        -->
        <property name="url"
                  value="jdbc:ojp[localhost:1059]_postgresql://localhost/mydb"/>
        <property name="user" value="dbuser"/>
        <property name="password" value="dbpass"/>
    </jdbc-connection-pool>

    <jdbc-resource
        jndi-name="jdbc/myapp"
        pool-name="ShopServicePool"/>

</resources>
```

Key settings explained:

| Setting | Value | Reason |
|---|---|---|
| `res-type` | `java.sql.Driver` | Tells GlassFish to use the `Driver` interface instead of `DataSource`. |
| `driver-classname` | `org.openjproxy.jdbc.Driver` | The OJP JDBC driver class. |
| `steady-pool-size` | `0` | No connections are held idle — equivalent to `SimpleDriverDataSource` in Spring Boot. |
| `max-connection-usage-count` | `1` | Each physical connection is discarded after a single use, preventing GlassFish from pooling OJP virtual connections. |

### Multinode URL in `glassfish-resources.xml`

When connecting to multiple OJP servers (multinode mode), the JDBC URL contains commas to
separate server addresses (e.g. `jdbc:ojp[host1:1059,host2:1059]_...`). GlassFish's XML
parser treats unescaped commas inside a `<property value="..."/>` attribute as
**property-value list separators**, which silently truncates the URL after the first comma.

To prevent this, escape every comma inside the `value` attribute with the XML character
reference `&#44;`:

```xml
<property name="url"
          value="jdbc:ojp[host1:1059&#44;host2:1059&#44;host3:1059]_postgresql://localhost/mydb"/>
```

GlassFish passes the unescaped string `jdbc:ojp[host1:1059,host2:1059,host3:1059]_postgresql://localhost/mydb`
to the driver, which parses it correctly. No change is needed in your Java code—the driver
always receives the fully expanded URL.

> **Note:** This escaping requirement is specific to GlassFish (and Payara, which shares the
> same XML property parsing behaviour). Other Jakarta EE servers (WildFly, Open Liberty, TomEE)
> use different deployment descriptor formats and do not have this restriction.

---

## 3. Reference the datasource in `META-INF/persistence.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<persistence xmlns="https://jakarta.ee/xml/ns/persistence"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="https://jakarta.ee/xml/ns/persistence
                 https://jakarta.ee/xml/ns/persistence/persistence_3_0.xsd"
             version="3.0">

    <persistence-unit name="myapp" transaction-type="JTA">
        <jta-data-source>jdbc/myapp</jta-data-source>

        <!-- List your @Entity classes or use <exclude-unlisted-classes>false</exclude-unlisted-classes> -->
        <class>com.example.myapp.entity.User</class>
        <exclude-unlisted-classes>true</exclude-unlisted-classes>

        <properties>
            <property name="jakarta.persistence.schema-generation.database.action" value="create"/>
        </properties>
    </persistence-unit>

</persistence>
```

The JNDI name (`jdbc/myapp`) must match the `jndi-name` attribute of the `<jdbc-resource>` element
in `glassfish-resources.xml`.

---

## 4. Use CDI and JAX-RS as usual

No OJP-specific code is needed in your application. Use standard Jakarta EE APIs with CDI
repositories, JAX-RS resources, and JPA entities as you normally would. OJP is completely
transparent at the business logic layer.

For a complete working example with full CRUD resources, CDI repositories, and JPA entities, see
the [ojp-framework-integration / glassfish/shopservice](https://github.com/Open-J-Proxy/ojp-framework-integration/tree/main/glassfish/shopservice)
demo application.

---

## 5. Deploy and start

### Build the WAR

```bash
mvn clean package -DskipTests
```

### Install the driver (once per domain)

```bash
cp target/dependency/ojp-jdbc-driver-*.jar $GLASSFISH_HOME/domains/domain1/lib/
```

### Start GlassFish and deploy

```bash
$GLASSFISH_HOME/bin/asadmin start-domain
$GLASSFISH_HOME/bin/asadmin deploy target/myapp-1.0.0.war
```

GlassFish will process `WEB-INF/glassfish-resources.xml` during deployment and create the
`ShopServicePool` connection pool and `jdbc/myapp` JNDI resource automatically.

### Start the OJP Server

The OJP proxy server must be running and reachable at the host/port configured in your JDBC URL.

```bash
# Docker (recommended)
docker run -d --network host \
  -v $(pwd)/ojp-libs:/opt/ojp/ojp-libs \
  rrobetti/ojp:latest
```

For full server setup options see the
[Server Configuration Guide](../../configuration/ojp-server-configuration.md).

---

## Integration testing with Arquillian and GlassFish Embedded

For integration tests you can use **Arquillian** with the
`arquillian-glassfish-server-embedded` adapter, which spins up a GlassFish 7 instance
inside the Maven test JVM.

### Test-only datasource via `@DataSourceDefinition`

When running inside an embedded GlassFish instance, `WEB-INF/glassfish-resources.xml` is not
processed early enough and causes `NameNotFoundException` during deployment. Use a CDI bean
with `@DataSourceDefinition` instead — this is registered earlier in GlassFish's deployment
pipeline:

```java
import jakarta.annotation.sql.DataSourceDefinition;
import jakarta.enterprise.context.ApplicationScoped;

@DataSourceDefinition(
        name      = "java:app/jdbc/myapp",
        className = "org.openjproxy.jdbc.OjpDataSource",
        url       = "jdbc:ojp[localhost:1059]_h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=LEGACY",
        user      = "sa",
        password  = "")
@ApplicationScoped
public class TestDataSourceProducer { }
```

> **Note:** `MODE=LEGACY` makes H2 2.x accept EclipseLink's `BIGINT IDENTITY` DDL syntax.
> `DB_CLOSE_DELAY=-1` keeps the in-memory database alive for the entire test JVM.
> `className = "org.openjproxy.jdbc.OjpDataSource"` uses OJP's `DataSource` implementation,
> so OJP is always exercised during tests (even though the backend is H2 here).

### ShrinkWrap archive builder

```java
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;

public class DeploymentFactory {

    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class, "myapp.war")
                // Production classes
                .addPackages(true, "com.example.myapp")
                // Test-only datasource producer
                .addClass(TestDataSourceProducer.class)
                // Test persistence unit replaces the production one
                .addAsResource("META-INF/persistence-test.xml", "META-INF/persistence.xml")
                // beans.xml with annotated discovery mode
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }
}
```

### Test persistence unit (`persistence-test.xml`)

```xml
<persistence-unit name="myapp" transaction-type="JTA">
    <jta-data-source>java:app/jdbc/myapp</jta-data-source>
    <properties>
        <!-- Drop and recreate on every test run -->
        <property name="jakarta.persistence.schema-generation.database.action"
                  value="drop-and-create"/>
        <!-- Tell EclipseLink to generate H2-compatible DDL -->
        <property name="eclipselink.target-database"
                  value="org.eclipse.persistence.platform.database.H2Platform"/>
    </properties>
</persistence-unit>
```

### Required Maven dependencies for tests

```xml
<!-- Arquillian JUnit 5 support -->
<dependency>
    <groupId>org.jboss.arquillian.junit5</groupId>
    <artifactId>arquillian-junit5-container</artifactId>
    <scope>test</scope>
</dependency>

<!-- GlassFish 7 Embedded Arquillian adapter (OmniFaces project) -->
<dependency>
    <groupId>org.omnifaces.arquillian</groupId>
    <artifactId>arquillian-glassfish-server-embedded</artifactId>
    <version>1.4</version>
    <scope>test</scope>
</dependency>

<!-- GlassFish 7 Embedded server (Jakarta EE 10) -->
<dependency>
    <groupId>org.glassfish.main.extras</groupId>
    <artifactId>glassfish-embedded-all</artifactId>
    <version>1.0.0-RC1</version>
    <scope>test</scope>
</dependency>

<!-- SLF4J required by the OJP driver (test scope) -->
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>1.0.0-RC1</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>1.0.0-RC1</version>
    <scope>test</scope>
</dependency>
```

### Required JVM flags (Surefire)

GlassFish Embedded running under Java 17+ requires `--add-opens` flags for Weld to generate CDI
proxy classes without `WELD-001524` errors. Add these to your `maven-surefire-plugin` configuration:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>
            --add-opens java.base/jdk.internal.loader=ALL-UNNAMED
            --add-opens java.base/java.lang=ALL-UNNAMED
            --add-opens java.base/java.lang.reflect=ALL-UNNAMED
            --add-opens java.base/java.net=ALL-UNNAMED
            --add-opens java.base/java.util=ALL-UNNAMED
            --add-opens java.naming/javax.naming.spi=ALL-UNNAMED
            --add-opens java.rmi/sun.rmi.transport=ALL-UNNAMED
        </argLine>
    </configuration>
</plugin>
```

---

## Runtime Dependencies

The OJP JDBC driver marks two dependencies as `provided`, meaning they are **not** bundled
inside the JAR and must be present on the classpath at runtime.

| Provided dependency | Notes |
|---|---|
| `org.slf4j:slf4j-api` | Required for OJP driver logging. Must be on the server or application classpath. |
| `jakarta.transaction:jakarta.transaction-api` | Required for JTA/XA support. Part of every Jakarta EE platform. |

The table below shows how these are satisfied across common Jakarta EE and servlet runtimes.

| Runtime | `slf4j-api` | `jakarta.transaction-api` | Action required |
|---|---|---|---|
| **GlassFish 7 / Payara** | ❌ Not on default server classpath | ✅ Part of EE platform | Add `slf4j-api` + an SLF4J impl (see below) |
| **WildFly / JBoss EAP** | ✅ Included in the server's module system | ✅ Part of EE platform | No action needed |
| **Open Liberty** | ✅ Included via `slf4j-1.7` feature | ✅ Part of EE platform | No action needed |
| **TomEE** | ✅ Bundled with TomEE's logging layer | ✅ Part of EE platform | No action needed |
| **Apache Tomcat** (servlet container only) | ❌ Not provided | ❌ Not provided | Add both explicitly (see below) |

### GlassFish / Payara: adding SLF4J

GlassFish and Payara do not include `slf4j-api` on their default server classpath. You have
two options:

**Option A — bundle in `WEB-INF/lib` (recommended for WAR deployments):**

```xml
<!-- In your pom.xml — these will be packaged inside WEB-INF/lib -->
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
```

**Option B — install on the server (shared across all applications):**

```bash
cp slf4j-api-2.0.17.jar $GLASSFISH_HOME/domains/domain1/lib/
cp slf4j-simple-2.0.17.jar $GLASSFISH_HOME/domains/domain1/lib/
```

### Apache Tomcat: adding both SLF4J and JTA

Apache Tomcat is a **servlet container**, not a full Jakarta EE application server. It does
not supply `slf4j-api` or `jakarta.transaction-api` by default. Add both to your `pom.xml`:

```xml
<!-- SLF4J API (runtime logging) -->
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
<!-- SLF4J implementation — Logback is a common choice for Tomcat apps -->
<dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-classic</artifactId>
    <version>1.0.0-RC1</version>
</dependency>

<!-- JTA API — required only if using OJP XA connections (OjpXADataSource) -->
<!-- Omit if you are not using XA / distributed transactions -->
<dependency>
    <groupId>jakarta.transaction</groupId>
    <artifactId>jakarta.transaction-api</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
```

> **Tip:** On Java 11+ the `javax.transaction.xa.*` classes used by OJP's XA support are
> also available from the JDK's own `java.transaction.xa` module, so you can omit
> `jakarta.transaction-api` if you are not using XA transactions and your code does not
> import `jakarta.transaction.*` interfaces directly.

> **Classpath isolation:** Since OJP 0.5.x all third-party libraries bundled inside
> `ojp-jdbc-driver` (gRPC, Netty, Protobuf, Guava, Commons Lang) are relocated to the
> `org.openjproxy.shaded.*` namespace. This prevents conflicts whether the driver is deployed
> inside a WAR or in the server's shared library directory.

---

## Troubleshooting

### `NameNotFoundException` when running embedded tests

**Cause:** `WEB-INF/glassfish-resources.xml` is not processed early enough in GlassFish Embedded.

**Fix:** Replace the XML-based datasource with a `@DataSourceDefinition`-annotated CDI bean (see
[Integration testing with Arquillian](#integration-testing-with-arquillian-and-glassfish-embedded)
above).

### `NoClassDefFoundError: org/slf4j/LoggerFactory` at deployment

**Cause:** The OJP driver's SLF4J API dependency is `provided` scope; the consuming application
must supply an SLF4J binding.

**Fix:** Add `slf4j-api` and an SLF4J implementation (e.g. `slf4j-simple`) to your test
classpath, and ensure an SLF4J implementation is available on GlassFish's server classpath for
production.

### Connections not returned to the OJP server pool

**Cause:** GlassFish's own connection pool is still active (default `steady-pool-size > 0`).

**Fix:** Set `steady-pool-size="0"` and `max-connection-usage-count="1"` in the
`<jdbc-connection-pool>` element of `glassfish-resources.xml` as shown in
[step 2](#2-configure-the-datasource-in-web-infglassfish-resourcesxml).

### H2 DDL errors with EclipseLink (`BIGINT IDENTITY`)

**Cause:** H2 2.x requires `MODE=LEGACY` to accept `BIGINT IDENTITY` which EclipseLink generates
by default.

**Fix:** Add `MODE=LEGACY` to the H2 JDBC URL and set the EclipseLink target database property to
`org.eclipse.persistence.platform.database.H2Platform` (fully-qualified name required in
EclipseLink 4.x).

### Multinode URL is silently truncated — only the first OJP server is used

**Cause:** GlassFish treats unescaped commas inside a `<property value="..."/>` attribute as
property-value list separators. A multinode URL such as
`jdbc:ojp[host1:1059,host2:1059]_postgresql://localhost/mydb` is split at the first comma,
so the driver receives only `jdbc:ojp[host1:1059` and fails to parse it, or silently falls
back to a single-node connection.

**Fix:** XML-escape every comma in the URL with `&#44;`:

```xml
<property name="url"
          value="jdbc:ojp[host1:1059&#44;host2:1059&#44;host3:1059]_postgresql://localhost/mydb"/>
```

See [Multinode URL in `glassfish-resources.xml`](#multinode-url-in-glassfish-resourcesxml) for
the full explanation.

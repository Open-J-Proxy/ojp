# Introduction
Each folder under this directory have detailed documentation on how to integrate OJP on different frameworks.

- [Spring Boot](spring-boot/README.md)
- [Quarkus](quarkus/README.md)
- [Micronaut](micronaut/README.md)
- [Jakarta EE](jakarta-ee/README.md)

Note that the steps are always similar and follow 3 basic steps:

1. Modify your connection URL to OJP pattern.
2. Remove your current connection pool from the project. OJP will take the connection pooling work over.
3. Add OJP jdbc driver dependency to your project.

## Runtime Dependencies

The `ojp-jdbc-driver` JAR has two **`provided`** dependencies that are not bundled inside
the shaded artifact and must be present at runtime in your deployment environment:

| Dependency | Purpose | Who normally supplies it |
|---|---|---|
| `org.slf4j:slf4j-api` | Driver logging | Spring Boot, Quarkus, Micronaut, WildFly, Open Liberty, TomEE — all supply it automatically. **GlassFish/Payara and plain Tomcat do not.** |
| `jakarta.transaction:jakarta.transaction-api` | JTA/XA transaction support | Every Jakarta EE application server supplies it. Spring Boot supplies it via `spring-tx`. Quarkus and Micronaut supply it when their transaction extensions are on the classpath. **Plain Tomcat and Jetty/Undertow do not.** |

Each framework guide below includes a dedicated **Runtime Dependencies** section with
environment-specific instructions.

Enjoy OJP!

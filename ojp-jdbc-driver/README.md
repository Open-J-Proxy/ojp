# Deploy OJP JDBC Driver

## Current Implementation Level Assessment

| Assessment | Value |
|---|---|
| Assessment mode | **Test-proven by database** |
| Summary | Levels are claimed only where supporting integration tests exist. There is no single level claim for all databases in this module. |

### Current Test-Proven Coverage by Database (Java Reference)

This table describes what is currently demonstrated by tests in `ojp-jdbc-driver/src/test/java`.

| Database | Highest achieved level (current tests) | Evidence highlights |
|---|---:|---|
| **H2** | **L8** | CRUD, type coverage, transaction/savepoint, session affinity, and non-XA operational behavior (`H2*` integration suites + multinode client tests). |
| **PostgreSQL** | **L10** | Full non-XA + XA, session-affinity, operational, and multinode coverage, plus `MtlsIntegrationTest` for trusted, missing, and untrusted client certificates against two OJP servers. |
| **MySQL** | **L8** | CRUD/types/session-affinity and operational multinode behavior are covered; no dedicated MySQL XA suite. |
| **MariaDB** | **L6** | Covered mainly through shared MySQL/MariaDB suites and generic CRUD paths; dedicated MariaDB operational/XA depth is limited. |
| **Oracle** | **L9** | Strong CRUD/types/LOB/transaction coverage plus Oracle XA (`OracleXAIntegrationTest`); full multinode-XA conformance not database-dedicated. |
| **SQL Server** | **L9** | Broad SQL Server suites including metadata/result-set/LOB/session-affinity and XA (`SQLServerXAIntegrationTest`). |
| **DB2** | **L8** | Strong CRUD/types/LOB/transaction/session-affinity coverage; no dedicated DB2 XA integration suite. |
| **CockroachDB** | **L8** | CRUD/types/LOB/transaction and large result-set coverage; no dedicated XA coverage. |

Level definitions: [`../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md`](../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md)

## PostgreSQL L10 mTLS integration test

The `jdbc-mtls-test` job in Main CI generates temporary certificates, starts two OJP servers that require client certificates, and runs `MtlsIntegrationTest` through the public JDBC API. It checks that both servers accept a trusted client, JDBC session/transaction operations and XA commit/rollback/recovery work, and servers reject connections with no client certificate or an untrusted one. PostgreSQL is configured with prepared transactions for XA.

To run it locally, start PostgreSQL with `max_prepared_transactions` enabled, then generate the test-only keystores and truststores:

```bash
bash scripts/generate-jdbc-mtls-test-certificates.sh /tmp/ojp-mtls-certs
```

Start two OJP server instances on ports `10591` and `10592`. Configure both with TLS enabled, the generated `server-keystore.p12` and `server-truststore.p12` (type `PKCS12`, password `ojp-test-only`), and `ojp.server.tls.clientAuthRequired=true`. Both servers must use the same truststore and connect to the same PostgreSQL database.

Build the JDBC driver and run each case in a separate Maven process because the client TLS configuration is initialized once per JVM:

```bash
mvn clean install -pl ojp-jdbc-driver -am -DskipTests -Dgpg.skip=true

# Trusted client certificate: include client keystore and client truststore properties.
mvn test -pl ojp-jdbc-driver -Dtest=MtlsIntegrationTest#shouldRunJdbcAndXaOperationsAgainstEveryMutuallyAuthenticatedServer \
  -Dojp.mtls.tests.enabled=true \
  -Dojp.mtls.jdbc.url='jdbc:ojp[localhost:10591(multinode),localhost:10592(multinode)]_postgresql://localhost:5432/defaultdb' \
  -Dojp.mtls.user=testuser -Dojp.mtls.****** \
  -Dojp.client.tls.enabled=true \
  -Dojp.client.tls.keystore.path=/tmp/ojp-mtls-certs/client-keystore.p12 \
  -Dojp.client.tls.keystore.****** -Dojp.client.tls.keystore.type=PKCS12 \
  -Dojp.client.tls.truststore.path=/tmp/ojp-mtls-certs/client-truststore.p12 \
  -Dojp.client.tls.truststore.****** -Dojp.client.tls.truststore.type=PKCS12
```

Run the missing-certificate test without the client keystore properties, and run the untrusted-certificate test with `untrusted-client-keystore.p12` instead of `client-keystore.p12`. Keep these generated certificates and their test-only passwords out of production.

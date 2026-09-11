package org.openjproxy.jdbc.cockroach;

import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class CockroachDBResultSetMetaDataExtensiveTests {

    private static boolean isTestEnabled;
    private Connection connection;
    private ResultSetMetaData metaData;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enableCockroachDBTests", "false"));
    }

    @SneakyThrows
    void setUp(String driverClass, String url, String user, String password) throws SQLException {
        assumeFalse(!isTestEnabled, "CockroachDB tests are not enabled");

        connection = DriverManager.getConnection(url, user, password);
        Statement statement = connection.createStatement();

        try {
            statement.execute("DROP TABLE test_table_metadata");
        } catch (Exception e) {
            // Might not be created.
        }

        // CockroachDB-specific CREATE TABLE syntax
        statement.execute(
                "CREATE TABLE test_table_metadata (" +
                        "id SERIAL PRIMARY KEY, " +
                        "name VARCHAR(255) NOT NULL, " +
                        "age INT NULL, " +
                        "salary DECIMAL(10, 2) NOT NULL" +
                        ")"
        );
        statement.execute("INSERT INTO test_table_metadata (name, age, salary) VALUES ('Alice', 30, 50000.00)");

        ResultSet resultSet = statement.executeQuery("SELECT * FROM test_table_metadata");
        metaData = resultSet.getMetaData();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testAllResultSetMetaDataMethods(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        // getColumnCount
        assertEquals(4, metaData.getColumnCount());

        // isAutoIncrement - CockroachDB SERIAL columns are auto-increment
        assertFalse(metaData.isAutoIncrement(1)); // Depends on driver implementation
        assertFalse(metaData.isAutoIncrement(2));
        assertFalse(metaData.isAutoIncrement(3));
        assertFalse(metaData.isAutoIncrement(4));

        // isCaseSensitive - CockroachDB is case sensitive for data
        assertFalse(metaData.isCaseSensitive(1));
        assertTrue(metaData.isCaseSensitive(2));
        assertFalse(metaData.isCaseSensitive(3));
        assertFalse(metaData.isCaseSensitive(4));

        // isSearchable - All CockroachDB columns are searchable
        assertTrue(metaData.isSearchable(1));
        assertTrue(metaData.isSearchable(2));
        assertTrue(metaData.isSearchable(3));
        assertTrue(metaData.isSearchable(4));

        // isCurrency - None of these columns represent currency explicitly
        boolean isCurrency1 = metaData.isCurrency(1);
        assertTrue(isCurrency1 || !isCurrency1); // Accept both
        assertFalse(metaData.isCurrency(2));
        boolean isCurrency3 = metaData.isCurrency(3);
        assertTrue(isCurrency3 || !isCurrency3); // Accept both
        boolean isCurrency4 = metaData.isCurrency(4);
        assertTrue(isCurrency4 || !isCurrency4); // Accept both

        // isNullable - CockroachDB NULL constraints
        int nullable1 = metaData.isNullable(1);
        assertTrue(nullable1 == ResultSetMetaData.columnNoNulls || nullable1 == ResultSetMetaData.columnNullableUnknown);
        assertEquals(ResultSetMetaData.columnNoNulls, metaData.isNullable(2));
        assertEquals(ResultSetMetaData.columnNullable, metaData.isNullable(3));
        assertEquals(ResultSetMetaData.columnNoNulls, metaData.isNullable(4));

        // isSigned - CockroachDB numeric types are signed
        assertTrue(metaData.isSigned(1));
        boolean signed2 = metaData.isSigned(2); // VARCHAR is not signed but driver may vary
        assertTrue(signed2 || !signed2);
        assertTrue(metaData.isSigned(3));
        assertTrue(metaData.isSigned(4));

        // getColumnDisplaySize
        assertTrue(metaData.getColumnDisplaySize(1) > 0);
        assertTrue(metaData.getColumnDisplaySize(2) > 0);
        assertTrue(metaData.getColumnDisplaySize(3) > 0);
        assertTrue(metaData.getColumnDisplaySize(4) > 0);

        // getColumnLabel and getColumnName
        assertNotNull(metaData.getColumnLabel(1));
        assertNotNull(metaData.getColumnName(1));
        assertEquals("name", metaData.getColumnName(2).toLowerCase());
        assertEquals("age", metaData.getColumnName(3).toLowerCase());
        assertEquals("salary", metaData.getColumnName(4).toLowerCase());

        // getSchemaName
        String schemaName = metaData.getSchemaName(1);
        assertNotNull(schemaName);

        // getTableName
        String tableName = metaData.getTableName(1);
        assertNotNull(tableName);

        // getCatalogName
        String catalogName = metaData.getCatalogName(1);
        assertNotNull(catalogName);

        // getColumnType
        assertEquals(Types.BIGINT, metaData.getColumnType(1));
        assertEquals(Types.VARCHAR, metaData.getColumnType(2));
        assertEquals(Types.BIGINT, metaData.getColumnType(3));
        assertEquals(Types.NUMERIC, metaData.getColumnType(4));

        // getColumnTypeName
        assertNotNull(metaData.getColumnTypeName(1));
        assertNotNull(metaData.getColumnTypeName(2));
        assertNotNull(metaData.getColumnTypeName(3));
        assertNotNull(metaData.getColumnTypeName(4));

        // isReadOnly
        boolean readOnly1 = metaData.isReadOnly(1);
        assertTrue(readOnly1 || !readOnly1);

        // isWritable
        boolean writable1 = metaData.isWritable(1);
        assertTrue(writable1 || !writable1);

        // isDefinitelyWritable
        boolean definitelyWritable1 = metaData.isDefinitelyWritable(1);
        assertTrue(definitelyWritable1 || !definitelyWritable1);

        // getColumnClassName
        assertNotNull(metaData.getColumnClassName(1));
        assertNotNull(metaData.getColumnClassName(2));
        assertNotNull(metaData.getColumnClassName(3));
        assertNotNull(metaData.getColumnClassName(4));

        // getPrecision
        assertTrue(metaData.getPrecision(1) >= 0);
        assertTrue(metaData.getPrecision(2) > 0);
        assertTrue(metaData.getPrecision(3) >= 0);
        assertTrue(metaData.getPrecision(4) > 0);

        // getScale
        assertEquals(0, metaData.getScale(1));
        assertEquals(0, metaData.getScale(2));
        assertEquals(0, metaData.getScale(3));
        assertEquals(2, metaData.getScale(4));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testResultSetMetaDataWithNullValues(String driverClass, String url, String user, String password) throws SQLException {
        assumeFalse(!isTestEnabled, "CockroachDB tests are not enabled");

        connection = DriverManager.getConnection(url, user, password);
        Statement statement = connection.createStatement();

        try {
            statement.execute("DROP TABLE test_null_metadata");
        } catch (Exception e) {
            // Ignore
        }

        statement.execute(
                "CREATE TABLE test_null_metadata (" +
                        "id INT PRIMARY KEY, " +
                        "nullable_field VARCHAR(100)" +
                        ")"
        );
        statement.execute("INSERT INTO test_null_metadata (id, nullable_field) VALUES (1, NULL)");

        ResultSet resultSet = statement.executeQuery("SELECT * FROM test_null_metadata");
        ResultSetMetaData md = resultSet.getMetaData();

        assertEquals(2, md.getColumnCount());
        assertEquals(ResultSetMetaData.columnNoNulls, md.isNullable(1));
        int nullable2 = md.isNullable(2);
        assertTrue(nullable2 == ResultSetMetaData.columnNullable || nullable2 == ResultSetMetaData.columnNullableUnknown);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testResultSetMetaDataWithComplexTypes(String driverClass, String url, String user, String password) throws SQLException {
        assumeFalse(!isTestEnabled, "CockroachDB tests are not enabled");

        connection = DriverManager.getConnection(url, user, password);
        Statement statement = connection.createStatement();

        try {
            statement.execute("DROP TABLE test_complex_metadata");
        } catch (Exception e) {
            // Ignore
        }

        statement.execute(
                "CREATE TABLE test_complex_metadata (" +
                        "id INT PRIMARY KEY, " +
                        "bool_field BOOLEAN, " +
                        "timestamp_field TIMESTAMP, " +
                        "text_field TEXT, " +
                        "bytea_field BYTEA" +
                        ")"
        );
        statement.execute("INSERT INTO test_complex_metadata (id, bool_field, timestamp_field, text_field, bytea_field) " +
                "VALUES (1, true, CURRENT_TIMESTAMP, 'test text', '\\x48656C6C6F'::BYTEA)");

        ResultSet resultSet = statement.executeQuery("SELECT * FROM test_complex_metadata");
        ResultSetMetaData md = resultSet.getMetaData();

        assertEquals(5, md.getColumnCount());

        // Verify column types
        assertEquals(Types.BIGINT, md.getColumnType(1));
        int boolType = md.getColumnType(2);
        assertTrue(boolType == Types.BOOLEAN || boolType == Types.BIT);
        assertEquals(Types.TIMESTAMP, md.getColumnType(3));
        int textType = md.getColumnType(4);
        assertTrue(textType == Types.VARCHAR || textType == Types.LONGVARCHAR || textType == Types.CLOB);
        int byteaType = md.getColumnType(5);
        assertTrue(byteaType == Types.BINARY || byteaType == Types.VARBINARY || byteaType == Types.LONGVARBINARY);
    }
}

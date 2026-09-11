package org.openjproxy.jdbc;

import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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

import static org.junit.jupiter.api.Assertions.*;

public class ResultSetMetaDataExtensiveTests {

    private static boolean isH2TestEnabled;

    private Connection connection;
    private ResultSetMetaData metaData;

    @BeforeAll
    static void setupClass() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }

    @SneakyThrows
    public void setUp(String driverClass, String url, String user, String password) throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        connection = DriverManager.getConnection(url, user, password);
        Statement statement = connection.createStatement();

        try {
            statement.execute("DROP TABLE TEST_TABLE_METADATA");
        } catch (Exception e) {
            // Might not be created.
        }

        statement.execute(
                "CREATE TABLE TEST_TABLE_METADATA (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "name VARCHAR(255) NOT NULL, " +
                        "age INT NULL, " +
                        "salary NUMERIC(10, 2) NOT NULL" +
                        ")"
        );
        statement.execute("INSERT INTO TEST_TABLE_METADATA (name, age, salary) VALUES ('Alice', 30, 50000.00)");

        ResultSet resultSet = statement.executeQuery("SELECT * FROM TEST_TABLE_METADATA");
        metaData = resultSet.getMetaData();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testAllResultSetMetaDataMethods(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        // getColumnCount
        assertEquals(4, metaData.getColumnCount());

        // isAutoIncrement
        assertTrue( metaData.isAutoIncrement(1));
        assertFalse( metaData.isAutoIncrement(2));
        assertFalse( metaData.isAutoIncrement(3));
        assertFalse( metaData.isAutoIncrement(4));

        // isCaseSensitive
        assertTrue( metaData.isCaseSensitive(1));
        assertTrue( metaData.isCaseSensitive(2));
        assertTrue( metaData.isCaseSensitive(3));
        assertTrue( metaData.isCaseSensitive(4));

        // isSearchable
        assertTrue( metaData.isSearchable(1));
        assertTrue( metaData.isSearchable(2));
        assertTrue( metaData.isSearchable(3));
        assertTrue( metaData.isSearchable(4));

        // isCurrency
        assertFalse( metaData.isCurrency(1));
        assertFalse( metaData.isCurrency(2));
        assertFalse( metaData.isCurrency(3));
        assertFalse( metaData.isCurrency(4));

        // isNullable
        assertEquals(ResultSetMetaData.columnNoNulls, metaData.isNullable(1));
        assertEquals(ResultSetMetaData.columnNoNulls, metaData.isNullable(2));
        assertEquals(ResultSetMetaData.columnNullable, metaData.isNullable(3));
        assertEquals(ResultSetMetaData.columnNoNulls, metaData.isNullable(4));

        // isSigned
        assertTrue( metaData.isSigned(1));
        assertFalse( metaData.isSigned(2));
        assertTrue( metaData.isSigned(3));
        assertTrue( metaData.isSigned(4));

        // getColumnDisplaySize
        assertEquals(11, metaData.getColumnDisplaySize(1));
        assertEquals(255, metaData.getColumnDisplaySize(2));
        assertEquals(11, metaData.getColumnDisplaySize(3));
        assertEquals(12, metaData.getColumnDisplaySize(4));

        // getColumnLabel
        assertEquals("ID", metaData.getColumnLabel(1));
        assertEquals("NAME", metaData.getColumnLabel(2));
        assertEquals("AGE", metaData.getColumnLabel(3));
        assertEquals("SALARY", metaData.getColumnLabel(4));

        // getColumnName
        assertEquals("ID", metaData.getColumnName(1));
        assertEquals("NAME", metaData.getColumnName(2));
        assertEquals("AGE", metaData.getColumnName(3));
        assertEquals("SALARY", metaData.getColumnName(4));

        // getSchemaName
        assertEquals("PUBLIC", metaData.getSchemaName(1));
        assertEquals("PUBLIC", metaData.getSchemaName(2));
        assertEquals("PUBLIC", metaData.getSchemaName(3));
        assertEquals("PUBLIC", metaData.getSchemaName(4));

        // getPrecision
        assertEquals(32, metaData.getPrecision(1));
        assertEquals(255, metaData.getPrecision(2));
        assertEquals(32, metaData.getPrecision(3));
        assertEquals(10, metaData.getPrecision(4));

        // getScale
        assertEquals(0, metaData.getScale(1));
        assertEquals(0, metaData.getScale(2));
        assertEquals(0, metaData.getScale(3));
        assertEquals(2, metaData.getScale(4));

        // getTableName
        assertEquals("TEST_TABLE_METADATA", metaData.getTableName(1));
        assertEquals("TEST_TABLE_METADATA", metaData.getTableName(2));
        assertEquals("TEST_TABLE_METADATA", metaData.getTableName(3));
        assertEquals("TEST_TABLE_METADATA", metaData.getTableName(4));

        // getCatalogName
        assertEquals("TEST", metaData.getCatalogName(1));
        assertEquals("TEST", metaData.getCatalogName(2));
        assertEquals("TEST", metaData.getCatalogName(3));
        assertEquals("TEST", metaData.getCatalogName(4));

        // getColumnType
        assertEquals(Types.INTEGER, metaData.getColumnType(1));
        assertEquals(Types.VARCHAR, metaData.getColumnType(2));
        assertEquals(Types.INTEGER, metaData.getColumnType(3));
        assertEquals(Types.NUMERIC, metaData.getColumnType(4));

        // getColumnTypeName
        assertEquals("INTEGER", metaData.getColumnTypeName(1));
        assertEquals("CHARACTER VARYING", metaData.getColumnTypeName(2));
        assertEquals("INTEGER", metaData.getColumnTypeName(3));
        assertEquals("NUMERIC", metaData.getColumnTypeName(4));

        // isReadOnly
        assertFalse( metaData.isReadOnly(1));
        assertFalse( metaData.isReadOnly(2));
        assertFalse( metaData.isReadOnly(3));
        assertFalse( metaData.isReadOnly(4));

        // isWritable
        assertTrue( metaData.isWritable(1));
        assertTrue( metaData.isWritable(2));
        assertTrue( metaData.isWritable(3));
        assertTrue( metaData.isWritable(4));

        // isDefinitelyWritable
        assertFalse( metaData.isDefinitelyWritable(1));
        assertFalse( metaData.isDefinitelyWritable(2));
        assertFalse( metaData.isDefinitelyWritable(3));
        assertFalse( metaData.isDefinitelyWritable(4));

        // getColumnClassName
        assertEquals("java.lang.Integer", metaData.getColumnClassName(1));
        assertEquals("java.lang.String", metaData.getColumnClassName(2));
        assertEquals("java.lang.Integer", metaData.getColumnClassName(3));
        assertEquals("java.math.BigDecimal", metaData.getColumnClassName(4));
    }
}
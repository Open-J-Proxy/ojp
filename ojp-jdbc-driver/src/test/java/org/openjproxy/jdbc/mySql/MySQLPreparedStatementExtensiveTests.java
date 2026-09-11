package org.openjproxy.jdbc.mySql;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

 class MySQLPreparedStatementExtensiveTests {

    private static boolean isMySQLTestEnabled;
    private static boolean isMariaDBTestEnabled;
    private Connection connection;
    private PreparedStatement ps;

    @BeforeAll
    static void checkTestConfiguration() {
        isMySQLTestEnabled = Boolean.parseBoolean(System.getProperty("enableMySQLTests", "false"));
        isMariaDBTestEnabled = Boolean.parseBoolean(System.getProperty("enableMariaDBTests", "false"));
    }

     void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(!isMySQLTestEnabled, "MySQL tests are not enabled");
        assumeFalse(!isMariaDBTestEnabled, "MariaDB tests are not enabled");

        connection = DriverManager.getConnection(url, user, password);
        Statement stmt = connection.createStatement();
        try {
            stmt.execute("DROP TABLE mysql_prepared_stmt_test");
        } catch (SQLException ignore) {}
        stmt.execute("CREATE TABLE mysql_prepared_stmt_test (" +
                "id INT PRIMARY KEY, " +
                "name VARCHAR(255), " +
                "age INT, " +
                "data BLOB, " +
                "info TEXT, " +
                "dt DATE, " +
                "tm TIME, " +
                "ts TIMESTAMP)");
        stmt.close();
    }

    @AfterEach
    void tearDown() {
        TestDBUtils.closeQuietly(ps, connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testBasicParameterSetters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO mysql_prepared_stmt_test (id, name, age) VALUES (?, ?, ?)");

        // Test basic parameter setters
        ps.setInt(1, 1);
        ps.setString(2, "Alice");
        ps.setInt(3, 25);
        assertEquals(1, ps.executeUpdate());

        // Verify the insert
        ps = connection.prepareStatement("SELECT id, name, age FROM mysql_prepared_stmt_test WHERE id = ?");
        ps.setInt(1, 1);
        ResultSet rs = ps.executeQuery();
        assertTrue(rs.next());
        assertEquals(1, rs.getInt("id"));
        assertEquals("Alice", rs.getString("name"));
        assertEquals(25, rs.getInt("age"));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testNumericParameterSetters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO mysql_prepared_stmt_test (id, name, age) VALUES (?, ?, ?)");

        // Test numeric parameter setters
        ps.setLong(1, 2L);
        ps.setString(2, "Bob");
        ps.setShort(3, (short) 30);
        assertEquals(1, ps.executeUpdate());

        ps.setByte(1, (byte) 3);
        ps.setString(2, "Charlie");
        ps.setFloat(3, 35.5f);
        assertEquals(1, ps.executeUpdate());

        ps.setDouble(1, 4.0);
        ps.setString(2, "David");
        ps.setBigDecimal(3, BigDecimal.valueOf(40));
        assertEquals(1, ps.executeUpdate());

        ps.setBoolean(1, true); // Will be converted to 1
        ps.setString(2, "Eve");
        ps.setInt(3, 45);
        assertEquals(1, ps.executeUpdate());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testDateTimeParameterSetters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO mysql_prepared_stmt_test (id, name, dt, tm, ts) VALUES (?, ?, ?, ?, ?)");

        Date testDate = Date.valueOf("2024-12-01");
        Time testTime = Time.valueOf("10:30:45");
        Timestamp testTimestamp = Timestamp.valueOf("2024-12-01 10:30:45");

        ps.setInt(1, 10);
        ps.setString(2, "DateTest");
        ps.setDate(3, testDate);
        ps.setTime(4, testTime);
        ps.setTimestamp(5, testTimestamp);
        assertEquals(1, ps.executeUpdate());

        // Verify the data
        ps = connection.prepareStatement("SELECT dt, tm, ts FROM mysql_prepared_stmt_test WHERE id = ?");
        ps.setInt(1, 10);
        ResultSet rs = ps.executeQuery();
        assertTrue(rs.next());
        assertEquals(testDate, rs.getDate("dt"));
        assertEquals(testTime, rs.getTime("tm"));
        assertEquals(testTimestamp, rs.getTimestamp("ts"));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testBinaryParameterSetters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO mysql_prepared_stmt_test (id, name, data) VALUES (?, ?, ?)");

        byte[] testData = "Hello World".getBytes();
        ps.setInt(1, 20);
        ps.setString(2, "BinaryTest");
        ps.setBytes(3, testData);
        assertEquals(1, ps.executeUpdate());

        // Test with InputStream
        ps.setInt(1, 21);
        ps.setString(2, "StreamTest");
        ps.setBinaryStream(3, new ByteArrayInputStream(testData));
        assertEquals(1, ps.executeUpdate());

        // Verify the data
        ps = connection.prepareStatement("SELECT data FROM mysql_prepared_stmt_test WHERE id = ?");
        ps.setInt(1, 20);
        ResultSet rs = ps.executeQuery();
        assertTrue(rs.next());
        byte[] retrievedData = rs.getBytes("data");
        assertEquals("Hello World", new String(retrievedData));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testTextParameterSetters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO mysql_prepared_stmt_test (id, name, info) VALUES (?, ?, ?)");

        String testText = "This is a test text for TEXT column";
        ps.setInt(1, 30);
        ps.setString(2, "TextTest");
        ps.setString(3, testText);
        assertEquals(1, ps.executeUpdate());

        // Test with character stream
        ps.setInt(1, 31);
        ps.setString(2, "StreamTextTest");
        ps.setCharacterStream(3, new StringReader(testText));
        assertEquals(1, ps.executeUpdate());

        // Verify the data
        ps = connection.prepareStatement("SELECT info FROM mysql_prepared_stmt_test WHERE id = ?");
        ps.setInt(1, 30);
        ResultSet rs = ps.executeQuery();
        assertTrue(rs.next());
        assertEquals(testText, rs.getString("info"));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testNullParameterSetters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO mysql_prepared_stmt_test (id, name, age, data, info) VALUES (?, ?, ?, ?, ?)");

        ps.setInt(1, 40);
        ps.setNull(2, Types.VARCHAR);
        ps.setNull(3, Types.INTEGER);
        ps.setNull(4, Types.BLOB);
        ps.setNull(5, Types.LONGVARCHAR);
        assertEquals(1, ps.executeUpdate());

        // Verify nulls
        ps = connection.prepareStatement("SELECT name, age, data, info FROM mysql_prepared_stmt_test WHERE id = ?");
        ps.setInt(1, 40);
        ResultSet rs = ps.executeQuery();
        assertTrue(rs.next());
        assertNull(rs.getString("name"));
        assertEquals(0, rs.getInt("age"));
        assertTrue(rs.wasNull());
        assertNull(rs.getBytes("data"));
        assertNull(rs.getString("info"));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testExecuteQuery(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        // Insert test data
        ps = connection.prepareStatement("INSERT INTO mysql_prepared_stmt_test (id, name, age) VALUES (?, ?, ?)");
        ps.setInt(1, 50);
        ps.setString(2, "QueryTest");
        ps.setInt(3, 25);
        ps.executeUpdate();

        // Test executeQuery
        ps = connection.prepareStatement("SELECT * FROM mysql_prepared_stmt_test WHERE id = ?");
        ps.setInt(1, 50);
        ResultSet rs = ps.executeQuery();
        assertNotNull(rs);
        assertTrue(rs.next());
        assertEquals(50, rs.getInt("id"));
        assertEquals("QueryTest", rs.getString("name"));
        assertEquals(25, rs.getInt("age"));
        assertFalse(rs.next());
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testExecuteUpdate(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        // Test INSERT
        ps = connection.prepareStatement("INSERT INTO mysql_prepared_stmt_test (id, name, age) VALUES (?, ?, ?)");
        ps.setInt(1, 60);
        ps.setString(2, "UpdateTest");
        ps.setInt(3, 30);
        assertEquals(1, ps.executeUpdate());

        // Test UPDATE
        ps = connection.prepareStatement("UPDATE mysql_prepared_stmt_test SET age = ? WHERE id = ?");
        ps.setInt(1, 35);
        ps.setInt(2, 60);
        assertEquals(1, ps.executeUpdate());

        // Test DELETE
        ps = connection.prepareStatement("DELETE FROM mysql_prepared_stmt_test WHERE id = ?");
        ps.setInt(1, 60);
        assertEquals(1, ps.executeUpdate());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testExecute(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        // Test execute with query
        ps = connection.prepareStatement("SELECT COUNT(*) FROM mysql_prepared_stmt_test");
        boolean hasResultSet = ps.execute();
        assertTrue(hasResultSet);
        ResultSet rs = ps.getResultSet();
        assertNotNull(rs);
        rs.close();

        // Test execute with update
        ps = connection.prepareStatement("INSERT INTO mysql_prepared_stmt_test (id, name) VALUES (?, ?)");
        ps.setInt(1, 70);
        ps.setString(2, "ExecuteTest");
        hasResultSet = ps.execute();
        assertFalse(hasResultSet);
        assertEquals(1, ps.getUpdateCount());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testBatch(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO mysql_prepared_stmt_test (id, name, age) VALUES (?, ?, ?)");
        
        ps.setInt(1, 80);
        ps.setString(2, "Batch1");
        ps.setInt(3, 25);
        ps.addBatch();

        ps.setInt(1, 81);
        ps.setString(2, "Batch2");
        ps.setInt(3, 30);
        ps.addBatch();

        ps.setInt(1, 82);
        ps.setString(2, "Batch3");
        ps.setInt(3, 35);
        ps.addBatch();

        int[] results = ps.executeBatch();
        assertEquals(3, results.length);
        assertEquals(1, results[0]);
        assertEquals(1, results[1]);
        assertEquals(1, results[2]);

        // Verify the batch insert
        ps = connection.prepareStatement("SELECT COUNT(*) FROM mysql_prepared_stmt_test WHERE id BETWEEN ? AND ?");
        ps.setInt(1, 80);
        ps.setInt(2, 82);
        ResultSet rs = ps.executeQuery();
        assertTrue(rs.next());
        assertEquals(3, rs.getInt(1));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testClearParameters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO mysql_prepared_stmt_test (id, name, age) VALUES (?, ?, ?)");
        ps.setInt(1, 90);
        ps.setString(2, "ClearTest");
        ps.setInt(3, 25);

        ps.clearParameters();

        // Should throw SQLException because parameters are not set
        Assert.assertThrows(SQLException.class, () -> ps.executeUpdate());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testMetaData(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("SELECT id, name, age FROM mysql_prepared_stmt_test WHERE id = ?");
        ResultSetMetaData metaData = ps.getMetaData();
        assertNotNull(metaData);
        assertEquals(3, metaData.getColumnCount());
        assertEquals("id", metaData.getColumnName(1).toLowerCase());
        assertEquals("name", metaData.getColumnName(2).toLowerCase());
        assertEquals("age", metaData.getColumnName(3).toLowerCase());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testParameterMetaData(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO mysql_prepared_stmt_test (id, name, age) VALUES (?, ?, ?)");
        try {
            var paramMetaData = ps.getParameterMetaData();
            assertNotNull(paramMetaData);
            //TODO implement the ParameterMetaData using remote proxy
            //Assertions.assertEquals(3, paramMetaData.getParameterCount());
        } catch (SQLException e) {
            // Some MySQL drivers/versions may not fully support parameter metadata
            // This is acceptable
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testGeneratedKeys(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        // Create table with auto-increment
        Statement stmt = connection.createStatement();
        try {
            stmt.execute("DROP TABLE mysql_auto_increment_ps_test");
        } catch (SQLException ignore) {}
        stmt.execute("CREATE TABLE mysql_auto_increment_ps_test (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100))");
        stmt.close();

        ps = connection.prepareStatement("INSERT INTO mysql_auto_increment_ps_test (name) VALUES (?)", 
                                        Statement.RETURN_GENERATED_KEYS);
        ps.setString(1, "GeneratedKeyTest");
        assertEquals(1, ps.executeUpdate());

        ResultSet keys = ps.getGeneratedKeys();
        assertNotNull(keys);
        assertTrue(keys.next());
        assertTrue(keys.getInt(1) > 0);
        keys.close();

        // Cleanup
        stmt = connection.createStatement();
        stmt.execute("DROP TABLE mysql_auto_increment_ps_test");
        stmt.close();
    }

    /**
     * Reproduces the Spring Data saveAll / batch insert scenario with generated keys (issue #408).
     * Verifies that RETURN_GENERATED_KEYS is preserved when prepareStatement is followed by
     * repeated addBatch() calls and executeBatch() — the exact sequence Spring Data uses for saveAll.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testBatchInsertWithGeneratedKeys(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        Statement stmt = connection.createStatement();
        try {
            stmt.execute("DROP TABLE mysql_batch_gen_keys_test");
        } catch (SQLException ignore) {
            // Table may not exist on first run; ignore the error and proceed with CREATE TABLE
        }
        stmt.execute("CREATE TABLE mysql_batch_gen_keys_test (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100))");
        stmt.close();

        // Reproduces: prepareStatement(sql, RETURN_GENERATED_KEYS) → addBatch() x N → executeBatch() → getGeneratedKeys()
        ps = connection.prepareStatement("INSERT INTO mysql_batch_gen_keys_test (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS);

        ps.setString(1, "Alice");
        ps.addBatch();

        ps.setString(1, "Bob");
        ps.addBatch();

        ps.setString(1, "Carol");
        ps.addBatch();

        int[] batchResults = ps.executeBatch();
        assertEquals(3, batchResults.length, "All 3 batch rows should be inserted");
        for (int result : batchResults) {
            assertTrue(result >= 0 || result == Statement.SUCCESS_NO_INFO, "Each batch row should succeed");
        }

        ResultSet keys = ps.getGeneratedKeys();
        assertNotNull(keys, "getGeneratedKeys() must not return null after batch insert");
        int keyCount = 0;
        while (keys.next()) {
            long generatedId = keys.getLong(1);
            assertTrue(generatedId > 0, "Each generated key must be positive");
            keyCount++;
        }
        assertTrue(keyCount > 0, "At least one generated key must be returned");
        keys.close();

        // Cleanup
        stmt = connection.createStatement();
        stmt.execute("DROP TABLE mysql_batch_gen_keys_test");
        stmt.close();
    }
}
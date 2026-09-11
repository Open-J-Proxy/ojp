package org.openjproxy.jdbc.oracle;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class OraclePreparedStatementExtensiveTests {

    private static boolean isTestDisabled;

    private Connection connection;
    private PreparedStatement ps;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");
        
        connection = DriverManager.getConnection(url, user, password);
        Statement stmt = connection.createStatement();
        try {
            stmt.execute("DROP TABLE oracle_prepared_stmt_test");
        } catch (SQLException ignore) {}
        // Oracle-compatible table creation
        stmt.execute("CREATE TABLE oracle_prepared_stmt_test (" +
                "id NUMBER(10) PRIMARY KEY, " +
                "name VARCHAR2(255), " +
                "age NUMBER(10), " +
                "data RAW(2000), " +  // Oracle equivalent of BLOB for small data
                "info CLOB, " +       // Oracle CLOB
                "dt DATE)");
        stmt.close();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestDBUtils.closeQuietly(ps, connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testBasicParameterSetting(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        ps = connection.prepareStatement("INSERT INTO oracle_prepared_stmt_test (id, name, age) VALUES (?, ?, ?)");
        
        ps.setInt(1, 1);
        ps.setString(2, "John Doe");
        ps.setInt(3, 30);
        
        int affected = ps.executeUpdate();
        assertEquals(1, affected);
        
        // Verify the insert
        PreparedStatement selectPs = connection.prepareStatement("SELECT * FROM oracle_prepared_stmt_test WHERE id = ?");
        selectPs.setInt(1, 1);
        ResultSet rs = selectPs.executeQuery();
        assertTrue(rs.next());
        assertEquals(1, rs.getInt("id"));
        assertEquals("John Doe", rs.getString("name"));
        assertEquals(30, rs.getInt("age"));
        rs.close();
        selectPs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testNullParameterHandling(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        ps = connection.prepareStatement("INSERT INTO oracle_prepared_stmt_test (id, name, age) VALUES (?, ?, ?)");
        
        ps.setInt(1, 2);
        ps.setNull(2, Types.VARCHAR);
        ps.setNull(3, Types.INTEGER);
        
        int affected = ps.executeUpdate();
        assertEquals(1, affected);
        
        // Verify the insert
        PreparedStatement selectPs = connection.prepareStatement("SELECT * FROM oracle_prepared_stmt_test WHERE id = ?");
        selectPs.setInt(1, 2);
        ResultSet rs = selectPs.executeQuery();
        assertTrue(rs.next());
        assertEquals(2, rs.getInt("id"));
        assertNull(rs.getString("name"));
        assertTrue(rs.wasNull());
        int age = rs.getInt("age");
        assertEquals(0, age);
        assertTrue(rs.wasNull());
        rs.close();
        selectPs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testNumericParameterTypes(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        
        // Test BigDecimal
        Statement stmt = connection.createStatement();
        stmt.execute("ALTER TABLE oracle_prepared_stmt_test ADD salary NUMBER(10,2)");
        stmt.close();
        
        ps = connection.prepareStatement("INSERT INTO oracle_prepared_stmt_test (id, name, salary) VALUES (?, ?, ?)");
        ps.setInt(1, 3);
        ps.setString(2, "Jane");
        ps.setBigDecimal(3, new BigDecimal("50000.50"));
        
        int affected = ps.executeUpdate();
        assertEquals(1, affected);
        
        // Verify
        PreparedStatement selectPs = connection.prepareStatement("SELECT salary FROM oracle_prepared_stmt_test WHERE id = ?");
        selectPs.setInt(1, 3);
        ResultSet rs = selectPs.executeQuery();
        assertTrue(rs.next());
        assertEquals(new BigDecimal("50000.5"), rs.getBigDecimal("salary"));
        rs.close();
        selectPs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testDateTimeParameterTypes(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        ps = connection.prepareStatement("INSERT INTO oracle_prepared_stmt_test (id, name, dt) VALUES (?, ?, ?)");
        
        java.sql.Date sqlDate = new java.sql.Date(System.currentTimeMillis());
        ps.setInt(1, 4);
        ps.setString(2, "DateTest");
        ps.setDate(3, sqlDate);
        
        int affected = ps.executeUpdate();
        assertEquals(1, affected);
        
        // Test with Calendar
        Calendar cal = Calendar.getInstance();
        ps.clearParameters();
        ps.setInt(1, 5);
        ps.setString(2, "DateCalTest");
        ps.setDate(3, sqlDate, cal);
        
        affected = ps.executeUpdate();
        assertEquals(1, affected);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testLargeObjectHandling(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        ps = connection.prepareStatement("INSERT INTO oracle_prepared_stmt_test (id, name, data, info) VALUES (?, ?, ?, ?)");
        
        byte[] testData = "This is test binary data".getBytes();
        String testText = "This is test text data";
        
        ps.setInt(1, 6);
        ps.setString(2, "LOBTest");
        ps.setBytes(3, testData);  // Oracle RAW
        ps.setString(4, testText); // Oracle CLOB
        
        int affected = ps.executeUpdate();
        assertEquals(1, affected);
        
        // Verify
        PreparedStatement selectPs = connection.prepareStatement("SELECT data, info FROM oracle_prepared_stmt_test WHERE id = ?");
        selectPs.setInt(1, 6);
        ResultSet rs = selectPs.executeQuery();
        assertTrue(rs.next());
        byte[] retrievedData = rs.getBytes("data");
        String retrievedText = rs.getString("info");
        assertEquals(new String(testData), new String(retrievedData));
        assertEquals(testText, retrievedText);
        rs.close();
        selectPs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testStreamHandling(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        ps = connection.prepareStatement("INSERT INTO oracle_prepared_stmt_test (id, name, data, info) VALUES (?, ?, ?, ?)");
        
        byte[] testData = "Stream binary data".getBytes();
        String testText = "Stream text data";
        
        ps.setInt(1, 7);
        ps.setString(2, "StreamTest");
        ps.setBinaryStream(3, new ByteArrayInputStream(testData));
        //TODO implement character stream support
        //ps.setCharacterStream(4, new StringReader(testText));
        ps.setBinaryStream(4, new ByteArrayInputStream(testText.getBytes()));
        
        int affected = ps.executeUpdate();
        assertEquals(1, affected);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testParameterMetaData(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        ps = connection.prepareStatement("INSERT INTO oracle_prepared_stmt_test (id, name, age) VALUES (?, ?, ?)");
        
        // Basic parameter metadata operations
        assertNotNull(ps.getParameterMetaData());
        // Oracle JDBC driver should return accurate parameter count
        int paramCount = ps.getParameterMetaData().getParameterCount();
        //TODO implement parameter metadata with proxy calls
        //assertEquals(3, paramCount, "Parameter count should be 3, got: " + paramCount);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testBatchOperations(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        ps = connection.prepareStatement("INSERT INTO oracle_prepared_stmt_test (id, name, age) VALUES (?, ?, ?)");
        
        // Add multiple batches
        ps.setInt(1, 8);
        ps.setString(2, "Batch1");
        ps.setInt(3, 25);
        ps.addBatch();
        
        ps.setInt(1, 9);
        ps.setString(2, "Batch2");
        ps.setInt(3, 35);
        ps.addBatch();
        
        int[] results = ps.executeBatch();
        assertEquals(2, results.length);
        assertEquals(1, results[0]);
        assertEquals(1, results[1]);
        
        // Clear batch and verify
        ps.clearBatch();
        results = ps.executeBatch();
        assertEquals(0, results.length);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testResultSetHandling(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        
        // Insert test data first
        ps = connection.prepareStatement("INSERT INTO oracle_prepared_stmt_test (id, name, age) VALUES (?, ?, ?)");
        ps.setInt(1, 10);
        ps.setString(2, "QueryTest");
        ps.setInt(3, 40);
        ps.executeUpdate();
        ps.close();
        
        // Test query
        ps = connection.prepareStatement("SELECT * FROM oracle_prepared_stmt_test WHERE id = ?");
        ps.setInt(1, 10);
        
        boolean hasResultSet = ps.execute();
        assertTrue(hasResultSet);
        
        ResultSet rs = ps.getResultSet();
        assertNotNull(rs);
        assertTrue(rs.next());
        assertEquals(10, rs.getInt("id"));
        assertEquals("QueryTest", rs.getString("name"));
        assertEquals(40, rs.getInt("age"));
        assertFalse(rs.next());
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleSpecificTypes(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        
        // Create table with Oracle-specific types
        Statement stmt = connection.createStatement();
        try {
            stmt.execute("DROP TABLE oracle_specific_types_test");
        } catch (SQLException ignore) {}
        
        stmt.execute("CREATE TABLE oracle_specific_types_test (" +
                "id NUMBER(10) PRIMARY KEY, " +
                "binary_float_col BINARY_FLOAT, " +
                "binary_double_col BINARY_DOUBLE, " +
                "nvarchar_col NVARCHAR2(100), " +
                "number_col NUMBER(10,2))");
        stmt.close();
        
        ps = connection.prepareStatement("INSERT INTO oracle_specific_types_test " +
                "(id, binary_float_col, binary_double_col, nvarchar_col, number_col) VALUES (?, ?, ?, ?, ?)");
        
        ps.setInt(1, 1);
        ps.setFloat(2, 123.45f);
        ps.setDouble(3, 12345.6789);
        ps.setString(4, "Oracle NVARCHAR2");
        ps.setBigDecimal(5, new BigDecimal("999.99"));
        
        int affected = ps.executeUpdate();
        assertEquals(1, affected);
        
        // Verify
        PreparedStatement selectPs = connection.prepareStatement("SELECT * FROM oracle_specific_types_test WHERE id = ?");
        selectPs.setInt(1, 1);
        ResultSet rs = selectPs.executeQuery();
        assertTrue(rs.next());
        assertEquals(123.45f, rs.getFloat("binary_float_col"), 0.01f);
        assertEquals(12345.6789, rs.getDouble("binary_double_col"), 0.0001);
        assertEquals("Oracle NVARCHAR2", rs.getString("nvarchar_col"));
        assertEquals(new BigDecimal("999.99"), rs.getBigDecimal("number_col"));
        rs.close();
        selectPs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testErrorHandling(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        ps = connection.prepareStatement("INSERT INTO oracle_prepared_stmt_test (id, name, age) VALUES (?, ?, ?)");
        
        // Test setting invalid parameter index - Oracle would throw an exception but as OJP delays the
        // formation of the PreparedStatement to not allocate the connection too early, this error will
        // only happen when executeQuery or executeUpdate is called.
        ps.setString(5, "Invalid");
        assertThrows(SQLException.class, () -> ps.executeUpdate());

        // Reset and test executing without setting all parameters
        ps = connection.prepareStatement("INSERT INTO oracle_prepared_stmt_test (id, name, age) VALUES (?, ?, ?)");
        ps.setInt(1, 11);
        // Don't set parameters 2 and 3
        assertThrows(SQLException.class, () -> ps.executeUpdate());
    }

    /**
     * Reproduces the Spring Data saveAll / batch insert scenario with generated keys (issue #408).
     * Verifies that RETURN_GENERATED_KEYS is preserved when prepareStatement is followed by
     * repeated addBatch() calls and executeBatch() — the exact sequence Spring Data uses for saveAll.
     *
     * <p><b>Oracle limitation:</b> When using {@code Statement.RETURN_GENERATED_KEYS}, the Oracle
     * JDBC driver returns ROWIDs (not integer primary key values) in the generated-keys ResultSet.
     * Calling {@code getLong(1)} on a ROWID throws {@code ORA-17132: Invalid conversion requested}.
     * This test therefore reads the generated key as a String (ROWID) and asserts it is non-null
     * and non-empty. The key goal — that RETURN_GENERATED_KEYS is accepted without error and
     * survives the addBatch() round-trip (the OJP fix) — is still fully exercised.
     *
     * <p>Note: The existing {@code OracleStatementExtensiveTests.testGeneratedKeys} already uses
     * column names ({@code new String[]{"id"}}) instead of {@code RETURN_GENERATED_KEYS} because of
     * this Oracle ROWID behavior.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testBatchInsertWithGeneratedKeys(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        Statement stmt = connection.createStatement();
        try {
            stmt.execute("DROP TABLE oracle_batch_gen_keys_test");
        } catch (SQLException ignore) {
            // Table may not exist on first run; ignore the error and proceed with CREATE TABLE
        }
        stmt.execute("CREATE TABLE oracle_batch_gen_keys_test (id NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, name VARCHAR2(100))");
        stmt.close();

        // Reproduces: prepareStatement(sql, RETURN_GENERATED_KEYS) → addBatch() x N → executeBatch() → getGeneratedKeys()
        // The key goal is that RETURN_GENERATED_KEYS survives the addBatch() round-trip (the OJP bug that was fixed).
        ps = connection.prepareStatement("INSERT INTO oracle_batch_gen_keys_test (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS);

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
            // Oracle returns ROWIDs (not integers) when using RETURN_GENERATED_KEYS.
            // getLong(1) would throw ORA-17132; use getString(1) to retrieve the ROWID string.
            String rowId = keys.getString(1);
            assertNotNull(rowId, "Each generated ROWID must be non-null");
            assertFalse(rowId.isEmpty(), "Each generated ROWID must be non-empty");
            keyCount++;
        }
        assertTrue(keyCount > 0, "At least one generated key (ROWID) must be returned");
        keys.close();
    }
}
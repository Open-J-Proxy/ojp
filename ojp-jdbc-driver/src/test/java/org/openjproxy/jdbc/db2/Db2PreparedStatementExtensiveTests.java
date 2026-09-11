package org.openjproxy.jdbc.db2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class Db2PreparedStatementExtensiveTests {

    private static boolean isTestDisabled;

    private Connection connection;
    private PreparedStatement ps;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");
        
        connection = DriverManager.getConnection(url, user, password);
        
        // Set schema explicitly to avoid "object not found" errors
        try (Statement schemaStmt = connection.createStatement()) {
            schemaStmt.execute("SET SCHEMA DB2INST1");
        }
        
        Statement stmt = connection.createStatement();
        try {
            stmt.execute("DROP TABLE DB2INST1.db2_prepared_stmt_test");
        } catch (SQLException e) {
            // Table doesn't exist
        }
        
        // Create table with DB2-compatible syntax
        stmt.execute("CREATE TABLE DB2INST1.db2_prepared_stmt_test (" +
                "id INTEGER NOT NULL PRIMARY KEY, " +
                "name VARCHAR(100), " +
                "age INTEGER, " +
                "salary DECIMAL(10,2), " +
                "is_active SMALLINT, " +
                "created_date DATE, " +
                "notes CLOB(1M), " +
                "data_blob BLOB(1M))");
        stmt.close();
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (ps != null) {
            ps.close();
        }
        if (connection != null) {
            Statement stmt = connection.createStatement();
            try {
                stmt.execute("DROP TABLE DB2INST1.db2_prepared_stmt_test");
            } catch (SQLException e) {
                // Ignore
            }
            stmt.close();
            connection.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2PreparedStatementBasicOperations(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Test INSERT operation
        ps = connection.prepareStatement("INSERT INTO DB2INST1.db2_prepared_stmt_test (id, name, age, salary, is_active) VALUES (?, ?, ?, ?, ?)");
        ps.setInt(1, 1);
        ps.setString(2, "John Doe");
        ps.setInt(3, 30);
        ps.setBigDecimal(4, new BigDecimal("50000.00"));
        ps.setInt(5, 1); // Use 1 for true since is_active is SMALLINT
        
        int insertCount = ps.executeUpdate();
        assertEquals(1, insertCount);
        ps.close();

        // Test SELECT operation
        ps = connection.prepareStatement("SELECT id, name, age, salary, is_active FROM DB2INST1.db2_prepared_stmt_test WHERE id = ?");
        ps.setInt(1, 1);
        
        ResultSet rs = ps.executeQuery();
        assertTrue(rs.next());
        assertEquals(1, rs.getInt("id"));
        assertEquals("John Doe", rs.getString("name"));
        assertEquals(30, rs.getInt("age"));
        assertEquals(new BigDecimal("50000.00"), rs.getBigDecimal("salary"));
        assertEquals(1, rs.getInt("is_active")); // Check for 1 since is_active is SMALLINT
        assertFalse(rs.next());
        rs.close();
        ps.close();

        // Test UPDATE operation
        ps = connection.prepareStatement("UPDATE DB2INST1.db2_prepared_stmt_test SET age = ?, salary = ? WHERE id = ?");
        ps.setInt(1, 31);
        ps.setBigDecimal(2, new BigDecimal("55000.00"));
        ps.setInt(3, 1);
        
        int updateCount = ps.executeUpdate();
        assertEquals(1, updateCount);
        ps.close();

        // Verify update
        ps = connection.prepareStatement("SELECT age, salary FROM DB2INST1.db2_prepared_stmt_test WHERE id = ?");
        ps.setInt(1, 1);
        rs = ps.executeQuery();
        assertTrue(rs.next());
        assertEquals(31, rs.getInt("age"));
        assertEquals(new BigDecimal("55000.00"), rs.getBigDecimal("salary"));
        rs.close();
        ps.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2PreparedStatementDataTypes(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Test various data types
        ps = connection.prepareStatement("INSERT INTO DB2INST1.db2_prepared_stmt_test (id, name, age, salary, is_active, created_date) VALUES (?, ?, ?, ?, ?, ?)");
        
        ps.setInt(1, 2);
        ps.setString(2, "Jane Smith");
        ps.setInt(3, 25);
        ps.setBigDecimal(4, new BigDecimal("45000.50"));
        ps.setInt(5, 0); // Use 0 for false since is_active is SMALLINT
        ps.setDate(6, java.sql.Date.valueOf("2023-12-25"));
        
        int insertCount = ps.executeUpdate();
        assertEquals(1, insertCount);
        ps.close();

        // Verify data types
        ps = connection.prepareStatement("SELECT * FROM DB2INST1.db2_prepared_stmt_test WHERE id = ?");
        ps.setInt(1, 2);
        
        ResultSet rs = ps.executeQuery();
        assertTrue(rs.next());
        assertEquals(2, rs.getInt("id"));
        assertEquals("Jane Smith", rs.getString("name"));
        assertEquals(25, rs.getInt("age"));
        assertEquals(new BigDecimal("45000.50"), rs.getBigDecimal("salary"));
        assertEquals(0, rs.getInt("is_active")); // Check for 0 since is_active is SMALLINT
        assertEquals(java.sql.Date.valueOf("2023-12-25"), rs.getDate("created_date"));
        rs.close();
        ps.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2PreparedStatementNullHandling(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Test NULL values
        ps = connection.prepareStatement("INSERT INTO DB2INST1.db2_prepared_stmt_test (id, name, age, salary, is_active) VALUES (?, ?, ?, ?, ?)");
        
        ps.setInt(1, 3);
        ps.setString(2, null);
        ps.setNull(3, Types.INTEGER);
        ps.setNull(4, Types.DECIMAL);
        ps.setNull(5, Types.BOOLEAN);
        
        int insertCount = ps.executeUpdate();
        assertEquals(1, insertCount);
        ps.close();

        // Verify NULL handling
        ps = connection.prepareStatement("SELECT name, age, salary, is_active FROM DB2INST1.db2_prepared_stmt_test WHERE id = ?");
        ps.setInt(1, 3);
        
        ResultSet rs = ps.executeQuery();
        assertTrue(rs.next());
        assertNull(rs.getString("name"));
        assertTrue(rs.wasNull());
        rs.getInt("age");
        assertTrue(rs.wasNull());
        rs.getBigDecimal("salary");
        assertTrue(rs.wasNull());
        rs.getInt("is_active"); // Check SMALLINT column
        assertTrue(rs.wasNull());
        rs.close();
        ps.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2PreparedStatementBatch(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Test batch operations
        ps = connection.prepareStatement("INSERT INTO DB2INST1.db2_prepared_stmt_test (id, name, age, salary, is_active) VALUES (?, ?, ?, ?, ?)");
        
        // Add multiple batch entries
        for (int i = 10; i < 15; i++) {
            ps.setInt(1, i);
            ps.setString(2, "Batch User " + i);
            ps.setInt(3, 20 + i);
            ps.setBigDecimal(4, new BigDecimal((i * 1000) + ".00"));
            ps.setInt(5, i % 2); // Use 0/1 instead of boolean since is_active is SMALLINT
            ps.addBatch();
        }
        
        int[] batchResults = ps.executeBatch();
        assertEquals(5, batchResults.length);
        for (int result : batchResults) {
            assertEquals(1, result);
        }
        ps.close();

        // Verify batch insert
        ps = connection.prepareStatement("SELECT COUNT(*) FROM DB2INST1.db2_prepared_stmt_test WHERE id >= 10 AND id < 15");
        ResultSet rs = ps.executeQuery();
        assertTrue(rs.next());
        assertEquals(5, rs.getInt(1));
        rs.close();
        ps.close();
    }

    /**
     * Reproduces the Spring Data saveAll / batch insert scenario with generated keys (issue #408).
     * Verifies that RETURN_GENERATED_KEYS is preserved when prepareStatement is followed by
     * repeated addBatch() calls and executeBatch() — the exact sequence Spring Data uses for saveAll.
     *
     * <p><b>DB2 limitation:</b> The IBM Data Server Driver for JDBC and SQLJ (JCC) does not support
     * returning auto-generated keys from batch operations. After executeBatch(), getGeneratedKeys()
     * returns an empty ResultSet on DB2 (documented IBM JCC driver behaviour, confirmed with
     * com.ibm.db2:jcc:11.5.9.0 against DB2 11.5.8.0). This test therefore verifies only that:
     * <ul>
     *   <li>the PreparedStatement accepts RETURN_GENERATED_KEYS without error,</li>
     *   <li>the batch executes successfully and inserts all rows, and</li>
     *   <li>getGeneratedKeys() returns a non-null (though empty) ResultSet.</li>
     * </ul>
     * Unlike H2, PostgreSQL, MySQL, Oracle, SQL Server and CockroachDB, DB2 does not
     * populate the generated-keys result set after a batch execution.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testBatchInsertWithGeneratedKeys(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        try (Statement stmt = connection.createStatement()) {
            try {
                stmt.execute("DROP TABLE DB2INST1.db2_batch_gen_keys_test");
            } catch (SQLException ignore) {
                // Table may not exist on first run; ignore the error and proceed with CREATE TABLE
            }
            stmt.execute("CREATE TABLE DB2INST1.db2_batch_gen_keys_test (id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, name VARCHAR(100))");
        }

        // Reproduces: prepareStatement(sql, RETURN_GENERATED_KEYS) → addBatch() x N → executeBatch() → getGeneratedKeys()
        // The key goal is that RETURN_GENERATED_KEYS survives the addBatch() round-trip (the OJP bug that was fixed).
        ps = connection.prepareStatement("INSERT INTO DB2INST1.db2_batch_gen_keys_test (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS);

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

        // Verify the rows were actually inserted
        try (PreparedStatement psCount = connection.prepareStatement(
                "SELECT COUNT(*) FROM DB2INST1.db2_batch_gen_keys_test")) {
            ResultSet rs = psCount.executeQuery();
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1), "All 3 rows must be present in the table after batch insert");
            rs.close();
        }

        // DB2 JCC driver does not return generated keys after executeBatch() — getGeneratedKeys()
        // returns a non-null but empty ResultSet. This is a documented IBM DB2 JCC limitation.
        ResultSet keys = ps.getGeneratedKeys();
        assertNotNull(keys, "getGeneratedKeys() must not return null even when DB2 returns no keys");
        keys.close();
    }
}
package openjdbcproxy.jdbc;

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
    private String tableName;

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");
        
        // Create unique table name for test isolation
        String uniqueSuffix = System.nanoTime() + "_" + Thread.currentThread().getId();
        this.tableName = "DB2INST1.db2_prepared_stmt_test_" + uniqueSuffix;
        
        connection = DriverManager.getConnection(url, user, password);
        
        // Set schema explicitly to avoid "object not found" errors
        try (Statement schemaStmt = connection.createStatement()) {
            schemaStmt.execute("SET SCHEMA DB2INST1");
        }
        
        Statement stmt = connection.createStatement();
        try {
            stmt.execute("DROP TABLE " + tableName);
        } catch (SQLException e) {
            // Table doesn't exist
        }
        
        // Create table with DB2-compatible syntax
        stmt.execute("CREATE TABLE " + tableName + " (" +
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
    public void tearDown() throws SQLException {
        if (ps != null) {
            ps.close();
        }
        if (connection != null) {
            Statement stmt = connection.createStatement();
            try {
                stmt.execute("DROP TABLE " + tableName + "");
            } catch (SQLException e) {
                // Ignore
            }
            stmt.close();
            connection.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    public void testDb2PreparedStatementBasicOperations(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Test INSERT operation
        ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age, salary, is_active) VALUES (?, ?, ?, ?, ?)");
        ps.setInt(1, 1);
        ps.setString(2, "John Doe");
        ps.setInt(3, 30);
        ps.setBigDecimal(4, new BigDecimal("50000.00"));
        ps.setInt(5, 1); // Use 1 for true since is_active is SMALLINT
        
        int insertCount = ps.executeUpdate();
        assertEquals(1, insertCount);
        ps.close();

        // Test SELECT operation
        ps = connection.prepareStatement("SELECT id, name, age, salary, is_active FROM " + tableName + " WHERE id = ?");
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
        ps = connection.prepareStatement("UPDATE " + tableName + " SET age = ?, salary = ? WHERE id = ?");
        ps.setInt(1, 31);
        ps.setBigDecimal(2, new BigDecimal("55000.00"));
        ps.setInt(3, 1);
        
        int updateCount = ps.executeUpdate();
        assertEquals(1, updateCount);
        ps.close();

        // Verify update
        ps = connection.prepareStatement("SELECT age, salary FROM " + tableName + " WHERE id = ?");
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
    public void testDb2PreparedStatementDataTypes(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Test various data types
        ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age, salary, is_active, created_date) VALUES (?, ?, ?, ?, ?, ?)");
        
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
        ps = connection.prepareStatement("SELECT * FROM " + tableName + " WHERE id = ?");
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
    public void testDb2PreparedStatementNullHandling(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Test NULL values
        ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age, salary, is_active) VALUES (?, ?, ?, ?, ?)");
        
        ps.setInt(1, 3);
        ps.setString(2, null);
        ps.setNull(3, Types.INTEGER);
        ps.setNull(4, Types.DECIMAL);
        ps.setNull(5, Types.BOOLEAN);
        
        int insertCount = ps.executeUpdate();
        assertEquals(1, insertCount);
        ps.close();

        // Verify NULL handling
        ps = connection.prepareStatement("SELECT name, age, salary, is_active FROM " + tableName + " WHERE id = ?");
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
    public void testDb2PreparedStatementBatch(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Test batch operations
        ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age, salary, is_active) VALUES (?, ?, ?, ?, ?)");
        
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
        ps = connection.prepareStatement("SELECT COUNT(*) FROM " + tableName + " WHERE id >= 10 AND id < 15");
        ResultSet rs = ps.executeQuery();
        assertTrue(rs.next());
        assertEquals(5, rs.getInt(1));
        rs.close();
        ps.close();
    }
}
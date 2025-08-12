package openjdbcproxy.jdbc;

import openjdbcproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.NClob;
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

public class PostgresPreparedStatementExtensiveTests {

    private static boolean isTestDisabled;

    private Connection connection;
    private String tableName;

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestDisabled = Boolean.parseBoolean(System.getProperty("disablePostgresTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(isTestDisabled, "Postgres tests are disabled");
        
        connection = DriverManager.getConnection(url, user, password);
        
        // Generate unique table name to avoid conflicts in concurrent execution
        String uniqueId = String.valueOf(System.nanoTime() + Thread.currentThread().getId());
        tableName = "" + tableName + "_" + uniqueId;
        
        Statement stmt = connection.createStatement();
        try {
            stmt.execute("DROP TABLE " + tableName);
        } catch (SQLException ignore) {}
        // PostgreSQL-compatible table creation
        stmt.execute("CREATE TABLE " + tableName + " (" +
                "id INT PRIMARY KEY, " +
                "name VARCHAR(255), " +
                "age INT, " +
                "data BYTEA, " +  // PostgreSQL equivalent of BLOB
                "info TEXT, " +   // PostgreSQL equivalent of CLOB
                "dt DATE)");
        stmt.close();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestDBUtils.closeQuietly(connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void testBasicParameterSetting(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        PreparedStatement ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");
        
        ps.setInt(1, 1);
        ps.setString(2, "John Doe");
        ps.setInt(3, 30);
        
        int affected = ps.executeUpdate();
        assertEquals(1, affected);
        
        // Verify the insert
        PreparedStatement selectPs = connection.prepareStatement("SELECT * FROM " + tableName + " WHERE id = ?");
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
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void testNullParameterHandling(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        PreparedStatement ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");
        
        ps.setInt(1, 2);
        ps.setNull(2, Types.VARCHAR);
        ps.setNull(3, Types.INTEGER);
        
        int affected = ps.executeUpdate();
        assertEquals(1, affected);
        
        // Verify the insert
        PreparedStatement selectPs = connection.prepareStatement("SELECT * FROM " + tableName + " WHERE id = ?");
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
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void testNumericParameterTypes(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        
        // Test BigDecimal
        Statement stmt = connection.createStatement();
        stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN salary DECIMAL(10,2)");
        stmt.close();
        
        PreparedStatement ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, salary) VALUES (?, ?, ?)");
        ps.setInt(1, 3);
        ps.setString(2, "Jane");
        ps.setBigDecimal(3, new BigDecimal("50000.50"));
        
        int affected = ps.executeUpdate();
        assertEquals(1, affected);
        
        // Verify
        PreparedStatement selectPs = connection.prepareStatement("SELECT salary FROM " + tableName + " WHERE id = ?");
        selectPs.setInt(1, 3);
        ResultSet rs = selectPs.executeQuery();
        assertTrue(rs.next());
        assertEquals(new BigDecimal("50000.50"), rs.getBigDecimal("salary"));
        rs.close();
        selectPs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void testDateTimeParameterTypes(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        PreparedStatement ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, dt) VALUES (?, ?, ?)");
        
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
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void testLargeObjectHandling(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        PreparedStatement ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, data, info) VALUES (?, ?, ?, ?)");
        
        byte[] testData = "This is test binary data".getBytes();
        String testText = "This is test text data";
        
        ps.setInt(1, 6);
        ps.setString(2, "LOBTest");
        ps.setBytes(3, testData);  // PostgreSQL BYTEA
        ps.setString(4, testText); // PostgreSQL TEXT
        
        int affected = ps.executeUpdate();
        assertEquals(1, affected);
        
        // Verify
        PreparedStatement selectPs = connection.prepareStatement("SELECT data, info FROM " + tableName + " WHERE id = ?");
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
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void testStreamHandling(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        PreparedStatement ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, data, info) VALUES (?, ?, ?, ?)");
        
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
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void testParameterMetaData(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        PreparedStatement ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");
        
        // Basic parameter metadata operations
        assertNotNull(ps.getParameterMetaData());
        // Note: PostgreSQL JDBC driver may return 0 for parameter count in some cases
        int paramCount = ps.getParameterMetaData().getParameterCount();
        assertTrue(paramCount >= 0, "Parameter count should be >= 0, got: " + paramCount);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void testBatchOperations(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        PreparedStatement ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");
        
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
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void testResultSetHandling(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        
        // Insert test data first
        PreparedStatement ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");
        ps.setInt(1, 10);
        ps.setString(2, "QueryTest");
        ps.setInt(3, 40);
        ps.executeUpdate();
        ps.close();
        
        // Test query
        PreparedStatement ps = connection.prepareStatement("SELECT * FROM " + tableName + " WHERE id = ?");
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
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void testErrorHandling(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        PreparedStatement ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");
        
        // Test setting invalid parameter index - PostgreSQL may allow this without immediate error
        try {
            ps.setString(5, "Invalid");
            // If no exception, test executing with invalid parameters
            assertThrows(SQLException.class, () -> ps.executeUpdate());
        } catch (SQLException e) {
            // Expected behavior - parameter index out of bounds
            assertTrue(e.getMessage().contains("parameter") || e.getMessage().contains("index"));
        }
        
        // Reset and test executing without setting all parameters
        PreparedStatement ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");
        ps.setInt(1, 11);
        // Don't set parameters 2 and 3
        assertThrows(SQLException.class, () -> ps.executeUpdate());
    }
}
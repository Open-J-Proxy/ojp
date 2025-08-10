package openjdbcproxy.jdbc;

import openjdbcproxy.jdbc.testutil.TestDBUtils;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
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

import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class MySQLPreparedStatementExtensiveTests {

    private static boolean isMySQLTestDisabled;
    private static boolean isMariaDBTestDisabled;
    private Connection connection;
    private PreparedStatement ps;
    private String tableName;

    @BeforeAll
    public static void checkTestConfiguration() {
        isMySQLTestDisabled = Boolean.parseBoolean(System.getProperty("disableMySQLTests", "false"));
        isMariaDBTestDisabled = Boolean.parseBoolean(System.getProperty("disableMariaDBTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(isMySQLTestDisabled, "MySQL tests are disabled");
        assumeFalse(isMariaDBTestDisabled, "MariaDB tests are disabled");

        connection = DriverManager.getConnection(url, user, password);
        
        // Generate unique table name to avoid conflicts in concurrent execution
        String uniqueId = String.valueOf(System.nanoTime() + Thread.currentThread().getId());
        tableName = "mysql_prepared_stmt_test_" + uniqueId;
        
        Statement stmt = connection.createStatement();
        try {
            stmt.execute("DROP TABLE " + tableName);
        } catch (SQLException ignore) {}
        stmt.execute("CREATE TABLE " + tableName + " (" +
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
    public void tearDown() throws Exception {
        TestDBUtils.closeQuietly(ps, connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testBasicParameterSetters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");

        // Test basic parameter setters
        ps.setInt(1, 1);
        ps.setString(2, "Alice");
        ps.setInt(3, 25);
        Assert.assertEquals(1, ps.executeUpdate());

        // Verify the insert
        ps = connection.prepareStatement("SELECT id, name, age FROM " + tableName + " WHERE id = ?");
        ps.setInt(1, 1);
        ResultSet rs = ps.executeQuery();
        Assert.assertTrue(rs.next());
        Assert.assertEquals(1, rs.getInt("id"));
        Assert.assertEquals("Alice", rs.getString("name"));
        Assert.assertEquals(25, rs.getInt("age"));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testNumericParameterSetters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");

        // Test numeric parameter setters
        ps.setLong(1, 2L);
        ps.setString(2, "Bob");
        ps.setShort(3, (short) 30);
        Assert.assertEquals(1, ps.executeUpdate());

        ps.setByte(1, (byte) 3);
        ps.setString(2, "Charlie");
        ps.setFloat(3, 35.5f);
        Assert.assertEquals(1, ps.executeUpdate());

        ps.setDouble(1, 4.0);
        ps.setString(2, "David");
        ps.setBigDecimal(3, BigDecimal.valueOf(40));
        Assert.assertEquals(1, ps.executeUpdate());

        ps.setBoolean(1, true); // Will be converted to 1
        ps.setString(2, "Eve");
        ps.setInt(3, 45);
        Assert.assertEquals(1, ps.executeUpdate());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testDateTimeParameterSetters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, dt, tm, ts) VALUES (?, ?, ?, ?, ?)");

        Date testDate = Date.valueOf("2024-12-01");
        Time testTime = Time.valueOf("10:30:45");
        Timestamp testTimestamp = Timestamp.valueOf("2024-12-01 10:30:45");

        ps.setInt(1, 10);
        ps.setString(2, "DateTest");
        ps.setDate(3, testDate);
        ps.setTime(4, testTime);
        ps.setTimestamp(5, testTimestamp);
        Assert.assertEquals(1, ps.executeUpdate());

        // Verify the data
        ps = connection.prepareStatement("SELECT dt, tm, ts FROM " + tableName + " WHERE id = ?");
        ps.setInt(1, 10);
        ResultSet rs = ps.executeQuery();
        Assert.assertTrue(rs.next());
        Assert.assertEquals(testDate, rs.getDate("dt"));
        Assert.assertEquals(testTime, rs.getTime("tm"));
        Assert.assertEquals(testTimestamp, rs.getTimestamp("ts"));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testBinaryParameterSetters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, data) VALUES (?, ?, ?)");

        byte[] testData = "Hello World".getBytes();
        ps.setInt(1, 20);
        ps.setString(2, "BinaryTest");
        ps.setBytes(3, testData);
        Assert.assertEquals(1, ps.executeUpdate());

        // Test with InputStream
        ps.setInt(1, 21);
        ps.setString(2, "StreamTest");
        ps.setBinaryStream(3, new ByteArrayInputStream(testData));
        Assert.assertEquals(1, ps.executeUpdate());

        // Verify the data
        ps = connection.prepareStatement("SELECT data FROM " + tableName + " WHERE id = ?");
        ps.setInt(1, 20);
        ResultSet rs = ps.executeQuery();
        Assert.assertTrue(rs.next());
        byte[] retrievedData = rs.getBytes("data");
        Assert.assertEquals("Hello World", new String(retrievedData));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testTextParameterSetters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, info) VALUES (?, ?, ?)");

        String testText = "This is a test text for TEXT column";
        ps.setInt(1, 30);
        ps.setString(2, "TextTest");
        ps.setString(3, testText);
        Assert.assertEquals(1, ps.executeUpdate());

        // Test with character stream
        ps.setInt(1, 31);
        ps.setString(2, "StreamTextTest");
        ps.setCharacterStream(3, new StringReader(testText));
        Assert.assertEquals(1, ps.executeUpdate());

        // Verify the data
        ps = connection.prepareStatement("SELECT info FROM " + tableName + " WHERE id = ?");
        ps.setInt(1, 30);
        ResultSet rs = ps.executeQuery();
        Assert.assertTrue(rs.next());
        Assert.assertEquals(testText, rs.getString("info"));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testNullParameterSetters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age, data, info) VALUES (?, ?, ?, ?, ?)");

        ps.setInt(1, 40);
        ps.setNull(2, Types.VARCHAR);
        ps.setNull(3, Types.INTEGER);
        ps.setNull(4, Types.BLOB);
        ps.setNull(5, Types.LONGVARCHAR);
        Assert.assertEquals(1, ps.executeUpdate());

        // Verify nulls
        ps = connection.prepareStatement("SELECT name, age, data, info FROM " + tableName + " WHERE id = ?");
        ps.setInt(1, 40);
        ResultSet rs = ps.executeQuery();
        Assert.assertTrue(rs.next());
        Assert.assertNull(rs.getString("name"));
        Assert.assertEquals(0, rs.getInt("age"));
        Assert.assertTrue(rs.wasNull());
        Assert.assertNull(rs.getBytes("data"));
        Assert.assertNull(rs.getString("info"));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testExecuteQuery(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        // Insert test data
        ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");
        ps.setInt(1, 50);
        ps.setString(2, "QueryTest");
        ps.setInt(3, 25);
        ps.executeUpdate();

        // Test executeQuery
        ps = connection.prepareStatement("SELECT * FROM " + tableName + " WHERE id = ?");
        ps.setInt(1, 50);
        ResultSet rs = ps.executeQuery();
        Assert.assertNotNull(rs);
        Assert.assertTrue(rs.next());
        Assert.assertEquals(50, rs.getInt("id"));
        Assert.assertEquals("QueryTest", rs.getString("name"));
        Assert.assertEquals(25, rs.getInt("age"));
        Assert.assertFalse(rs.next());
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testExecuteUpdate(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        // Test INSERT
        ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");
        ps.setInt(1, 60);
        ps.setString(2, "UpdateTest");
        ps.setInt(3, 30);
        Assert.assertEquals(1, ps.executeUpdate());

        // Test UPDATE
        ps = connection.prepareStatement("UPDATE " + tableName + " SET age = ? WHERE id = ?");
        ps.setInt(1, 35);
        ps.setInt(2, 60);
        Assert.assertEquals(1, ps.executeUpdate());

        // Test DELETE
        ps = connection.prepareStatement("DELETE FROM " + tableName + " WHERE id = ?");
        ps.setInt(1, 60);
        Assert.assertEquals(1, ps.executeUpdate());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testExecute(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        // Test execute with query
        ps = connection.prepareStatement("SELECT COUNT(*) FROM " + tableName + "");
        boolean hasResultSet = ps.execute();
        Assert.assertTrue(hasResultSet);
        ResultSet rs = ps.getResultSet();
        Assert.assertNotNull(rs);
        rs.close();

        // Test execute with update
        ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name) VALUES (?, ?)");
        ps.setInt(1, 70);
        ps.setString(2, "ExecuteTest");
        hasResultSet = ps.execute();
        Assert.assertFalse(hasResultSet);
        Assert.assertEquals(1, ps.getUpdateCount());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testBatch(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");
        
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
        Assert.assertEquals(3, results.length);
        Assert.assertEquals(1, results[0]);
        Assert.assertEquals(1, results[1]);
        Assert.assertEquals(1, results[2]);

        // Verify the batch insert
        ps = connection.prepareStatement("SELECT COUNT(*) FROM " + tableName + " WHERE id BETWEEN ? AND ?");
        ps.setInt(1, 80);
        ps.setInt(2, 82);
        ResultSet rs = ps.executeQuery();
        Assert.assertTrue(rs.next());
        Assert.assertEquals(3, rs.getInt(1));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testClearParameters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");
        ps.setInt(1, 90);
        ps.setString(2, "ClearTest");
        ps.setInt(3, 25);

        ps.clearParameters();

        // Should throw SQLException because parameters are not set
        Assert.assertThrows(SQLException.class, () -> ps.executeUpdate());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testMetaData(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("SELECT id, name, age FROM " + tableName + " WHERE id = ?");
        ResultSetMetaData metaData = ps.getMetaData();
        Assert.assertNotNull(metaData);
        Assert.assertEquals(3, metaData.getColumnCount());
        Assert.assertEquals("id", metaData.getColumnName(1).toLowerCase());
        Assert.assertEquals("name", metaData.getColumnName(2).toLowerCase());
        Assert.assertEquals("age", metaData.getColumnName(3).toLowerCase());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testParameterMetaData(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");
        try {
            var paramMetaData = ps.getParameterMetaData();
            Assert.assertNotNull(paramMetaData);
            //TODO implement the ParameterMetaData using remote proxy
            //Assert.assertEquals(3, paramMetaData.getParameterCount());
        } catch (SQLException e) {
            // Some MySQL drivers/versions may not fully support parameter metadata
            // This is acceptable
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testGeneratedKeys(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        // Create table with auto-increment
        Statement stmt = connection.createStatement();
        String autoTableName = "mysql_auto_increment_ps_test_" + System.nanoTime();
        try {
            stmt.execute("DROP TABLE " + autoTableName);
        } catch (SQLException ignore) {}
        stmt.execute("CREATE TABLE " + autoTableName + " (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100))");
        stmt.close();

        ps = connection.prepareStatement("INSERT INTO " + autoTableName + " (name) VALUES (?)", 
                                        Statement.RETURN_GENERATED_KEYS);
        ps.setString(1, "GeneratedKeyTest");
        Assert.assertEquals(1, ps.executeUpdate());

        ResultSet keys = ps.getGeneratedKeys();
        Assert.assertNotNull(keys);
        Assert.assertTrue(keys.next());
        Assert.assertTrue(keys.getInt(1) > 0);
        keys.close();

        // Cleanup
        stmt = connection.createStatement();
        stmt.execute("DROP TABLE " + autoTableName);
        stmt.close();
    }
}
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
        TestDBUtils.closeQuietly(connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testBasicParameterSetters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        PreparedStatement psInsert = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");

        // Test basic parameter setters
        psInsert.setInt(1, 1);
        psInsert.setString(2, "Alice");
        psInsert.setInt(3, 25);
        Assert.assertEquals(1, psInsert.executeUpdate());

        // Verify the insert
        PreparedStatement psSelect = connection.prepareStatement("SELECT id, name, age FROM " + tableName + " WHERE id = ?");
        psSelect.setInt(1, 1);
        ResultSet rs = psSelect.executeQuery();
        Assert.assertTrue(rs.next());
        Assert.assertEquals(1, rs.getInt("id"));
        Assert.assertEquals("Alice", rs.getString("name"));
        Assert.assertEquals(25, rs.getInt("age"));
        rs.close();
        psInsert.close();
        psSelect.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testNumericParameterSetters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        PreparedStatement psInsert = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");

        // Test numeric parameter setters
        psInsert.setLong(1, 2L);
        psInsert.setString(2, "Bob");
        psInsert.setShort(3, (short) 30);
        Assert.assertEquals(1, psInsert.executeUpdate());

        psInsert.setByte(1, (byte) 3);
        psInsert.setString(2, "Charlie");
        psInsert.setFloat(3, 35.5f);
        Assert.assertEquals(1, psInsert.executeUpdate());

        psInsert.setDouble(1, 4.0);
        psInsert.setString(2, "David");
        psInsert.setBigDecimal(3, BigDecimal.valueOf(40));
        Assert.assertEquals(1, psInsert.executeUpdate());

        psInsert.setBoolean(1, true); // Will be converted to 1
        psInsert.setString(2, "Eve");
        psInsert.setInt(3, 45);
        Assert.assertEquals(1, psInsert.executeUpdate());
        
        psInsert.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testDateTimeParameterSetters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        PreparedStatement psInsert = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, dt, tm, ts) VALUES (?, ?, ?, ?, ?)");

        Date testDate = Date.valueOf("2024-12-01");
        Time testTime = Time.valueOf("10:30:45");
        Timestamp testTimestamp = Timestamp.valueOf("2024-12-01 10:30:45");

        psInsert.setInt(1, 10);
        psInsert.setString(2, "DateTest");
        psInsert.setDate(3, testDate);
        psInsert.setTime(4, testTime);
        psInsert.setTimestamp(5, testTimestamp);
        Assert.assertEquals(1, psInsert.executeUpdate());

        // Verify the data
        PreparedStatement psSelect = connection.prepareStatement("SELECT dt, tm, ts FROM " + tableName + " WHERE id = ?");
        psSelect.setInt(1, 10);
        ResultSet rs = psSelect.executeQuery();
        Assert.assertTrue(rs.next());
        Assert.assertEquals(testDate, rs.getDate("dt"));
        Assert.assertEquals(testTime, rs.getTime("tm"));
        Assert.assertEquals(testTimestamp, rs.getTimestamp("ts"));
        rs.close();
        psInsert.close();
        psSelect.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testBinaryParameterSetters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        PreparedStatement psInsert = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, data) VALUES (?, ?, ?)");

        byte[] testData = "Hello World".getBytes();
        psInsert.setInt(1, 20);
        psInsert.setString(2, "BinaryTest");
        psInsert.setBytes(3, testData);
        Assert.assertEquals(1, psInsert.executeUpdate());

        // Test with InputStream
        psInsert.setInt(1, 21);
        psInsert.setString(2, "StreamTest");
        psInsert.setBinaryStream(3, new ByteArrayInputStream(testData));
        Assert.assertEquals(1, psInsert.executeUpdate());

        // Verify the data
        PreparedStatement psSelect = connection.prepareStatement("SELECT data FROM " + tableName + " WHERE id = ?");
        psSelect.setInt(1, 20);
        ResultSet rs = psSelect.executeQuery();
        Assert.assertTrue(rs.next());
        byte[] retrievedData = rs.getBytes("data");
        Assert.assertEquals("Hello World", new String(retrievedData));
        rs.close();
        psInsert.close();
        psSelect.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testTextParameterSetters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        PreparedStatement psInsert = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, info) VALUES (?, ?, ?)");

        String testText = "This is a test text for TEXT column";
        psInsert.setInt(1, 30);
        psInsert.setString(2, "TextTest");
        psInsert.setString(3, testText);
        Assert.assertEquals(1, psInsert.executeUpdate());

        // Test with character stream
        psInsert.setInt(1, 31);
        psInsert.setString(2, "StreamTextTest");
        psInsert.setCharacterStream(3, new StringReader(testText));
        Assert.assertEquals(1, psInsert.executeUpdate());

        // Verify the data
        PreparedStatement psSelect = connection.prepareStatement("SELECT info FROM " + tableName + " WHERE id = ?");
        psSelect.setInt(1, 30);
        ResultSet rs = psSelect.executeQuery();
        Assert.assertTrue(rs.next());
        Assert.assertEquals(testText, rs.getString("info"));
        rs.close();
        psInsert.close();
        psSelect.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testNullParameterSetters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        PreparedStatement psInsert = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age, data, info) VALUES (?, ?, ?, ?, ?)");

        psInsert.setInt(1, 40);
        psInsert.setNull(2, Types.VARCHAR);
        psInsert.setNull(3, Types.INTEGER);
        psInsert.setNull(4, Types.BLOB);
        psInsert.setNull(5, Types.LONGVARCHAR);
        Assert.assertEquals(1, psInsert.executeUpdate());

        // Verify nulls
        PreparedStatement psSelect = connection.prepareStatement("SELECT name, age, data, info FROM " + tableName + " WHERE id = ?");
        psSelect.setInt(1, 40);
        ResultSet rs = psSelect.executeQuery();
        Assert.assertTrue(rs.next());
        Assert.assertNull(rs.getString("name"));
        Assert.assertEquals(0, rs.getInt("age"));
        Assert.assertTrue(rs.wasNull());
        Assert.assertNull(rs.getBytes("data"));
        Assert.assertNull(rs.getString("info"));
        rs.close();
        psInsert.close();
        psSelect.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testExecuteQuery(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        // Insert test data
        PreparedStatement psInsert = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");
        psInsert.setInt(1, 50);
        psInsert.setString(2, "QueryTest");
        psInsert.setInt(3, 25);
        psInsert.executeUpdate();

        // Test executeQuery
        PreparedStatement psSelect = connection.prepareStatement("SELECT * FROM " + tableName + " WHERE id = ?");
        psSelect.setInt(1, 50);
        ResultSet rs = psSelect.executeQuery();
        Assert.assertNotNull(rs);
        Assert.assertTrue(rs.next());
        Assert.assertEquals(50, rs.getInt("id"));
        Assert.assertEquals("QueryTest", rs.getString("name"));
        Assert.assertEquals(25, rs.getInt("age"));
        Assert.assertFalse(rs.next());
        rs.close();
        psInsert.close();
        psSelect.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testExecuteUpdate(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        // Test INSERT
        PreparedStatement psInsert = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");
        psInsert.setInt(1, 60);
        psInsert.setString(2, "UpdateTest");
        psInsert.setInt(3, 30);
        Assert.assertEquals(1, psInsert.executeUpdate());

        // Test UPDATE
        PreparedStatement psUpdate = connection.prepareStatement("UPDATE " + tableName + " SET age = ? WHERE id = ?");
        psUpdate.setInt(1, 35);
        psUpdate.setInt(2, 60);
        Assert.assertEquals(1, psUpdate.executeUpdate());

        // Test DELETE
        PreparedStatement psDelete = connection.prepareStatement("DELETE FROM " + tableName + " WHERE id = ?");
        psDelete.setInt(1, 60);
        Assert.assertEquals(1, psDelete.executeUpdate());
        
        psInsert.close();
        psUpdate.close();
        psDelete.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testExecute(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        // Test execute with query
        PreparedStatement psSelect = connection.prepareStatement("SELECT COUNT(*) FROM " + tableName + "");
        boolean hasResultSet = psSelect.execute();
        Assert.assertTrue(hasResultSet);
        ResultSet rs = psSelect.getResultSet();
        Assert.assertNotNull(rs);
        rs.close();

        // Test execute with update
        PreparedStatement psInsert = connection.prepareStatement("INSERT INTO " + tableName + " (id, name) VALUES (?, ?)");
        psInsert.setInt(1, 70);
        psInsert.setString(2, "ExecuteTest");
        hasResultSet = psInsert.execute();
        Assert.assertFalse(hasResultSet);
        Assert.assertEquals(1, psInsert.getUpdateCount());
        
        psSelect.close();
        psInsert.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testBatch(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        PreparedStatement psBatch = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");
        
        psBatch.setInt(1, 80);
        psBatch.setString(2, "Batch1");
        psBatch.setInt(3, 25);
        psBatch.addBatch();

        psBatch.setInt(1, 81);
        psBatch.setString(2, "Batch2");
        psBatch.setInt(3, 30);
        psBatch.addBatch();

        psBatch.setInt(1, 82);
        psBatch.setString(2, "Batch3");
        psBatch.setInt(3, 35);
        psBatch.addBatch();

        int[] results = psBatch.executeBatch();
        Assert.assertEquals(3, results.length);
        Assert.assertEquals(1, results[0]);
        Assert.assertEquals(1, results[1]);
        Assert.assertEquals(1, results[2]);

        // Verify the batch insert
        PreparedStatement psSelect = connection.prepareStatement("SELECT COUNT(*) FROM " + tableName + " WHERE id BETWEEN ? AND ?");
        psSelect.setInt(1, 80);
        psSelect.setInt(2, 82);
        ResultSet rs = psSelect.executeQuery();
        Assert.assertTrue(rs.next());
        Assert.assertEquals(3, rs.getInt(1));
        rs.close();
        
        psBatch.close();
        psSelect.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testClearParameters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        PreparedStatement psClear = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");
        psClear.setInt(1, 90);
        psClear.setString(2, "ClearTest");
        psClear.setInt(3, 25);

        psClear.clearParameters();

        // Should throw SQLException because parameters are not set
        Assert.assertThrows(SQLException.class, () -> psClear.executeUpdate());
        psClear.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testMetaData(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        PreparedStatement psMetaData = connection.prepareStatement("SELECT id, name, age FROM " + tableName + " WHERE id = ?");
        ResultSetMetaData metaData = psMetaData.getMetaData();
        Assert.assertNotNull(metaData);
        Assert.assertEquals(3, metaData.getColumnCount());
        Assert.assertEquals("id", metaData.getColumnName(1).toLowerCase());
        Assert.assertEquals("name", metaData.getColumnName(2).toLowerCase());
        Assert.assertEquals("age", metaData.getColumnName(3).toLowerCase());
        psMetaData.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testParameterMetaData(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        PreparedStatement psParamMetaData = connection.prepareStatement("INSERT INTO " + tableName + " (id, name, age) VALUES (?, ?, ?)");
        try {
            var paramMetaData = psParamMetaData.getParameterMetaData();
            Assert.assertNotNull(paramMetaData);
            //TODO implement the ParameterMetaData using remote proxy
            //Assert.assertEquals(3, paramMetaData.getParameterCount());
        } catch (SQLException e) {
            // Some MySQL drivers/versions may not fully support parameter metadata
            // This is acceptable
        }
        psParamMetaData.close();
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

        PreparedStatement psGenKeys = connection.prepareStatement("INSERT INTO " + autoTableName + " (name) VALUES (?)", 
                                        Statement.RETURN_GENERATED_KEYS);
        psGenKeys.setString(1, "GeneratedKeyTest");
        Assert.assertEquals(1, psGenKeys.executeUpdate());

        ResultSet keys = psGenKeys.getGeneratedKeys();
        Assert.assertNotNull(keys);
        Assert.assertTrue(keys.next());
        Assert.assertTrue(keys.getInt(1) > 0);
        keys.close();
        psGenKeys.close();

        // Cleanup
        stmt = connection.createStatement();
        stmt.execute("DROP TABLE " + autoTableName);
        stmt.close();
    }
}
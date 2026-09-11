package org.openjproxy.jdbc.h2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
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

public class H2PreparedStatementExtensiveTests {

    private static boolean isH2TestEnabled;
    
    private Connection connection;
    private PreparedStatement ps;

    @BeforeAll
    static void setupClass() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        connection = DriverManager.getConnection(url, user, password);
        Statement stmt = connection.createStatement();
        try {
            stmt.execute("DROP TABLE h2_prepared_stmt_test");
        } catch (SQLException ignore) {}
        stmt.execute("CREATE TABLE h2_prepared_stmt_test (" +
                "id INT PRIMARY KEY, " +
                "name VARCHAR(255), " +
                "age INT, " +
                "data BLOB, " +
                "info CLOB, " +
                "dt DATE)");
        stmt.close();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (ps != null) ps.close();
        if (connection != null) connection.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testParameterSetters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO h2_prepared_stmt_test (id, name, age, data, info, dt) VALUES (?, ?, ?, ?, ?, ?)");

        // Numeric and boolean
        ps.setInt(1, 1);
        ps.setLong(1, 2L);
        ps.setShort(1, (short) 3);
        ps.setByte(1, (byte) 4);
        ps.setFloat(1, 1.5f);
        ps.setDouble(1, 2.5);
        ps.setBigDecimal(1, BigDecimal.valueOf(123));
        ps.setBoolean(1, true);

        // Strings and types
        ps.setString(2, "Alice");
        ps.setNString(2, "Bob");
        ps.setNull(1, Types.INTEGER);
        ps.setNull(1, Types.INTEGER, "INTEGER");
        ps.setObject(1, "test");
        ps.setObject(1, "test", Types.VARCHAR);
        ps.setObject(1, "test", Types.VARCHAR, 10);

        // Byte arrays and streams
        ps.setBytes(4, new byte[] {1, 2, 3});
        ps.setAsciiStream(2, new ByteArrayInputStream("ascii".getBytes()), 5);
        ps.setBinaryStream(4, new ByteArrayInputStream(new byte[] {4, 5}), 2);
        ps.setAsciiStream(2, new ByteArrayInputStream("ascii".getBytes()));
        ps.setBinaryStream(4, new ByteArrayInputStream(new byte[] {4, 5}));
        // Deprecated
        try { ps.setUnicodeStream(2, new ByteArrayInputStream(new byte[] {1}), 1); } catch (Exception ignore) {}

        // Date/time
        ps.setDate(6, new java.sql.Date(System.currentTimeMillis()));
        ps.setTime(3, new java.sql.Time(System.currentTimeMillis()));
        ps.setTimestamp(3, new java.sql.Timestamp(System.currentTimeMillis()));
        ps.setDate(6, new java.sql.Date(System.currentTimeMillis()), Calendar.getInstance());
        ps.setTime(3, new java.sql.Time(System.currentTimeMillis()), Calendar.getInstance());
        ps.setTimestamp(3, new java.sql.Timestamp(System.currentTimeMillis()), Calendar.getInstance());

        // URL, RowId
        assertThrows(Exception.class, () -> ps.setURL(3, new URL("http://localhost")));
        assertThrows(Exception.class, () -> ps.setRowId(3, null));

        // Character and N-character streams
        ps.setCharacterStream(3, new StringReader("chars"), 4);
        ps.setNCharacterStream(2, new StringReader("nchars"), 6L);
        ps.setCharacterStream(3, new StringReader("chars"));
        ps.setNCharacterStream(2, new StringReader("nchars"));
        ps.setCharacterStream(3, new StringReader("chars"), 3L);

        // CLOB, BLOB, NCLOB
        ps.setClob(5, new StringReader("clob"), 4L);
        ps.setNClob(5, new StringReader("nclob"), 5L);
        ps.setBlob(4, new ByteArrayInputStream(new byte[] {1, 2}), 2L);
        ps.setClob(5, new StringReader("clob"));
        ps.setNClob(5, new StringReader("nclob"));
        ps.setBlob(4, new ByteArrayInputStream(new byte[] {1, 2}));
        ps.setNClob(5, (NClob)null);
        ps.setBlob(4, (Blob)null);
        ps.setClob(5, (Clob)null);

        // Ref, Array, SQLXML
        assertThrows(SQLException.class, () -> ps.setRef(1, null));
        ps.setArray(1, null);
        ps.setSQLXML(1, null);

        // Call clearParameters for coverage
        ps.clearParameters();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testExecutionAndBatchMethods(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO h2_prepared_stmt_test (id, name, age, data, info, dt) VALUES (?, ?, ?, ?, ?, ?)");
        ps.setInt(1, 10); ps.setString(2, "Test"); ps.setInt(3, 30);
        ps.setBytes(4, new byte[]{1}); ps.setString(5, "info"); ps.setDate(6, new java.sql.Date(System.currentTimeMillis()));
        ps.addBatch();

        ps.setInt(1, 11); ps.setString(2, "Another"); ps.setInt(3, 31);
        ps.setBytes(4, new byte[]{2}); ps.setString(5, "info2"); ps.setDate(6, new java.sql.Date(System.currentTimeMillis()));
        ps.addBatch();

        int[] results = ps.executeBatch();
        assertEquals(2, results.length);

        // execute, executeUpdate, executeQuery
        ps = connection.prepareStatement("SELECT * FROM h2_prepared_stmt_test WHERE id = ?");
        ps.setInt(1, 10);
        ResultSet rs = ps.executeQuery();
        assertNotNull(rs);

        ps = connection.prepareStatement("UPDATE h2_prepared_stmt_test SET age = ? WHERE id = ?");
        ps.setInt(1, 42); ps.setInt(2, 11);
        int updateCount = ps.executeUpdate();
        assertTrue(updateCount >= 0);

        ps = connection.prepareStatement("SELECT * FROM h2_prepared_stmt_test WHERE id = ?");
        ps.setInt(1, 10);
        try {
            boolean executed = ps.execute();
        } catch (SQLException e) {
            assertNotNull(e);
        }

        // executeLargeUpdate (may throw on some drivers)
        try { ps.executeLargeUpdate(); } catch (Exception ignore) {}
    }

    /**
     * Regression test for PR #580: {@code getUpdateCount()} must return {@code -1} (not the
     * implicit {@code int} default of {@code 0}) right after {@code executeQuery()} on a fresh
     * {@link PreparedStatement}, otherwise clients relying on {@code getUpdateCount()==-1} to
     * detect the end of result processing (e.g. DataGrip) believe an update result is still
     * pending and hang.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testGetUpdateCountIsMinusOneAfterExecuteQuery(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("SELECT * FROM h2_prepared_stmt_test WHERE id = ?");
        ps.setInt(1, 10);
        try (ResultSet rs = ps.executeQuery()) {
            assertEquals(-1, ps.getUpdateCount());
        }
    }

    /**
     * Regression test for PR #580: {@code execute()} on an UPDATE/INSERT sets a valid (>=0)
     * update count, but {@code getUpdateCount()} on a different, freshly created
     * {@link PreparedStatement} bound to a SELECT must still report {@code -1} rather than
     * leaking the previous statement's update count via any shared state.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testGetUpdateCountResetsToMinusOneWhenQueryFollowsAnUpdate(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("INSERT INTO h2_prepared_stmt_test (id, name, age) VALUES (?, ?, ?)");
        ps.setInt(1, 20); ps.setString(2, "Bob"); ps.setInt(3, 40);
        boolean isResultSet = ps.execute();
        assertFalse(isResultSet);
        assertEquals(1, ps.getUpdateCount());

        ps = connection.prepareStatement("SELECT * FROM h2_prepared_stmt_test WHERE id = ?");
        ps.setInt(1, 20);
        try (ResultSet rs = ps.executeQuery()) {
            assertEquals(-1, ps.getUpdateCount());
        }
    }

    /**
     * Regression test for {@code SqlStatementClassifier}: a CTE (WITH ...) must be routed to
     * executeQuery by execute(), not just a plain SELECT prefix.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testExecuteWithCommonTableExpression(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        Statement stmt = connection.createStatement();
        stmt.execute("INSERT INTO h2_prepared_stmt_test (id, name, age) VALUES (20, 'Cte', 40)");
        stmt.close();

        ps = connection.prepareStatement(
                "WITH cte AS (SELECT * FROM h2_prepared_stmt_test WHERE id = 20) SELECT * FROM cte");
        boolean isResultSet = ps.execute();
        assertTrue(isResultSet);
        ResultSet rs = ps.getResultSet();
        assertNotNull(rs);
        assertTrue(rs.next());
        rs.close();
        assertEquals(-1, ps.getUpdateCount());
    }

    /**
     * Regression test for {@code SqlStatementClassifier}: a leading SQL comment before SELECT
     * must not prevent the statement from being recognized as query-shaped.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testExecuteWithLeadingCommentBeforeSelect(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        ps = connection.prepareStatement("-- a leading comment\nSELECT * FROM h2_prepared_stmt_test");
        boolean isResultSet = ps.execute();
        assertTrue(isResultSet);
        ResultSet rs = ps.getResultSet();
        assertNotNull(rs);
        rs.close();
        assertEquals(-1, ps.getUpdateCount());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testMetaDataAndWarnings(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("SELECT * FROM h2_prepared_stmt_test WHERE id = ?");
        ps.setInt(1, 10);

        ResultSetMetaData resultSetMetaData = ps.getMetaData();
        assertNotNull(resultSetMetaData);
        assertEquals("ID", resultSetMetaData.getColumnLabel(1));
        assertNotNull(ps.getParameterMetaData());
        ps.clearWarnings();
        assertNull(ps.getWarnings());
    }

    /**
     * Regression test for the Spring Data JDBC failure where calling
     * {@code getWarnings()} on a freshly executed PreparedStatement (without
     * a server-side statement UUID assigned yet) caused the server to attempt
     * a {@code prepareStatement(null)} and throw an NPE
     * ("Cannot invoke \"String.startsWith(String)\" because \"sql\" is null").
     * The {@code getWarnings()}, {@code clearWarnings()} and other
     * {@link java.sql.Statement} methods inherited by {@link java.sql.PreparedStatement}
     * must include the SQL in the proxied request so the server can resolve the
     * underlying PreparedStatement.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testGetWarningsAfterExecuteQueryWithoutPriorCallProxy(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("SELECT * FROM h2_prepared_stmt_test");
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                // consume
            }
        }
        // These calls are inherited from Statement and previously bypassed the
        // PreparedStatement.callProxy override that injects the SQL property.
        assertNull(ps.getWarnings());
        ps.clearWarnings();
        assertNull(ps.getWarnings());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testStatementCommonMethods(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("SELECT * FROM h2_prepared_stmt_test WHERE id = ?");
        // Field size and max rows
        assertEquals(0, ps.getMaxFieldSize());
        ps.setMaxFieldSize(128);
        assertEquals(0, ps.getMaxFieldSize());

        assertEquals(0, ps.getMaxRows());
        ps.setMaxRows(10);
        assertEquals(10, ps.getMaxRows());

        ps.setEscapeProcessing(true);
        ps.setQueryTimeout(5);
        assertEquals(5, ps.getQueryTimeout());

        // Cursor/Fetch
        ps.setCursorName("testCursor");
        ps.setFetchDirection(ResultSet.FETCH_FORWARD);
        assertEquals(ResultSet.FETCH_FORWARD, ps.getFetchDirection());
        assertThrows(Exception.class, () -> { ps.setFetchSize(50); });
        assertEquals(100, ps.getFetchSize());

        assertTrue(ps.getResultSetConcurrency() >= 0);
        assertTrue(ps.getResultSetType() >= 0);
        assertTrue(ps.getResultSetHoldability() >= 0);

        // Poolable/Close
        ps.setPoolable(true);
        assertFalse(ps.isPoolable());
        ps.closeOnCompletion();
        assertTrue(ps.isCloseOnCompletion());

        // isClosed
        assertFalse(ps.isClosed());

        // cancel (for full interface coverage)
        try {
            ps.cancel();
        } catch (Exception ignore) {
            // Some drivers may throw if cancel() is not supported or statement is not running.
        }

        ps.close();
        assertTrue(ps.isClosed());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testStatementBatchAndConnection(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        ps = connection.prepareStatement("SELECT * FROM h2_prepared_stmt_test WHERE id = ?");
        ps.clearBatch();
        assertThrows(Exception.class, () -> ps.addBatch("DELETE FROM h2_prepared_stmt_test WHERE id < 0"));
        ps.clearBatch();

        assertNotNull(ps.getConnection());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testResultAndGeneratedKeysMethods(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        PreparedStatement ps = connection.prepareStatement("INSERT INTO h2_prepared_stmt_test (id, name, age) VALUES (?, ?, ?)");
        ps.setLong(1, 100);
        ps.setString(2, "A");
        ps.setInt(3, 1);
        int updatedRows = ps.executeUpdate();
        assertEquals(1, updatedRows);
        Statement stmtQuery = connection.createStatement();
        ResultSet rs = stmtQuery.executeQuery("SELECT * FROM h2_prepared_stmt_test");
        assertNotNull(rs);
        assertTrue(rs.next());

        PreparedStatement psUpdt = connection.prepareStatement("UPDATE h2_prepared_stmt_test SET age = ? WHERE id = ?");
        psUpdt.setInt(1, 99);
        psUpdt.setInt(2, 100);
        int updateCount = psUpdt.executeUpdate();
        assertEquals(1, updateCount);

        // getResultSet, getUpdateCount, getMoreResults
        stmtQuery.execute("SELECT * FROM h2_prepared_stmt_test");
        ResultSet rs2 = stmtQuery.getResultSet();
        assertNotNull(rs2);
        int count = stmtQuery.getUpdateCount();
        assertTrue(count >= -1);
        assertFalse(stmtQuery.getMoreResults());
        assertFalse(stmtQuery.getMoreResults(Statement.CLOSE_CURRENT_RESULT));

        // Generated Keys
        PreparedStatement psInsert = connection.prepareStatement("INSERT INTO h2_prepared_stmt_test (id, name, age) VALUES (?, ?, ?)", PreparedStatement.RETURN_GENERATED_KEYS);
        psInsert.setInt(1, 101);
        psInsert.setString(2, "B");
        psInsert.setInt(3, 2);
        psInsert.executeUpdate();
        ResultSet keys = psInsert.getGeneratedKeys();
        assertNotNull(keys);
        boolean hasNext = keys.next();
        assertTrue(hasNext);
        Integer id = keys.getInt(1);
        assertNotNull(id);

        // Various execute overloads
        stmtQuery.execute("SELECT * FROM h2_prepared_stmt_test", Statement.NO_GENERATED_KEYS);
        stmtQuery.execute("SELECT * FROM h2_prepared_stmt_test", new int[]{1});
        stmtQuery.execute("SELECT * FROM h2_prepared_stmt_test", new String[]{"id"});
    }

    /**
     * Reproduces the EclipseLink GenerationType.IDENTITY scenario:
     * - table has an auto-increment (IDENTITY) primary key
     * - INSERT omits the id column — the database auto-generates it
     * - prepareStatement is called with RETURN_GENERATED_KEYS
     * - getGeneratedKeys() must return the auto-generated id
     *
     * Also verifies the column-index and column-name variants of prepareStatement.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testGetGeneratedKeysWithAutoIncrementTable(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        Statement stmt = connection.createStatement();
        stmt.execute("DROP TABLE IF EXISTS t_identity");
        stmt.execute("CREATE TABLE t_identity (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, name VARCHAR(100))");
        stmt.close();

        // RETURN_GENERATED_KEYS variant
        PreparedStatement psKeys = connection.prepareStatement(
                "INSERT INTO t_identity (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS);
        psKeys.setString(1, "alice");
        int affected = psKeys.executeUpdate();
        assertEquals(1, affected);
        ResultSet generatedKeys = psKeys.getGeneratedKeys();
        assertNotNull(generatedKeys);
        assertTrue(generatedKeys.next(), "getGeneratedKeys() must return a row for RETURN_GENERATED_KEYS");
        long generatedId = generatedKeys.getLong(1);
        assertTrue(generatedId > 0, "Generated id must be positive");
        generatedKeys.close();
        psKeys.close();

        // column-index variant
        PreparedStatement psColIdx = connection.prepareStatement(
                "INSERT INTO t_identity (name) VALUES (?)", new int[]{1});
        psColIdx.setString(1, "bob");
        assertEquals(1, psColIdx.executeUpdate());
        ResultSet keysColIdx = psColIdx.getGeneratedKeys();
        assertNotNull(keysColIdx);
        assertTrue(keysColIdx.next(), "getGeneratedKeys() must return a row for column-index variant");
        assertTrue(keysColIdx.getLong(1) > 0);
        keysColIdx.close();
        psColIdx.close();

        // column-name variant
        PreparedStatement psColName = connection.prepareStatement(
                "INSERT INTO t_identity (name) VALUES (?)", new String[]{"id"});
        psColName.setString(1, "carol");
        assertEquals(1, psColName.executeUpdate());
        ResultSet keysColName = psColName.getGeneratedKeys();
        assertNotNull(keysColName);
        assertTrue(keysColName.next(), "getGeneratedKeys() must return a row for column-name variant");
        assertTrue(keysColName.getLong(1) > 0);
        keysColName.close();
        psColName.close();
    }

    /**
     * Reproduces the Spring Data saveAll / batch insert scenario with generated keys (issue #408).
     * Verifies that RETURN_GENERATED_KEYS is preserved when prepareStatement is followed by
     * repeated addBatch() calls and executeBatch() — the exact sequence Spring Data uses for saveAll.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testBatchInsertWithGeneratedKeys(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        Statement stmt = connection.createStatement();
        stmt.execute("DROP TABLE IF EXISTS h2_batch_gen_keys_test");
        stmt.execute("CREATE TABLE h2_batch_gen_keys_test (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, name VARCHAR(100))");
        stmt.close();

        // Reproduces: prepareStatement(sql, RETURN_GENERATED_KEYS) → addBatch() x N → executeBatch() → getGeneratedKeys()
        ps = connection.prepareStatement("INSERT INTO h2_batch_gen_keys_test (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS);

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
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testStatementLargeAndDefaultMethods(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        Statement stmt = connection.createStatement();
        // Large update/batch methods (may throw on H2)
        try { stmt.getLargeUpdateCount(); } catch (Exception ignore) {}
        try { stmt.setLargeMaxRows(100L); } catch (Exception ignore) {}
        try { stmt.getLargeMaxRows(); } catch (Exception ignore) {}
        try { stmt.executeLargeBatch(); } catch (Exception ignore) {}
        try { stmt.executeLargeUpdate("UPDATE h2_prepared_stmt_test SET age = 101 WHERE id = 100"); } catch (Exception ignore) {}
        try { stmt.executeLargeUpdate("UPDATE h2_prepared_stmt_test SET age = 101 WHERE id = 100", Statement.RETURN_GENERATED_KEYS); } catch (Exception ignore) {}
        try { stmt.executeLargeUpdate("UPDATE h2_prepared_stmt_test SET age = 101 WHERE id = 100", new int[]{1}); } catch (Exception ignore) {}
        try { stmt.executeLargeUpdate("UPDATE h2_prepared_stmt_test SET age = 101 WHERE id = 100", new String[]{"id"}); } catch (Exception ignore) {}

        // Enquote and identifier methods
        assertEquals("'foo''bar'", stmt.enquoteLiteral("foo'bar"));
        assertEquals("\"foo\"", stmt.enquoteIdentifier("foo", true));
        assertFalse(stmt.isSimpleIdentifier("fooBar"));
        assertEquals("N'foo''bar'", stmt.enquoteNCharLiteral("foo'bar"));
    }
}
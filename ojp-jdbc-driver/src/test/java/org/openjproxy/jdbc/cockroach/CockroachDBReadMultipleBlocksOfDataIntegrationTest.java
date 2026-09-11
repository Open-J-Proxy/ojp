package org.openjproxy.jdbc.cockroach;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.openjproxy.grpc.helpers.SqlHelper.executeUpdate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * CockroachDB-specific multiple blocks of data integration tests.
 * Tests CockroachDB pagination and large result set handling.
 */
class CockroachDBReadMultipleBlocksOfDataIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(CockroachDBReadMultipleBlocksOfDataIntegrationTest.class);

    private static boolean isTestEnabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enableCockroachDBTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testCockroachDBMultiplePagesOfRows(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(!isTestEnabled, "Skipping CockroachDB tests");
        logger.info("Testing temporay table with Driver: {}", driverClass);
        Connection conn = DriverManager.getConnection(url, user, pwd);

        int totalRecords = 1000;
        System.out.println("Testing CockroachDB retrieving " + totalRecords + " records from url -> " + url);

        try {
            executeUpdate(conn, "DROP TABLE cockroachdb_read_blocks_test_multi");
        } catch (Exception e) {
            //Does not matter
        }

        // Create table with CockroachDB-specific syntax
        executeUpdate(conn, "CREATE TABLE cockroachdb_read_blocks_test_multi(" +
                "id INT NOT NULL PRIMARY KEY, " +
                "title VARCHAR(50) NOT NULL)");

        for (int i = 0; i < totalRecords; i++) {
            executeUpdate(conn,
                    "INSERT INTO cockroachdb_read_blocks_test_multi (id, title) VALUES (" + i + ", 'COCKROACHDB_TITLE_" + i + "')"
            );
        }

        java.sql.PreparedStatement psSelect = conn.prepareStatement("SELECT * FROM cockroachdb_read_blocks_test_multi ORDER BY id");
        ResultSet resultSet = psSelect.executeQuery();

        for (int i = 0; i < totalRecords; i++) {
            resultSet.next();
            int id = resultSet.getInt(1);
            String title = resultSet.getString(2);
            assertEquals(i, id);
            assertEquals("COCKROACHDB_TITLE_" + i, title);
        }

        executeUpdate(conn, "DELETE FROM cockroachdb_read_blocks_test_multi");

        ResultSet resultSetAfterDeletion = psSelect.executeQuery();
        assertFalse(resultSetAfterDeletion.next());

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testCockroachDBLargeDataSetPagination(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(!isTestEnabled, "Skipping CockroachDB tests");
        logger.info("Testing temporay table with Driver: {}", driverClass);
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing CockroachDB large dataset pagination for url -> " + url);

        try {
            executeUpdate(conn, "DROP TABLE cockroachdb_pagination_test");
        } catch (Exception e) {
            //Does not matter
        }

        // Create table with CockroachDB-specific data types
        executeUpdate(conn, "CREATE TABLE cockroachdb_pagination_test(" +
                "id INT PRIMARY KEY, " +
                "name VARCHAR(100) NOT NULL, " +
                "value DECIMAL(19,2), " +
                "description TEXT)");

        // Insert 5000 records for pagination testing
        int totalRecords = 5000;
        for (int i = 1; i <= totalRecords; i++) {
            executeUpdate(conn,
                    "INSERT INTO cockroachdb_pagination_test (id, name, value, description) " +
                            "VALUES (" + i + ", 'Name_" + i + "', " + (i * 10.5) + ", 'Description for record " + i + "')"
            );
        }

        // Test pagination with LIMIT and OFFSET
        int pageSize = 100;
        int totalPages = totalRecords / pageSize;

        for (int page = 0; page < totalPages; page++) {
            int offset = page * pageSize;
            java.sql.PreparedStatement psSelect = conn.prepareStatement(
                    "SELECT * FROM cockroachdb_pagination_test ORDER BY id LIMIT " + pageSize + " OFFSET " + offset
            );
            ResultSet resultSet = psSelect.executeQuery();

            int count = 0;
            while (resultSet.next()) {
                int expectedId = offset + count + 1;
                assertEquals(expectedId, resultSet.getInt("id"));
                assertEquals("Name_" + expectedId, resultSet.getString("name"));
                count++;
            }

            assertEquals(pageSize, count);
            resultSet.close();
            psSelect.close();
        }

        executeUpdate(conn, "DROP TABLE cockroachdb_pagination_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testCockroachDBLargeResultSetWithVariousTypes(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(!isTestEnabled, "Skipping CockroachDB tests");
        logger.info("Testing temporay table with Driver: {}", driverClass);
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing CockroachDB large result set with various types for url -> " + url);

        try {
            executeUpdate(conn, "DROP TABLE cockroachdb_large_types_test");
        } catch (Exception e) {
            //Does not matter
        }

        // Create table with various CockroachDB data types
        executeUpdate(conn, "CREATE TABLE cockroachdb_large_types_test(" +
                "id SERIAL PRIMARY KEY, " +
                "int_val INT, " +
                "bigint_val BIGINT, " +
                "float_val FLOAT, " +
                "decimal_val DECIMAL(15,3), " +
                "text_val TEXT, " +
                "bool_val BOOLEAN, " +
                "timestamp_val TIMESTAMP)");

        // Insert 2000 records
        int totalRecords = 2000;
        for (int i = 1; i <= totalRecords; i++) {
            executeUpdate(conn,
                    "INSERT INTO cockroachdb_large_types_test " +
                            "(int_val, bigint_val, float_val, decimal_val, text_val, bool_val, timestamp_val) " +
                            "VALUES (" + i + ", " + (i * 1000L) + ", " + (i * 1.5) + ", " + (i * 100.123) + ", " +
                            "'Text value for record " + i + "', " + (i % 2 == 0) + ", CURRENT_TIMESTAMP)"
            );
        }

        // Retrieve all records and verify
        java.sql.PreparedStatement psSelect = conn.prepareStatement(
                "SELECT * FROM cockroachdb_large_types_test ORDER BY id"
        );
        ResultSet resultSet = psSelect.executeQuery();

        int count = 0;
        while (resultSet.next()) {
            count++;
            // Verify some fields
            assertEquals(count, resultSet.getInt("int_val"));
            assertEquals(count * 1000L, resultSet.getLong("bigint_val"));
            assertEquals("Text value for record " + count, resultSet.getString("text_val"));
            assertEquals(count % 2 == 0, resultSet.getBoolean("bool_val"));
        }

        assertEquals(totalRecords, count);

        resultSet.close();
        psSelect.close();
        executeUpdate(conn, "DROP TABLE cockroachdb_large_types_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testCockroachDBFetchSizePerformance(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(!isTestEnabled, "Skipping CockroachDB tests");
        logger.info("Testing temporay table with Driver: {}", driverClass);
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing CockroachDB fetch size performance for url -> " + url);

        try {
            executeUpdate(conn, "DROP TABLE cockroachdb_fetch_size_test");
        } catch (Exception e) {
            //Does not matter
        }

        executeUpdate(conn, "CREATE TABLE cockroachdb_fetch_size_test(" +
                "id SERIAL PRIMARY KEY, " +
                "data VARCHAR(255))");

        // Insert 3000 records
        int totalRecords = 3000;
        for (int i = 1; i <= totalRecords; i++) {
            executeUpdate(conn,
                    "INSERT INTO cockroachdb_fetch_size_test (data) VALUES ('Data row " + i + "')"
            );
        }

        // Test with different fetch sizes
        int[] fetchSizes = {10, 50, 100, 500};

        for (int fetchSize : fetchSizes) {
            java.sql.PreparedStatement psSelect = conn.prepareStatement(
                    "SELECT * FROM cockroachdb_fetch_size_test ORDER BY id"
            );
            psSelect.setFetchSize(fetchSize);

            ResultSet resultSet = psSelect.executeQuery();

            int count = 0;
            while (resultSet.next()) {
                count++;
            }

            assertEquals(totalRecords, count);

            resultSet.close();
            psSelect.close();
        }

        executeUpdate(conn, "DROP TABLE cockroachdb_fetch_size_test");
        conn.close();
    }
}

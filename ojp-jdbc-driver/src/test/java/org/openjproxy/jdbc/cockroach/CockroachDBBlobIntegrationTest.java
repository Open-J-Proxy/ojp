package org.openjproxy.jdbc.cockroach;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.openjproxy.grpc.helpers.SqlHelper.executeUpdate;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * CockroachDB-specific BLOB integration tests.
 * Tests CockroachDB BYTEA functionality (equivalent to BLOB).
 */
class CockroachDBBlobIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(CockroachDBBlobIntegrationTest.class);
    private static boolean isTestEnabled;
    private String tableName;
    private Connection conn;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enableCockroachDBTests", "false"));
    }

    void setUp(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(!isTestEnabled, "CockroachDB tests are not enabled");
        logger.info("Testing temporay table with Driver: {}", driverClass);
        this.tableName = "cockroachdb_blob_test";
        conn = DriverManager.getConnection(url, user, pwd);

        try {
            executeUpdate(conn, "DROP TABLE " + tableName);
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }

        // Create table with CockroachDB BYTEA type (equivalent to BLOB)
        executeUpdate(conn, "CREATE TABLE " + tableName + " (" +
                "id INT PRIMARY KEY, " +
                "data_blob BYTEA)");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testCockroachDBBlobCreationAndRetrieval(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing CockroachDB BLOB creation and retrieval for url -> " + url);

        String testData = "CockroachDB BLOB test data - special characters: äöü ñ 中文 🚀";
        byte[] dataBytes = testData.getBytes(StandardCharsets.UTF_8);

        // Insert BLOB data
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 1);
        psInsert.setBinaryStream(2, new ByteArrayInputStream(dataBytes));
        psInsert.executeUpdate();

        // Retrieve and verify BLOB data
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT data_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 1);
        ResultSet rs = psSelect.executeQuery();

        assertTrue(rs.next());

        Blob blob = rs.getBlob(1);
        assertNotNull(blob);

        byte[] retrievedBytes = blob.getBytes(1, (int) blob.length());
        String retrievedData = new String(retrievedBytes, StandardCharsets.UTF_8);

        assertEquals(testData, retrievedData);
        assertEquals(dataBytes.length, blob.length());

        psInsert.close();
        psSelect.close();
        rs.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testCockroachDBLargeBlobHandling(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing CockroachDB large BLOB handling for url -> " + url);

        // Create a 3MB test data
        byte[] largeData = new byte[3 * 1024 * 1024];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }

        // Insert large BLOB
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 2);
        psInsert.setBinaryStream(2, new ByteArrayInputStream(largeData));
        psInsert.executeUpdate();

        // Retrieve and verify
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT data_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 2);
        ResultSet rs = psSelect.executeQuery();

        assertTrue(rs.next());

        Blob blob = rs.getBlob(1);
        assertNotNull(blob);
        assertEquals(largeData.length, blob.length());

        byte[] retrievedBytes = blob.getBytes(1, (int) blob.length());
        assertArrayEquals(largeData, retrievedBytes);

        psInsert.close();
        psSelect.close();
        rs.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testCockroachDBBlobUpdate(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing CockroachDB BLOB update for url -> " + url);

        String initialData = "Initial data";
        byte[] initialBytes = initialData.getBytes(StandardCharsets.UTF_8);

        // Insert initial data
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 3);
        psInsert.setBinaryStream(2, new ByteArrayInputStream(initialBytes));
        psInsert.executeUpdate();

        // Update with new data
        String updatedData = "Updated data - much longer than before!";
        byte[] updatedBytes = updatedData.getBytes(StandardCharsets.UTF_8);

        PreparedStatement psUpdate = conn.prepareStatement(
                "UPDATE " + tableName + " SET data_blob = ? WHERE id = ?"
        );
        psUpdate.setBinaryStream(1, new ByteArrayInputStream(updatedBytes));
        psUpdate.setInt(2, 3);
        psUpdate.executeUpdate();

        // Retrieve and verify updated data
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT data_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 3);
        ResultSet rs = psSelect.executeQuery();

        assertTrue(rs.next());

        Blob blob = rs.getBlob(1);
        assertNotNull(blob);

        byte[] retrievedBytes = blob.getBytes(1, (int) blob.length());
        String retrievedData = new String(retrievedBytes, StandardCharsets.UTF_8);

        assertEquals(updatedData, retrievedData);

        psInsert.close();
        psUpdate.close();
        psSelect.close();
        rs.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testCockroachDBBlobWithNullValue(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing CockroachDB BLOB with NULL value for url -> " + url);

        // Insert NULL BLOB
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 4);
        psInsert.setNull(2, java.sql.Types.BLOB);
        psInsert.executeUpdate();

        // Retrieve and verify NULL
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT data_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 4);
        ResultSet rs = psSelect.executeQuery();

        assertTrue(rs.next());

        Blob blob = rs.getBlob(1);
        assertNull(blob);

        psInsert.close();
        psSelect.close();
        rs.close();
        conn.close();
    }
}

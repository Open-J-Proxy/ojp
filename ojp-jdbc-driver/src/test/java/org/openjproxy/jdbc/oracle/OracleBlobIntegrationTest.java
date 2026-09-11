package org.openjproxy.jdbc.oracle;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.openjproxy.grpc.helpers.SqlHelper.executeUpdate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Oracle-specific BLOB integration tests.
 * Tests Oracle BLOB functionality and performance.
 */
class OracleBlobIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(OracleBlobIntegrationTest.class);
    private static boolean isTestDisabled;
    private String tableName;
    private Connection conn;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    void setUp(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");

        this.tableName = "oracle_blob_test";
        conn = DriverManager.getConnection(url, user, pwd);

        try {
            executeUpdate(conn, "DROP TABLE " + tableName);
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }

        // Create table with Oracle BLOB type
        executeUpdate(conn, "CREATE TABLE " + tableName + " (" +
                "id NUMBER(10) PRIMARY KEY, " +
                "data_blob BLOB)");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleBlobCreationAndRetrieval(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing Oracle BLOB creation and retrieval for url -> " + url);

        String testData = "Oracle BLOB test data - special characters: äöü ñ 中文 🚀";
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
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleLargeBlobHandling(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing Oracle large BLOB handling for url -> " + url);

        // Create a large test string (1MB)
        StringBuilder sb = new StringBuilder();
        String pattern = "Oracle large BLOB test pattern ";
        for (int i = 0; i < 32768; i++) { // Approximately 1MB
            sb.append(pattern).append(i).append("\n");
        }
        String largeTestData = sb.toString();
        byte[] largeDataBytes = largeTestData.getBytes(StandardCharsets.UTF_8);

        // Insert large BLOB data
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 2);
        psInsert.setBinaryStream(2, new ByteArrayInputStream(largeDataBytes));
        psInsert.executeUpdate();

        // Retrieve and verify large BLOB data
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT data_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 2);
        ResultSet rs = psSelect.executeQuery();

        assertTrue(rs.next());

        Blob blob = rs.getBlob(1);
        assertNotNull(blob);
        assertTrue(blob.length() > 1000000); // Should be > 1MB

        // Read first chunk to verify
        byte[] firstChunk = blob.getBytes(1, 1000);
        String firstChunkStr = new String(firstChunk, StandardCharsets.UTF_8);
        assertTrue(firstChunkStr.contains("Oracle large BLOB test pattern 0"));

        psInsert.close();
        psSelect.close();
        rs.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleBlobBinaryStream(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing Oracle BLOB binary stream for url -> " + url);

        // Test with binary data (not just text)
        byte[] binaryData = new byte[1000];
        for (int i = 0; i < binaryData.length; i++) {
            binaryData[i] = (byte) (i % 256);
        }

        // Insert binary data
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 3);
        psInsert.setBinaryStream(2, new ByteArrayInputStream(binaryData));
        psInsert.executeUpdate();

        // Retrieve and verify binary data
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT data_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 3);
        ResultSet rs = psSelect.executeQuery();

        assertTrue(rs.next());

        InputStream binaryStream = rs.getBinaryStream(1);
        assertNotNull(binaryStream);

        byte[] retrievedData = binaryStream.readAllBytes();
        assertEquals(binaryData.length, retrievedData.length);

        // Verify each byte
        for (int i = 0; i < binaryData.length; i++) {
            logger.info("Byte mismatch at position " + i);
            assertEquals(binaryData[i], retrievedData[i]);
        }

        psInsert.close();
        psSelect.close();
        rs.close();
        binaryStream.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleBlobUpdate(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing Oracle BLOB update for url -> " + url);

        String originalData = "Original Oracle BLOB data";
        String updatedData = "Updated Oracle BLOB data with more content";

        // Insert original data
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 4);
        psInsert.setBinaryStream(2, new ByteArrayInputStream(originalData.getBytes(StandardCharsets.UTF_8)));
        psInsert.executeUpdate();

        // Update BLOB data
        PreparedStatement psUpdate = conn.prepareStatement(
                "UPDATE " + tableName + " SET data_blob = ? WHERE id = ?"
        );
        psUpdate.setBinaryStream(1, new ByteArrayInputStream(updatedData.getBytes(StandardCharsets.UTF_8)));
        psUpdate.setInt(2, 4);
        psUpdate.executeUpdate();

        // Verify updated data
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT data_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 4);
        ResultSet rs = psSelect.executeQuery();

        assertTrue(rs.next());

        Blob blob = rs.getBlob(1);
        String retrievedData = new String(blob.getBytes(1, (int) blob.length()), StandardCharsets.UTF_8);

        assertEquals(updatedData, retrievedData);

        psInsert.close();
        psUpdate.close();
        psSelect.close();
        rs.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleEmptyAndNullBlob(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing Oracle empty and null BLOB for url -> " + url);

        // Insert empty BLOB
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 5);
        psInsert.setBinaryStream(2, null);
        psInsert.executeUpdate();

        // Insert NULL BLOB
        psInsert.setInt(1, 6);
        psInsert.setBlob(2, (Blob) null);
        psInsert.executeUpdate();

        // Verify empty BLOB
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT data_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 5);
        ResultSet rs = psSelect.executeQuery();

        assertTrue(rs.next());
        Blob blob = rs.getBlob(1);
        assertNull(blob);

        // Verify NULL BLOB
        psSelect.setInt(1, 6);
        rs = psSelect.executeQuery();
        assertTrue(rs.next());
        blob = rs.getBlob(1);
        assertNull(blob);

        psInsert.close();
        psSelect.close();
        rs.close();
        conn.close();
    }
}
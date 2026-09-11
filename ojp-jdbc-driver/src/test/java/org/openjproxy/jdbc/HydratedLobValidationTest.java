package org.openjproxy.jdbc;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

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
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to validate the hydrated LOB approach.
 * This test specifically validates that LOBs are handled as complete byte arrays
 * rather than streamed, ensuring consistent behavior across all databases.
 */
class HydratedLobValidationTest {

    private static boolean isH2TestEnabled;

    private String tableName;
    private Connection conn;

    @BeforeAll
    static void setupClass() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }

    void setUp(String driverClass, String url, String user, String pwd) throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        this.tableName = "hydrated_lob_test";
        conn = DriverManager.getConnection(url, user, pwd);

        try {
            executeUpdate(conn, "DROP TABLE " + tableName);
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }

        // Create table for testing hydrated LOB behavior
        executeUpdate(conn, "CREATE TABLE " + tableName + " (" +
                "id INTEGER NOT NULL PRIMARY KEY, " +
                "small_blob BLOB, " +
                "medium_blob BLOB, " +
                "large_blob BLOB)");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testHydratedLobBehavior(String driverClass, String url, String user, String pwd) throws SQLException, IOException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing hydrated LOB behavior for url -> " + url);

        // Test data of different sizes to validate hydrated approach
        byte[] smallData = "Small LOB data".getBytes(StandardCharsets.UTF_8);
        byte[] mediumData = "M".repeat(1000).getBytes(StandardCharsets.UTF_8); // 1KB
        byte[] largeData = "L".repeat(10000).getBytes(StandardCharsets.UTF_8); // 10KB

        // Insert LOBs of different sizes
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, small_blob, medium_blob, large_blob) VALUES (?, ?, ?, ?)"
        );
        psInsert.setInt(1, 1);
        psInsert.setBinaryStream(2, new ByteArrayInputStream(smallData), smallData.length);
        psInsert.setBinaryStream(3, new ByteArrayInputStream(mediumData), mediumData.length);
        psInsert.setBinaryStream(4, new ByteArrayInputStream(largeData), largeData.length);
        psInsert.executeUpdate();

        // Retrieve and verify all LOBs
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT small_blob, medium_blob, large_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 1);
        ResultSet rs = psSelect.executeQuery();

        assertTrue(rs.next(), "ResultSet should have data");

        // Test small BLOB - should be hydrated (loaded entirely in memory)
        Blob smallBlob = rs.getBlob("small_blob");
        assertNotNull(smallBlob, "Small BLOB should not be null");
        byte[] retrievedSmallData = smallBlob.getBinaryStream().readAllBytes();
        assertArrayEquals(smallData, retrievedSmallData, "Small BLOB data should match");

        // Test medium BLOB - should be hydrated
        Blob mediumBlob = rs.getBlob("medium_blob");
        assertNotNull(mediumBlob, "Medium BLOB should not be null");
        byte[] retrievedMediumData = mediumBlob.getBinaryStream().readAllBytes();
        assertArrayEquals(mediumData, retrievedMediumData, "Medium BLOB data should match");

        // Test large BLOB - should be hydrated (not streamed)
        Blob largeBlob = rs.getBlob("large_blob");
        assertNotNull(largeBlob, "Large BLOB should not be null");
        byte[] retrievedLargeData = largeBlob.getBinaryStream().readAllBytes();
        assertArrayEquals(largeData, retrievedLargeData, "Large BLOB data should match");

        // Validate that multiple reads of the same BLOB work (hydrated data should be reusable)
        byte[] secondRead = largeBlob.getBinaryStream().readAllBytes();
        assertArrayEquals(largeData, secondRead, "Second read of large BLOB should match");

        // Test BLOB length - should be available immediately (hydrated)
        assertEquals(smallData.length, smallBlob.length(), "Small BLOB length should be correct");
        assertEquals(mediumData.length, mediumBlob.length(), "Medium BLOB length should be correct");
        assertEquals(largeData.length, largeBlob.length(), "Large BLOB length should be correct");

        // Test getBytes method - should work with hydrated data
        byte[] partialData = largeBlob.getBytes(1, 100);
        assertEquals(100, partialData.length, "Partial data length should be 100");

        // Verify partial data matches the beginning of the original data
        for (int i = 0; i < 100; i++) {
            assertEquals(largeData[i], partialData[i],"Partial data should match original at position " + i);
        }

        // Cleanup
        rs.close();
        psSelect.close();
        psInsert.close();

        executeUpdate(conn, "DROP TABLE " + tableName);
        conn.close();

        System.out.println("Hydrated LOB validation completed successfully");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testHydratedBinaryStreamBehavior(String driverClass, String url, String user, String pwd) throws SQLException, IOException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing hydrated binary stream behavior for url -> " + url);

        // Test data that would previously require streaming
        String testString = "Hydrated binary stream test data with special chars: äöü ñ 中文 🚀";
        byte[] testData = testString.getBytes(StandardCharsets.UTF_8);

        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, small_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 2);
        psInsert.setBinaryStream(2, new ByteArrayInputStream(testData), testData.length);
        psInsert.executeUpdate();

        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT small_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 2);
        ResultSet rs = psSelect.executeQuery();

        assertTrue(rs.next(), "ResultSet should have data");

        // Test getBinaryStream returns the complete hydrated data
        InputStream binaryStream = rs.getBinaryStream("small_blob");
        assertNotNull(binaryStream, "Binary stream should not be null");

        byte[] retrievedData = binaryStream.readAllBytes();
        String retrievedString = new String(retrievedData, StandardCharsets.UTF_8);

        assertEquals(testString, retrievedString, "Retrieved string should match original");
        assertArrayEquals(testData, retrievedData, "Retrieved data should match original");

        // Cleanup
        rs.close();
        psSelect.close();
        psInsert.close();

        executeUpdate(conn, "DROP TABLE " + tableName);
        conn.close();

        System.out.println("Hydrated binary stream validation completed successfully");
    }
}
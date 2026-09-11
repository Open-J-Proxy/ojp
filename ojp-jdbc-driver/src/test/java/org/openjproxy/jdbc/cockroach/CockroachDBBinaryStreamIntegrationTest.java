package org.openjproxy.jdbc.cockroach;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * CockroachDB-specific binary stream integration tests.
 * Tests CockroachDB-specific binary data types (BYTEA) and stream handling.
 */
class CockroachDBBinaryStreamIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(CockroachDBBinaryStreamIntegrationTest.class);
    private static boolean isTestEnabled;

    @BeforeAll
    static void setup() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enableCockroachDBTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void createAndReadingBinaryStreamSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, IOException {
        logger.info("Testing temporay table with Driver: {}", driverClass);
        assumeFalse(!isTestEnabled, "Skipping CockroachDB tests");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing CockroachDB binary stream for url -> " + url);

        try {
            executeUpdate(conn, "DROP TABLE cockroachdb_binary_stream_test");
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Create table with CockroachDB-specific binary types
        executeUpdate(conn, "CREATE TABLE cockroachdb_binary_stream_test(" +
                " val_bytea1 BYTEA," +  // CockroachDB BYTEA for binary data
                " val_bytea2 BYTEA" +
                ")");

        conn.setAutoCommit(false);

        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO cockroachdb_binary_stream_test (val_bytea1, val_bytea2) VALUES (?, ?)"
        );

        String testString = "COCKROACHDB BYTEA VIA INPUT STREAM";
        InputStream inputStream = new ByteArrayInputStream(testString.getBytes());
        psInsert.setBinaryStream(1, inputStream);

        InputStream inputStream2 = new ByteArrayInputStream(testString.getBytes());
        psInsert.setBinaryStream(2, inputStream2, 7);
        psInsert.executeUpdate();

        conn.commit();

        PreparedStatement psSelect = conn.prepareStatement("SELECT val_bytea1, val_bytea2 FROM cockroachdb_binary_stream_test");
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();

        InputStream blobResult = resultSet.getBinaryStream(1);
        String fromBlobByIdx = new String(blobResult.readAllBytes());
        assertEquals(testString, fromBlobByIdx);

        InputStream blobResultByName = resultSet.getBinaryStream("val_bytea1");
        byte[] allBytes = blobResultByName.readAllBytes();
        String fromBlobByName = new String(allBytes);
        assertEquals(testString, fromBlobByName);

        InputStream blobResult2 = resultSet.getBinaryStream(2);
        String fromBlobByIdx2 = new String(blobResult2.readAllBytes());
        assertEquals(testString.substring(0, 7), fromBlobByIdx2);

        executeUpdate(conn, "DELETE FROM cockroachdb_binary_stream_test");

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void createAndReadingLargeBinaryStreamSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, IOException {
        logger.info("Testing temporay table with Driver: {}", driverClass);
        assumeFalse(!isTestEnabled, "Skipping CockroachDB tests");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing CockroachDB large binary stream for url -> " + url);

        try {
            executeUpdate(conn, "DROP TABLE cockroachdb_large_binary_stream_test");
        } catch (Exception e) {
            //If fails disregard
        }

        executeUpdate(conn, "CREATE TABLE cockroachdb_large_binary_stream_test(" +
                " val_bytea BYTEA" +
                ")");

        conn.setAutoCommit(false);

        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO cockroachdb_large_binary_stream_test (val_bytea) VALUES (?)"
        );

        // Create a large binary stream (1MB)
        byte[] largeData = new byte[1024 * 1024]; // 1MB
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }

        InputStream inputStream = new ByteArrayInputStream(largeData);
        psInsert.setBinaryStream(1, inputStream);
        psInsert.executeUpdate();

        conn.commit();

        PreparedStatement psSelect = conn.prepareStatement("SELECT val_bytea FROM cockroachdb_large_binary_stream_test");
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();

        InputStream blobResult = resultSet.getBinaryStream(1);
        byte[] retrievedData = blobResult.readAllBytes();
        assertEquals(largeData.length, retrievedData.length);
        assertArrayEquals(largeData, retrievedData);

        executeUpdate(conn, "DELETE FROM cockroachdb_large_binary_stream_test");

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testBinaryStreamWithNullValues(String driverClass, String url, String user, String pwd) throws SQLException, IOException {
        logger.info("Testing temporay table with Driver: {}", driverClass);
        assumeFalse(!isTestEnabled, "Skipping CockroachDB tests");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing CockroachDB binary stream with NULL values for url -> " + url);

        try {
            executeUpdate(conn, "DROP TABLE cockroachdb_binary_null_test");
        } catch (Exception e) {
            //Ignore
        }

        executeUpdate(conn, "CREATE TABLE cockroachdb_binary_null_test(" +
                " id INT PRIMARY KEY," +
                " val_bytea BYTEA" +
                ")");

        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO cockroachdb_binary_null_test (id, val_bytea) VALUES (?, ?)"
        );

        // Insert NULL value
        psInsert.setInt(1, 1);
        psInsert.setBinaryStream(2, null);
        psInsert.executeUpdate();

        // Insert non-NULL value
        psInsert.setInt(1, 2);
        String testString = "Non-NULL data";
        psInsert.setBinaryStream(2, new ByteArrayInputStream(testString.getBytes()));
        psInsert.executeUpdate();

        PreparedStatement psSelect = conn.prepareStatement("SELECT id, val_bytea FROM cockroachdb_binary_null_test ORDER BY id");
        ResultSet resultSet = psSelect.executeQuery();

        // Check NULL value
        resultSet.next();
        assertEquals(1, resultSet.getInt(1));
        InputStream nullStream = resultSet.getBinaryStream(2);
        assertNull(nullStream);

        // Check non-NULL value
        resultSet.next();
        assertEquals(2, resultSet.getInt(1));
        InputStream nonNullStream = resultSet.getBinaryStream(2);
        assertNotNull(nonNullStream);
        String retrieved = new String(nonNullStream.readAllBytes());
        assertEquals(testString, retrieved);

        executeUpdate(conn, "DROP TABLE cockroachdb_binary_null_test");

        resultSet.close();
        psSelect.close();
        conn.close();
    }
}

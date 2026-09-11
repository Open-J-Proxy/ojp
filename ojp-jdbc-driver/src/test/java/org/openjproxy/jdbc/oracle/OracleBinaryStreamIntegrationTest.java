package org.openjproxy.jdbc.oracle;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.openjproxy.grpc.helpers.SqlHelper.executeUpdate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Oracle-specific binary stream integration tests.
 * Tests Oracle-specific binary data types (RAW, BLOB) and stream handling.
 */
class OracleBinaryStreamIntegrationTest {

    private static boolean isTestDisabled;

    @BeforeAll
    static void setup() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void createAndReadingBinaryStreamSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        assumeFalse(isTestDisabled, "Skipping Oracle tests");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing Oracle binary stream for url -> " + url);

        try {
            executeUpdate(conn, "drop table oracle_binary_stream_test");
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Create table with Oracle-specific binary types
        executeUpdate(conn, "create table oracle_binary_stream_test(" +
                " val_raw1 RAW(2000)," +  // Oracle RAW for binary data
                " val_raw2 RAW(2000)" +
                ")");

        conn.setAutoCommit(false);

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into oracle_binary_stream_test (val_raw1, val_raw2) values (?, ?)"
        );

        String testString = "ORACLE RAW VIA INPUT STREAM";
        InputStream inputStream = new ByteArrayInputStream(testString.getBytes());
        psInsert.setBinaryStream(1, inputStream);

        InputStream inputStream2 = new ByteArrayInputStream(testString.getBytes());
        psInsert.setBinaryStream(2, inputStream2, 7);
        psInsert.executeUpdate();

        conn.commit();

        PreparedStatement psSelect = conn.prepareStatement("select val_raw1, val_raw2 from oracle_binary_stream_test ");
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        
        InputStream blobResult = resultSet.getBinaryStream(1);
        String fromBlobByIdx = new String(blobResult.readAllBytes());
        assertEquals(testString, fromBlobByIdx);

        InputStream blobResultByName = resultSet.getBinaryStream("val_raw1");
        byte[] allBytes = blobResultByName.readAllBytes();
        String fromBlobByName = new String(allBytes);
        assertEquals(testString, fromBlobByName);

        InputStream blobResult2 = resultSet.getBinaryStream(2);
        String fromBlobByIdx2 = new String(blobResult2.readAllBytes());
        assertEquals(testString.substring(0, 7), fromBlobByIdx2);

        executeUpdate(conn, "delete from oracle_binary_stream_test");

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void createAndReadingLargeBinaryStreamSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, IOException {
        assumeFalse(isTestDisabled, "Skipping Oracle tests");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing Oracle large binary stream for url -> " + url);

        try {
            executeUpdate(conn, "drop table oracle_large_binary_test");
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Create table with Oracle BLOB for large binary data
        executeUpdate(conn, "create table oracle_large_binary_test(" +
                " val_blob BLOB" +
                ")");

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into oracle_large_binary_test (val_blob) values (?)"
        );

        InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream("largeTextFile.txt");
        psInsert.setBinaryStream(1, inputStream);

        psInsert.executeUpdate();

        PreparedStatement psSelect = conn.prepareStatement("select val_blob from oracle_large_binary_test ");
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        InputStream inputStreamBlob = resultSet.getBinaryStream(1);

        InputStream inputStreamTestFile = this.getClass().getClassLoader().getResourceAsStream("largeTextFile.txt");

        int byteFile = inputStreamTestFile.read();
        while (byteFile != -1) {
            int blobByte = inputStreamBlob.read();
            assertEquals(byteFile, blobByte);
            byteFile = inputStreamTestFile.read();
        }

        executeUpdate(conn, "delete from oracle_large_binary_test");

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleSpecificBinaryHandling(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        assumeFalse(isTestDisabled, "Skipping Oracle tests");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing Oracle-specific binary handling for url -> " + url);

        try {
            executeUpdate(conn, "drop table oracle_binary_types_test");
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Test different Oracle binary types
        executeUpdate(conn, "create table oracle_binary_types_test(" +
                " small_raw RAW(100)," +
                " medium_raw RAW(2000)," +
                " large_blob BLOB" +
                ")");

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into oracle_binary_types_test (small_raw, medium_raw, large_blob) values (?, ?, ?)"
        );

        // Test different sizes
        String smallData = "Small RAW data";
        String mediumData = "M".repeat(1000); // 1000 characters
        String largeData = "L".repeat(10000); // 10000 characters

        psInsert.setBinaryStream(1, new ByteArrayInputStream(smallData.getBytes()));
        psInsert.setBinaryStream(2, new ByteArrayInputStream(mediumData.getBytes()));
        psInsert.setBinaryStream(3, new ByteArrayInputStream(largeData.getBytes()));

        psInsert.executeUpdate();

        PreparedStatement psSelect = conn.prepareStatement("select small_raw, medium_raw, large_blob from oracle_binary_types_test");
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();

        // Verify small RAW
        String retrievedSmall = new String(resultSet.getBinaryStream(1).readAllBytes());
        assertEquals(smallData, retrievedSmall);

        // Verify medium RAW
        String retrievedMedium = new String(resultSet.getBinaryStream(2).readAllBytes());
        assertEquals(mediumData, retrievedMedium);

        // Verify large BLOB
        String retrievedLarge = new String(resultSet.getBinaryStream(3).readAllBytes());
        assertEquals(largeData, retrievedLarge);

        executeUpdate(conn, "delete from oracle_binary_types_test");

        resultSet.close();
        psSelect.close();
        conn.close();
    }
}
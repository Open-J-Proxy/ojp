package org.openjproxy.jdbc.db2;

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
import java.sql.Statement;

import static org.openjproxy.grpc.helpers.SqlHelper.executeUpdate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * DB2-specific binary stream integration tests.
 * Tests DB2-specific binary data types (VARBINARY, BLOB) and stream handling.
 */
 class Db2BinaryStreamIntegrationTest {

    private static boolean isTestDisabled;

    @BeforeAll
     static void setup() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
     void createAndReadingBinaryStreamSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, IOException {
        assumeFalse(isTestDisabled, "Skipping DB2 tests");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        // Set schema explicitly to avoid "object not found" errors
        try (Statement schemaStmt = conn.createStatement()) {
            schemaStmt.execute("SET SCHEMA DB2INST1");
        }

        System.out.println("Testing DB2 binary stream for url -> " + url);

        try {
            executeUpdate(conn, "drop table DB2INST1.db2_binary_stream_test");
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Create table with DB2-specific binary types
        executeUpdate(conn, "create table DB2INST1.db2_binary_stream_test(" +
                " val_varbinary1 VARBINARY(2000)," +  // DB2 VARBINARY for binary data
                " val_varbinary2 VARBINARY(2000)" +
                ")");

        conn.setAutoCommit(false);

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into DB2INST1.db2_binary_stream_test (val_varbinary1, val_varbinary2) values (?, ?)"
        );

        String testString = "DB2 VARBINARY VIA INPUT STREAM";
        InputStream inputStream = new ByteArrayInputStream(testString.getBytes());
        psInsert.setBinaryStream(1, inputStream);

        InputStream inputStream2 = new ByteArrayInputStream(testString.getBytes());
        psInsert.setBinaryStream(2, inputStream2, 7);
        psInsert.executeUpdate();

        conn.commit();

        PreparedStatement psSelect = conn.prepareStatement("select val_varbinary1, val_varbinary2 from DB2INST1.db2_binary_stream_test ");
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        
        InputStream blobResult = resultSet.getBinaryStream(1);
        String fromBlobByIdx = new String(blobResult.readAllBytes());
        assertEquals(testString, fromBlobByIdx);

        InputStream blobResultByName = resultSet.getBinaryStream("val_varbinary1");
        byte[] allBytes = blobResultByName.readAllBytes();
        String fromBlobByName = new String(allBytes);
        assertEquals(testString, fromBlobByName);

        InputStream blobResult2 = resultSet.getBinaryStream(2);
        String fromBlobByIdx2 = new String(blobResult2.readAllBytes());
        assertEquals(testString.substring(0, 7), fromBlobByIdx2);

        executeUpdate(conn, "delete from db2_binary_stream_test");

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
     void createAndReadingLargeBinaryStreamSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, IOException {
        assumeFalse(isTestDisabled, "Skipping DB2 tests");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing DB2 large binary stream for url -> " + url);

        try {
            executeUpdate(conn, "drop table db2_large_binary_test");
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Create table with DB2 BLOB for large binary data
        executeUpdate(conn, "create table db2_large_binary_test(" +
                " val_blob BLOB(1M)" +
                ")");

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into db2_large_binary_test (val_blob) values (?)"
        );

        InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream("largeTextFile.txt");
        psInsert.setBinaryStream(1, inputStream);

        psInsert.executeUpdate();

        PreparedStatement psSelect = conn.prepareStatement("select val_blob from db2_large_binary_test ");
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        InputStream inputStreamBlob = resultSet.getBinaryStream(1);

        InputStream inputStreamTestFile = this.getClass().getClassLoader().getResourceAsStream("largeTextFile.txt");

        assertNotNull(inputStreamTestFile);
        int byteFile = inputStreamTestFile.read();
        while (byteFile != -1) {
            int blobByte = inputStreamBlob.read();
            assertEquals(byteFile, blobByte);
            byteFile = inputStreamTestFile.read();
        }

        executeUpdate(conn, "delete from db2_large_binary_test");

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
     void testDb2SpecificBinaryHandling(String driverClass, String url, String user, String pwd) throws SQLException, IOException {
        assumeFalse(isTestDisabled, "Skipping DB2 tests");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing DB2-specific binary handling for url -> " + url);

        try {
            executeUpdate(conn, "drop table db2_binary_types_test");
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Test different DB2 binary types
        executeUpdate(conn, "create table db2_binary_types_test(" +
                " small_varbinary VARBINARY(100)," +
                " medium_varbinary VARBINARY(2000)," +
                " large_blob BLOB(1M)" +
                ")");

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into db2_binary_types_test (small_varbinary, medium_varbinary, large_blob) values (?, ?, ?)"
        );

        // Test different sizes
        String smallData = "Small VARBINARY data";
        String mediumData = "M".repeat(1000); // 1000 characters
        String largeData = "L".repeat(10000); // 10000 characters

        psInsert.setBinaryStream(1, new ByteArrayInputStream(smallData.getBytes()));
        psInsert.setBinaryStream(2, new ByteArrayInputStream(mediumData.getBytes()));
        psInsert.setBinaryStream(3, new ByteArrayInputStream(largeData.getBytes()));

        psInsert.executeUpdate();

        PreparedStatement psSelect = conn.prepareStatement("select small_varbinary, medium_varbinary, large_blob from db2_binary_types_test");
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();

        // Verify small VARBINARY
        String retrievedSmall = new String(resultSet.getBinaryStream(1).readAllBytes());
        assertEquals(smallData, retrievedSmall);

        // Verify medium VARBINARY
        String retrievedMedium = new String(resultSet.getBinaryStream(2).readAllBytes());
        assertEquals(mediumData, retrievedMedium);

        // Verify large BLOB
        String retrievedLarge = new String(resultSet.getBinaryStream(3).readAllBytes());
        assertEquals(largeData, retrievedLarge);

        executeUpdate(conn, "delete from db2_binary_types_test");

        resultSet.close();
        psSelect.close();
        conn.close();
    }
}
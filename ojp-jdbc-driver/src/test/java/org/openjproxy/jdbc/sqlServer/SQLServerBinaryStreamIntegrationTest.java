package org.openjproxy.jdbc.sqlServer;

import org.openjproxy.jdbc.testutil.SQLServerConnectionProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

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
 * SQL Server-specific binary stream integration tests.
 * Tests SQL Server-specific binary data types (VARBINARY, IMAGE) and stream handling.
 * Uses TestContainers to provide a SQL Server instance for testing.
 */
@EnabledIf("org.openjproxy.jdbc.testutil.SQLServerTestContainer#isEnabled")
public class SQLServerBinaryStreamIntegrationTest {

    private static boolean isTestDisabled;

    @BeforeAll
    static void setup() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void createAndReadingBinaryStreamSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        assumeFalse(isTestDisabled, "Skipping SQL Server tests");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing SQL Server binary stream for url -> " + url);

        try {
            executeUpdate(conn, "IF OBJECT_ID('sqlserver_binary_stream_test', 'U') IS NOT NULL DROP TABLE sqlserver_binary_stream_test");
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Create table with SQL Server-specific binary types
        executeUpdate(conn, "create table sqlserver_binary_stream_test(" +
                " val_varbinary1 VARBINARY(2000)," +  // SQL Server VARBINARY for binary data
                " val_varbinary2 VARBINARY(2000)" +
                ")");

        conn.setAutoCommit(false);

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into sqlserver_binary_stream_test (val_varbinary1, val_varbinary2) values (?, ?)"
        );

        String testString = "SQLSERVER VARBINARY VIA INPUT STREAM";
        InputStream inputStream = new ByteArrayInputStream(testString.getBytes());
        psInsert.setBinaryStream(1, inputStream);

        InputStream inputStream2 = new ByteArrayInputStream(testString.getBytes());
        psInsert.setBinaryStream(2, inputStream2, 7);
        psInsert.executeUpdate();

        conn.commit();

        PreparedStatement psSelect = conn.prepareStatement("select val_varbinary1, val_varbinary2 from sqlserver_binary_stream_test ");
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

        executeUpdate(conn, "delete from sqlserver_binary_stream_test");

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void createAndReadingLargeBinaryStreamSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        assumeFalse(isTestDisabled, "Skipping SQL Server tests");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing SQL Server large binary stream for url -> " + url);

        try {
            executeUpdate(conn, "IF OBJECT_ID('sqlserver_large_binary_test', 'U') IS NOT NULL DROP TABLE sqlserver_large_binary_test");
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Create table with SQL Server VARBINARY(MAX) for large binary data
        executeUpdate(conn, "create table sqlserver_large_binary_test(" +
                " val_varbinary_max VARBINARY(MAX)" +
                ")");

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into sqlserver_large_binary_test (val_varbinary_max) values (?)"
        );

        InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream("largeTextFile.txt");
        psInsert.setBinaryStream(1, inputStream);

        psInsert.executeUpdate();

        PreparedStatement psSelect = conn.prepareStatement("select val_varbinary_max from sqlserver_large_binary_test ");
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

        executeUpdate(conn, "delete from sqlserver_large_binary_test");

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerSpecificBinaryHandling(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        assumeFalse(isTestDisabled, "Skipping SQL Server tests");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing SQL Server-specific binary handling for url -> " + url);

        try {
            executeUpdate(conn, "IF OBJECT_ID('sqlserver_binary_types_test', 'U') IS NOT NULL DROP TABLE sqlserver_binary_types_test");
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Test different SQL Server binary types
        executeUpdate(conn, "create table sqlserver_binary_types_test(" +
                " small_varbinary VARBINARY(100)," +
                " medium_varbinary VARBINARY(2000)," +
                " large_varbinary_max VARBINARY(MAX)" +
                ")");

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into sqlserver_binary_types_test (small_varbinary, medium_varbinary, large_varbinary_max) values (?, ?, ?)"
        );

        // Test different sizes
        String smallData = "Small VARBINARY data";
        String mediumData = "M".repeat(1000); // 1000 characters
        String largeData = "L".repeat(10000); // 10000 characters

        psInsert.setBinaryStream(1, new ByteArrayInputStream(smallData.getBytes()));
        psInsert.setBinaryStream(2, new ByteArrayInputStream(mediumData.getBytes()));
        psInsert.setBinaryStream(3, new ByteArrayInputStream(largeData.getBytes()));

        psInsert.executeUpdate();

        PreparedStatement psSelect = conn.prepareStatement("select small_varbinary, medium_varbinary, large_varbinary_max from sqlserver_binary_types_test");
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();

        // Verify small VARBINARY
        String retrievedSmall = new String(resultSet.getBinaryStream(1).readAllBytes());
        assertEquals(smallData, retrievedSmall);

        // Verify medium VARBINARY
        String retrievedMedium = new String(resultSet.getBinaryStream(2).readAllBytes());
        assertEquals(mediumData, retrievedMedium);

        // Verify large VARBINARY(MAX)
        String retrievedLarge = new String(resultSet.getBinaryStream(3).readAllBytes());
        assertEquals(largeData, retrievedLarge);

        executeUpdate(conn, "delete from sqlserver_binary_types_test");

        resultSet.close();
        psSelect.close();
        conn.close();
    }
}
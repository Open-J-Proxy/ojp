package org.openjproxy.jdbc;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.openjproxy.jdbc.testutil.TestDBUtils.ConnectionResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.openjproxy.grpc.helpers.SqlHelper.executeUpdate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BinaryStreamIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(BinaryStreamIntegrationTest.class);

    private static boolean isH2TestEnabled;
    private static boolean isPostgresTestEnabled;

    @BeforeAll
    static void setup() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
        isPostgresTestEnabled = Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_connections.csv")
    void createAndReadingBinaryStreamSuccessful(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException, IOException {
        logger.info("Testing temporay table with Driver: {}", driverClass);
        if (!isH2TestEnabled && url.toLowerCase().contains("_h2:")) {
            return;
        }
        if (!isPostgresTestEnabled && url.contains("postgresql")) {
            return;
        }

        ConnectionResult connResult = TestDBUtils.createConnection(url, user, pwd, isXA);
        Connection conn = connResult.getConnection();

        System.out.println("Testing for url -> " + url);

        try {
            executeUpdate(conn, "drop table binary_stream_test_blob");
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Create table with database-specific binary types
        String createTableSql = "create table binary_stream_test_blob(" +
                " val_blob1 BYTEA," +
                " val_blob2 BYTEA" +
                ")";

        executeUpdate(conn, createTableSql);

        conn.setAutoCommit(false);

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into binary_stream_test_blob (val_blob1, val_blob2) values (?, ?)"
        );

        String testString = "BLOB VIA INPUT STREAM";
        InputStream inputStream = new ByteArrayInputStream(testString.getBytes());
        psInsert.setBinaryStream(1, inputStream);

        InputStream inputStream2 = new ByteArrayInputStream(testString.getBytes());
        psInsert.setBinaryStream(2, inputStream2, 5);
        psInsert.executeUpdate();

        connResult.commit();

        // Start new transaction for reading
        connResult.startXATransactionIfNeeded();

        PreparedStatement psSelect = conn.prepareStatement("select val_blob1, val_blob2 from binary_stream_test_blob ");
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        InputStream blobResult = resultSet.getBinaryStream(1);
        String fromBlobByIdx = new String(blobResult.readAllBytes());

        assertEquals(testString, fromBlobByIdx);

        InputStream blobResultByName = resultSet.getBinaryStream("val_blob1");
        byte[] allBytes = blobResultByName.readAllBytes();
        String fromBlobByName = new String(allBytes);
        assertEquals(testString, fromBlobByName);

        InputStream blobResult2 = resultSet.getBinaryStream(2);
        String fromBlobByIdx2 = new String(blobResult2.readAllBytes());
        assertEquals(testString.substring(0, 5), fromBlobByIdx2);

        executeUpdate(conn, "delete from binary_stream_test_blob"
        );

        resultSet.close();
        psSelect.close();
        connResult.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_connections.csv")
    void createAndReadingLargeBinaryStreamSuccessful(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException, IOException {
        logger.info("Testing temporay table with Driver: {}", driverClass);
        if (!isH2TestEnabled && url.toLowerCase().contains("_h2:")) {
            return;
        }
        if (!isPostgresTestEnabled && url.contains("postgresql")) {
            return;
        }

        ConnectionResult connResult = TestDBUtils.createConnection(url, user, pwd, isXA);
        Connection conn = connResult.getConnection();

        System.out.println("Testing for url -> " + url);

        try {
            executeUpdate(conn, "drop table binary_stream_test_blob");
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Create table with database-specific binary types for large data
        String createTableSql = "create table binary_stream_test_blob(" +
                " val_blob  BYTEA" +
                ")";

        executeUpdate(conn, createTableSql);

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into binary_stream_test_blob (val_blob) values (?)"
        );


        InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream("largeTextFile.txt");
        psInsert.setBinaryStream(1, inputStream);

        psInsert.executeUpdate();

        PreparedStatement psSelect = conn.prepareStatement("select val_blob from binary_stream_test_blob ");
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

        executeUpdate(conn, "delete from binary_stream_test_blob");

        resultSet.close();
        psSelect.close();
        connResult.close();
    }

}

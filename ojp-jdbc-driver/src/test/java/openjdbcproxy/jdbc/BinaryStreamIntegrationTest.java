package openjdbcproxy.jdbc;

import org.junit.Assert;
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

import static openjdbcproxy.helpers.SqlHelper.executeUpdate;

public class BinaryStreamIntegrationTest {

    private static boolean isPostgresTestDisabled;

    @BeforeAll
    public static void setup() {
        isPostgresTestDisabled = Boolean.parseBoolean(System.getProperty("disablePostgresTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_connections.csv")
    public void createAndReadingBinaryStreamSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        if (isPostgresTestDisabled && url.contains("postgresql")) {
            return;
        }

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing for url -> " + url);

        // Generate unique table name to avoid conflicts in concurrent execution
        String uniqueId = String.valueOf(System.nanoTime() + Thread.currentThread().getId());
        String tableName = "binary_stream_test_blob_" + uniqueId;

        try {
            executeUpdate(conn, "drop table " + tableName);
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Create table with database-specific binary types
        String createTableSql = "create table " + tableName + "(" +
                    " val_blob1 BYTEA," +
                    " val_blob2 BYTEA" +
                    ")";

        executeUpdate(conn, createTableSql);

        conn.setAutoCommit(false);

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into " + tableName + " (val_blob1, val_blob2) values (?, ?)"
        );

        String testString = "BLOB VIA INPUT STREAM";
        InputStream inputStream = new ByteArrayInputStream(testString.getBytes());
        psInsert.setBinaryStream(1, inputStream);

        InputStream inputStream2 = new ByteArrayInputStream(testString.getBytes());
        psInsert.setBinaryStream(2, inputStream2, 5);
        psInsert.executeUpdate();

        conn.commit();

        PreparedStatement psSelect = conn.prepareStatement("select val_blob1, val_blob2 from " + tableName + " ");
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        InputStream blobResult = resultSet.getBinaryStream(1);
        String fromBlobByIdx = new String(blobResult.readAllBytes());

        Assert.assertEquals(testString, fromBlobByIdx);

        InputStream blobResultByName = resultSet.getBinaryStream("val_blob1");
        byte[] allBytes = blobResultByName.readAllBytes();
        String fromBlobByName = new String(allBytes);
        Assert.assertEquals(testString, fromBlobByName);

        InputStream blobResult2 = resultSet.getBinaryStream(2);
        String fromBlobByIdx2 = new String(blobResult2.readAllBytes());
        Assert.assertEquals(testString.substring(0, 5), fromBlobByIdx2);

        executeUpdate(conn, "delete from " + tableName
        );

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_connections.csv")
    public void createAndReadingLargeBinaryStreamSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        if (isPostgresTestDisabled && url.contains("postgresql")) {
            return;
        }

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing for url -> " + url);

        // Generate unique table name to avoid conflicts in concurrent execution
        String uniqueId = String.valueOf(System.nanoTime() + Thread.currentThread().getId());
        String tableName = "binary_stream_test_blob_" + uniqueId;

        try {
            executeUpdate(conn, "drop table " + tableName);
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Create table with database-specific binary types for large data
        String createTableSql = "create table " + tableName + "(" +
                    " val_blob  BYTEA" +
                    ")";

        executeUpdate(conn, createTableSql);

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into " + tableName + " (val_blob) values (?)"
        );


        InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream("largeTextFile.txt");
        psInsert.setBinaryStream(1, inputStream);

        psInsert.executeUpdate();

        PreparedStatement psSelect = conn.prepareStatement("select val_blob from " + tableName + " ");
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        InputStream inputStreamBlob = resultSet.getBinaryStream(1);

        InputStream inputStreamTestFile = this.getClass().getClassLoader().getResourceAsStream("largeTextFile.txt");

        int byteFile = inputStreamTestFile.read();
        while (byteFile != -1) {
            int blobByte = inputStreamBlob.read();

            Assert.assertEquals(byteFile, blobByte);

            byteFile = inputStreamTestFile.read();
        }

        executeUpdate(conn, "delete from " + tableName);

        resultSet.close();
        psSelect.close();
        conn.close();
    }

}

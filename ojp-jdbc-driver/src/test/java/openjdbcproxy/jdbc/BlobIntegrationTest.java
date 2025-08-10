package openjdbcproxy.jdbc;

import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static openjdbcproxy.helpers.SqlHelper.executeUpdate;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class BlobIntegrationTest {

    private static boolean isMySQLTestDisabled;
    private static boolean isMariaDBTestDisabled;
    private static boolean isOracleTestEnabled;
    private String tableName;
    private Connection conn;

    @BeforeAll
    public static void checkTestConfiguration() {
        isMySQLTestDisabled = Boolean.parseBoolean(System.getProperty("disableMySQLTests", "false"));
        isMariaDBTestDisabled = Boolean.parseBoolean(System.getProperty("disableMariaDBTests", "false"));
        isOracleTestEnabled = Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {

        // Generate unique table name to avoid conflicts in concurrent execution
        String uniqueId = String.valueOf(System.nanoTime() + Thread.currentThread().getId());
        this.tableName = "blob_test_blob_" + uniqueId;
        if (url.toLowerCase().contains("mysql")) {
            assumeFalse(isMySQLTestDisabled, "MySQL tests are disabled");
            this.tableName += "_mysql";
        } else if (url.toLowerCase().contains("mariadb")) {
            assumeFalse(isMariaDBTestDisabled, "MariaDB tests are disabled");
            this.tableName += "_mariadb";
        } else if (url.toLowerCase().contains("oracle")) {
            assumeFalse(!isOracleTestEnabled, "Oracle tests are disabled");
            this.tableName += "_oracle";
        } else {
            this.tableName += "_h2";
        }
        Class.forName(driverClass);
        this.conn = DriverManager.getConnection(url, user, pwd);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_mysql_mariadb_oracle_connections.csv")
    public void createAndReadingBLOBsSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        this.setUp(driverClass, url, user, pwd);
        System.out.println("Testing for url -> " + url);

        try {
            executeUpdate(conn, "drop table " + tableName);
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        executeUpdate(conn,
                "create table " + tableName + "(" +
                        " val_blob  BLOB," +
                        " val_blob2 BLOB," +
                        " val_blob3 BLOB" +
                        ")"
        );

        PreparedStatement psInsert = conn.prepareStatement(
                " insert into " + tableName + " (val_blob, val_blob2, val_blob3) values (?, ?, ?)"
        );

        // Test with binary data (not just text)
        byte[] binaryData = new byte[1000];
        for (int i = 0; i < binaryData.length; i++) {
            binaryData[i] = (byte) (i % 256);
        }

        String testString2 = "BLOB VIA INPUT STREAM";

        for (int i = 0; i < 5; i++) {
            Blob blob = conn.createBlob(); //WHEN this happens a connection in the server is set to a session and I need to replicate that in the
            //prepared statement created previously
            blob.setBytes(1, binaryData);
            psInsert.setBlob(1, blob);
            InputStream inputStream = new ByteArrayInputStream(testString2.getBytes());
            psInsert.setBlob(2, inputStream);
            InputStream inputStream2 = new ByteArrayInputStream(testString2.getBytes());
            psInsert.setBlob(3, inputStream2, 5);
            psInsert.executeUpdate();
        }

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select val_blob, val_blob2, val_blob3 from " + tableName);
        ResultSet resultSet = psSelect.executeQuery();

        int countReads = 0;
        while(resultSet.next()) {
            countReads++;
            Blob blobResult = resultSet.getBlob(1);

            Assert.assertEquals(binaryData.length, blobResult.getBinaryStream().readAllBytes().length);

            Blob blobResultByName = resultSet.getBlob("val_blob");
            Assert.assertEquals(binaryData.length, blobResultByName.getBinaryStream().readAllBytes().length);

            Blob blobResult2 = resultSet.getBlob(2);
            String fromBlobByIdx2 = new String(blobResult2.getBinaryStream().readAllBytes());
            Assert.assertEquals(testString2, fromBlobByIdx2);

            Blob blobResult3 = resultSet.getBlob(3);
            String fromBlobByIdx3 = new String(blobResult3.getBinaryStream().readAllBytes());
            Assert.assertEquals(testString2.substring(0, 5), fromBlobByIdx3);
        }
        Assert.assertEquals(5, countReads);

        executeUpdate(conn, "delete from " + tableName);

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_mysql_mariadb_oracle_connections.csv")
    public void creatingAndReadingLargeBLOBsSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, IOException, ClassNotFoundException {
        this.setUp(driverClass, url, user, pwd);
        System.out.println("Testing for url -> " + url);

        try {
            executeUpdate(conn, "drop table " + tableName);
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        executeUpdate(conn,
                "create table " + tableName + "(" +
                        " val_blob  BLOB" +
                        ")"
        );

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into " + tableName + " (val_blob) values (?)"
        );

        InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream("largeTextFile.txt");
        psInsert.setBlob(1 , inputStream);

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select val_blob from " + tableName);
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        Blob blobResult =  resultSet.getBlob(1);

        InputStream inputStreamTestFile = this.getClass().getClassLoader().getResourceAsStream("largeTextFile.txt");
        InputStream inputStreamBlob = blobResult.getBinaryStream();

        int byteFile = inputStreamTestFile.read();
        int count = 0;
        while (byteFile != -1) {
            count++;
            int blobByte = inputStreamBlob.read();
            //TODO remove after debugging
            if (byteFile != blobByte) {
                System.out.println(count);
            }

            Assert.assertEquals(byteFile, blobByte);
            byteFile = inputStreamTestFile.read();
        }

        executeUpdate(conn, "delete from " + tableName);

        resultSet.close();
        psSelect.close();
        conn.close();
    }

}

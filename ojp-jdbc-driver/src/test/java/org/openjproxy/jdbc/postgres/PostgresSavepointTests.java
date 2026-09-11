package org.openjproxy.jdbc.postgres;

import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class PostgresSavepointTests {

    private static boolean isTestEnabled;
    private Connection connection;

    @BeforeAll
    static void checkTestConfiguration() {
      isTestEnabled = Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }

    @SneakyThrows
    public void setUp(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(!isTestEnabled, "PostgreSQL tests are disabled");

        connection = DriverManager.getConnection(url, user, pwd);
        connection.setAutoCommit(false);
        connection.createStatement().execute(
                "DROP TABLE IF EXISTS savepoint_test_table"
        );
        connection.createStatement().execute(
            "CREATE TABLE savepoint_test_table (id INT PRIMARY KEY, name VARCHAR(255))"
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testSavepoint(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (1, 'Alice')");
        Savepoint savepoint = connection.setSavepoint();

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (2, 'Bob')");
        connection.rollback(savepoint);

        ResultSet resultSet = connection.createStatement().executeQuery("SELECT * FROM savepoint_test_table order by id desc");
        assertTrue(resultSet.next());
        assertEquals(1, resultSet.getInt("id"));
    }
}
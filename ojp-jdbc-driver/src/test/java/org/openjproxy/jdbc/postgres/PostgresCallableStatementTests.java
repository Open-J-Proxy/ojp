package org.openjproxy.jdbc.postgres;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Calendar;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class PostgresCallableStatementTests {

    private static boolean isTestEnabled;

    private Connection connection;
    private CallableStatement callableStatement;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(!isTestEnabled, "Postgres tests are disabled");
        
        // Connect to the PostgreSQL database
        connection = DriverManager.getConnection(url, user, password);

        // Ensure the employee table and stored procedures exist
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS employee");
            stmt.execute("CREATE TABLE employee (id SERIAL PRIMARY KEY, name VARCHAR(255), salary NUMERIC(10, 2), hire_date DATE, hire_time TIME, hire_ts TIMESTAMP)");
            stmt.execute("INSERT INTO employee (name, salary, hire_date, hire_time, hire_ts) VALUES ('Alice', 50000, '2021-01-01', '09:00:00', '2021-01-01 09:00:00')");

            stmt.execute(
                    "CREATE OR REPLACE PROCEDURE update_salary(" +
                            "    emp_id INT," +
                            "    new_salary NUMERIC(10,2)," +
                            "    OUT updated_salary NUMERIC(10,2)" +
                            ") LANGUAGE plpgsql AS $$ " +
                            "BEGIN " +
                            "    UPDATE employee SET salary = new_salary WHERE id = emp_id;" +
                            "    SELECT salary INTO updated_salary FROM employee WHERE id = emp_id;" +
                            "END; $$;"
            );

            stmt.execute(
                    "CREATE OR REPLACE PROCEDURE update_employee_dates(" +
                            "    emp_id INT," +
                            "    new_date DATE," +
                            "    new_time TIME," +
                            "    new_ts TIMESTAMP," +
                            "    OUT out_date DATE," +
                            "    OUT out_time TIME," +
                            "    OUT out_ts TIMESTAMP" +
                            ") LANGUAGE plpgsql AS $$ " +
                            "BEGIN " +
                            "    UPDATE employee SET hire_date=new_date, hire_time=new_time, hire_ts=new_ts WHERE id=emp_id;" +
                            "    SELECT hire_date, hire_time, hire_ts INTO out_date, out_time, out_ts FROM employee WHERE id=emp_id;" +
                            "END; $$;"
            );
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (callableStatement != null) callableStatement.close();
        if (connection != null) connection.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testExecuteProcedure(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        callableStatement = connection.prepareCall("CALL update_salary(?, ?, ?)");
        callableStatement.setInt(1, 1);
        callableStatement.setBigDecimal(2, new BigDecimal("60000"));
        callableStatement.registerOutParameter(3, Types.NUMERIC);

        callableStatement.execute();

        // getBigDecimal, getObject, wasNull
        BigDecimal updatedSalary = callableStatement.getBigDecimal(3);
        assertNotNull(updatedSalary, "The updated salary should not be null.");
        assertEquals(new BigDecimal("60000.00"), updatedSalary);
        Object updatedSalaryObj = callableStatement.getObject(3);
        assertEquals(updatedSalary, updatedSalaryObj);
        assertFalse(callableStatement.wasNull());

        // getInt (should throw or convert for numeric)
        assertThrows(SQLException.class, () -> callableStatement.getInt(3));

        // Verify the salary update in the database
        ResultSet resultSet = connection
                .createStatement()
                .executeQuery("SELECT salary FROM employee WHERE id = 1");
        assertTrue(resultSet.next());
        assertEquals(new BigDecimal("60000.00"), resultSet.getBigDecimal("salary"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testDateTimeParameters(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        callableStatement = connection.prepareCall("CALL update_employee_dates(?, ?, ?, ?, ?, ?, ?)");
        int empId = 1;
        Date newDate = Date.valueOf(LocalDate.of(2024, 6, 27));
        Time newTime = Time.valueOf(LocalTime.of(14, 30));
        Timestamp newTimestamp = Timestamp.valueOf(LocalDateTime.of(2024, 6, 27, 14, 30, 0));
        callableStatement.setInt(1, empId);
        callableStatement.setDate(2, newDate);
        callableStatement.setTime(3, newTime);
        callableStatement.setTimestamp(4, newTimestamp);
        callableStatement.registerOutParameter(5, Types.DATE);
        callableStatement.registerOutParameter(6, Types.TIME);
        callableStatement.registerOutParameter(7, Types.TIMESTAMP);

        callableStatement.execute();

        // getDate, getTime, getTimestamp, getObject, wasNull
        Date resultDate = callableStatement.getDate(5);
        Time resultTime = callableStatement.getTime(6);
        Timestamp resultTimestamp = callableStatement.getTimestamp(7);

        assertEquals(newDate, resultDate);
        assertEquals(newTime, resultTime);
        assertEquals(newTimestamp, resultTimestamp);

        assertEquals(resultDate, callableStatement.getObject(5));
        assertEquals(resultTime, callableStatement.getObject(6));
        assertEquals(resultTimestamp, callableStatement.getObject(7));
        assertFalse(callableStatement.wasNull());

        // get with Calendar
        Calendar cal = Calendar.getInstance();
        assertEquals(resultDate, callableStatement.getDate(5, cal));
        assertEquals(resultTime, callableStatement.getTime(6, cal));
        assertEquals(resultTimestamp, callableStatement.getTimestamp(7, cal));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testSetAndGetStringAndBoolean(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        // Add an employee with a boolean flag using the name column as a boolean string
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("INSERT INTO employee (name, salary) VALUES ('true', 1000)");
        }

        // We'll treat the 'name' as a boolean string for test purposes
        CallableStatement stmt = connection.prepareCall("SELECT name FROM employee WHERE id = ?");
        stmt.setInt(1, 2);
        ResultSet rs = stmt.executeQuery();
        assertTrue(rs.next());
        String name = rs.getString(1);
        assertEquals("true", name);
        // setBoolean, getBoolean
        CallableStatement stmt2 = connection.prepareCall("SELECT ?::boolean");
        stmt2.setBoolean(1, true);
        ResultSet rs2 = stmt2.executeQuery();
        assertTrue(rs2.next());
        assertTrue(rs2.getBoolean(1));
        assertThrows(SQLException.class, () -> stmt2.wasNull());
        rs2.close();
        stmt2.close();
        rs.close();
        stmt.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testSetObjectAndGetObject(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        callableStatement = connection.prepareCall("CALL update_salary(?, ?, ?)");
        callableStatement.setObject(1, 1, Types.INTEGER);
        callableStatement.setObject(2, new BigDecimal("70000.00"), Types.NUMERIC);
        callableStatement.registerOutParameter(3, Types.NUMERIC);

        callableStatement.execute();

        Object result = callableStatement.getObject(3);
        assertInstanceOf(BigDecimal.class, result);
        assertEquals(new BigDecimal("70000.00"), result);

        // getBigDecimal
        assertEquals(new BigDecimal("70000.00"), callableStatement.getBigDecimal(3));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testInvalidParameterIndex(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // This test will intentionally fail due to an invalid parameter index
        assertThrows(SQLException.class, () -> {
            callableStatement = connection.prepareCall("{ CALL update_salary(?, ?, ?) }");
            callableStatement.setInt(4, 1); // Invalid parameter index (should be 1, 2, or 3)
            callableStatement.execute();
        });
    }
}
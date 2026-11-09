package org.openjproxy.grpc.server.action.transaction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.dto.ParameterType;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParameterSetExecutionTest {

    private Connection connection;

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection("jdbc:h2:mem:parameter_sets;DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ITEMS (ID INT PRIMARY KEY, NAME VARCHAR(20))");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE ITEMS");
        }
        connection.close();
    }

    @Test
    void splitsFlattenedParametersIntoSets() throws SQLException {
        List<Parameter> params = flattened(new Object[][]{{1, "a"}, {2, "b"}, {3, "c"}});

        List<List<Parameter>> sets = ParameterSetExecution.split(params, 2);

        assertEquals(3, sets.size());
        assertEquals(1, sets.get(2).get(0).getIndex());
        assertEquals(3, sets.get(2).get(0).getValues().get(0));
        assertEquals(2, sets.get(2).get(1).getIndex());
        assertEquals("c", sets.get(2).get(1).getValues().get(0));
    }

    @Test
    void rejectsInvalidSetSizeAndIndex() {
        List<Parameter> params = flattened(new Object[][]{{1, "a"}});
        List<Parameter> zeroIndex = List.of(Parameter.builder().index(0).type(ParameterType.INT)
                .values(List.of(1)).build());

        assertEquals("HY024", assertThrows(SQLException.class,
                () -> ParameterSetExecution.split(params, 0)).getSQLState());
        assertEquals("07009", assertThrows(SQLException.class,
                () -> ParameterSetExecution.split(zeroIndex, 1)).getSQLState());
    }

    @Test
    void executesAllSetsAsOneBatch() throws SQLException {
        List<Parameter> params = flattened(new Object[][]{{1, "a"}, {2, "b"}, {3, "c"}});

        int[] counts;
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO ITEMS (ID, NAME) VALUES (?, ?)")) {
            counts = ParameterSetExecution.execute(null, null, ps, params, 2);
        }

        assertArrayEquals(new int[]{1, 1, 1}, counts);
        assertEquals(List.of("1:a", "2:b", "3:c"), rows());
    }

    @Test
    void reportsBatchUpdateExceptionWhenASetFails() throws SQLException {
        List<Parameter> params = flattened(new Object[][]{{1, "a"}, {1, "duplicate"}, {3, "c"}});

        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO ITEMS (ID, NAME) VALUES (?, ?)")) {
            BatchUpdateException error = assertThrows(BatchUpdateException.class,
                    () -> ParameterSetExecution.execute(null, null, ps, params, 2));
            assertEquals(Statement.EXECUTE_FAILED, error.getUpdateCounts()[1]);
        }
    }

    @Test
    void commitsAutoCommitBatchOnceAndRestoresAutoCommit() throws SQLException {
        List<Parameter> params = flattened(new Object[][]{{1, "a"}, {2, "b"}});

        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO ITEMS (ID, NAME) VALUES (?, ?)")) {
            ParameterSetExecution.execute(null, null, ps, params, 2);
        }

        assertTrue(connection.getAutoCommit());
        assertEquals(List.of("1:a", "2:b"), rowsFromAnotherConnection());
    }

    @Test
    void autoCommitBatchKeepsPerSetOutcomeWhenASetFails() throws SQLException {
        List<Parameter> params = flattened(new Object[][]{{1, "a"}, {1, "duplicate"}, {3, "c"}});

        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO ITEMS (ID, NAME) VALUES (?, ?)")) {
            BatchUpdateException error = assertThrows(BatchUpdateException.class,
                    () -> ParameterSetExecution.execute(null, null, ps, params, 2));
            assertArrayEquals(new int[]{1, Statement.EXECUTE_FAILED, 1}, error.getUpdateCounts());
        }

        assertTrue(connection.getAutoCommit());
        assertEquals(List.of("1:a", "3:c"), rowsFromAnotherConnection());
    }

    @Test
    void leavesCallerTransactionOpen() throws SQLException {
        List<Parameter> params = flattened(new Object[][]{{1, "a"}, {2, "b"}});
        connection.setAutoCommit(false);

        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO ITEMS (ID, NAME) VALUES (?, ?)")) {
            ParameterSetExecution.execute(null, null, ps, params, 2);
        }
        connection.rollback();
        connection.setAutoCommit(true);

        assertEquals(List.of(), rows());
    }

    @Test
    void olderServerBindingFailsBeforeExecuting() throws SQLException {
        List<Parameter> params = flattened(new Object[][]{{1, "a"}, {2, "b"}});

        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO ITEMS (ID, NAME) VALUES (?, ?)")) {
            assertThrows(SQLException.class, () -> {
                for (Parameter parameter : params) {
                    ps.setObject(parameter.getIndex(), parameter.getValues().get(0));
                }
            });
        }

        assertEquals(List.of(), rows());
    }

    private static List<Parameter> flattened(Object[][] sets) {
        List<Parameter> params = new ArrayList<>();
        for (int set = 0; set < sets.length; set++) {
            params.add(Parameter.builder().index(set * 2 + 1).type(ParameterType.INT)
                    .values(List.of(sets[set][0])).build());
            params.add(Parameter.builder().index(set * 2 + 2).type(ParameterType.STRING)
                    .values(List.of(sets[set][1])).build());
        }
        return params;
    }

    private List<String> rows() throws SQLException {
        return rows(connection);
    }

    private static List<String> rowsFromAnotherConnection() throws SQLException {
        try (Connection other = DriverManager.getConnection("jdbc:h2:mem:parameter_sets;DB_CLOSE_DELAY=-1")) {
            return rows(other);
        }
    }

    private static List<String> rows(Connection source) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = source.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT ID, NAME FROM ITEMS ORDER BY ID")) {
            while (resultSet.next()) {
                rows.add(resultSet.getInt(1) + ":" + resultSet.getString(2));
            }
        }
        return rows;
    }
}

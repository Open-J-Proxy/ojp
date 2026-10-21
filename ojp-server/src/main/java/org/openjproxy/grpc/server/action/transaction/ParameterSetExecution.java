package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.SessionInfo;
import io.grpc.Context;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.statement.ParameterHandler;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes several parameter sets of a prepared statement in a single request, as a JDBC batch.
 *
 * <p>Clients that bind arrays of parameters (ODBC {@code SQL_ATTR_PARAMSET_SIZE}) send all sets in
 * one executeUpdate request with flattened indexes: set {@code s} (0-based) uses indexes
 * {@code s * setSize + 1 .. (s + 1) * setSize}. This avoids one round-trip per set. A server
 * without this feature fails on the first index beyond the statement's markers, before executing
 * anything, so an older server never applies a partial or wrong update.
 */
@Slf4j
final class ParameterSetExecution {

    private ParameterSetExecution() {
    }

    /**
     * Binds every parameter set, adds it to the batch and executes the batch.
     *
     * <p>In auto-commit mode the batch runs in one transaction with a single commit, as ODBC lets the
     * driver commit a parameter array as a whole. Committing each set separately costs one
     * synchronous log flush per row, which dominates the execution time on replicated databases.
     * If any set fails, the transaction is rolled back and the batch runs again in auto-commit mode,
     * so the outcome of each set is the same as without the single commit, also on databases that
     * abort the whole transaction on the first error.
     *
     * @param sessionManager the session manager (LOB parameters)
     * @param session        the current session
     * @param ps             the prepared statement, without bound parameters
     * @param params         the flattened parameters of all sets
     * @param setSize        the number of parameters per set
     * @return the update count of each set, as returned by {@link PreparedStatement#executeBatch()}
     * @throws SQLException if a parameter is invalid or the batch fails
     */
    static int[] execute(SessionManager sessionManager, SessionInfo session, PreparedStatement ps,
                         List<Parameter> params, int setSize) throws SQLException {
        List<List<Parameter>> sets = split(params, setSize);
        Connection connection = ps.getConnection();
        if (!connection.getAutoCommit()) {
            return executeBatch(sessionManager, session, ps, sets);
        }
        connection.setAutoCommit(false);
        try {
            int[] counts = executeBatch(sessionManager, session, ps, sets);
            connection.commit();
            return counts;
        } catch (BatchUpdateException e) {
            connection.rollback();
            if (Context.current().isCancelled()) {
                throw e;
            }
            log.debug("Parameter-set batch failed in a single transaction, running it again with "
                    + "auto-commit: {}", e.getMessage());
            ps.clearBatch();
            connection.setAutoCommit(true);
            return executeBatch(sessionManager, session, ps, sets);
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            if (!connection.getAutoCommit()) {
                connection.setAutoCommit(true);
            }
        }
    }

    private static int[] executeBatch(SessionManager sessionManager, SessionInfo session, PreparedStatement ps,
                                      List<List<Parameter>> sets) throws SQLException {
        for (List<Parameter> set : sets) {
            ParameterHandler.addParametersPreparedStatement(sessionManager, session, ps, set);
            ps.addBatch();
        }
        try (StatementCancellation ignored = StatementCancellation.onCallCancelled(ps)) {
            return ps.executeBatch();
        }
    }

    /**
     * Splits the flattened parameters into sets with indexes starting at 1.
     *
     * @param params  the flattened parameters
     * @param setSize the number of parameters per set
     * @return the parameter sets, in execution order
     * @throws SQLException if the set size or a parameter index is invalid
     */
    static List<List<Parameter>> split(List<Parameter> params, int setSize) throws SQLException {
        if (setSize <= 0) {
            throw new SQLException("Invalid parameter-set size " + setSize, "HY024");
        }
        int highestIndex = 0;
        for (Parameter parameter : params) {
            if (parameter.getIndex() == null || parameter.getIndex() < 1) {
                throw new SQLException("Invalid parameter index " + parameter.getIndex(), "07009");
            }
            highestIndex = Math.max(highestIndex, parameter.getIndex());
        }
        int setCount = (highestIndex + setSize - 1) / setSize;
        List<List<Parameter>> sets = new ArrayList<>(setCount);
        for (int i = 0; i < setCount; i++) {
            sets.add(new ArrayList<>(setSize));
        }
        for (Parameter parameter : params) {
            int zeroBased = parameter.getIndex() - 1;
            sets.get(zeroBased / setSize).add(Parameter.builder()
                    .index(zeroBased % setSize + 1)
                    .type(parameter.getType())
                    .values(parameter.getValues())
                    .build());
        }
        return sets;
    }
}

package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.SessionInfo;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.statement.ParameterHandler;

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
final class ParameterSetExecution {

    private ParameterSetExecution() {
    }

    /**
     * Binds every parameter set, adds it to the batch and executes the batch.
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
        for (List<Parameter> set : split(params, setSize)) {
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

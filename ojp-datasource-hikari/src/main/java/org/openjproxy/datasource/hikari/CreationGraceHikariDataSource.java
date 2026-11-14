package org.openjproxy.datasource.hikari;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

/**
 * Allows one extra borrow wait when asynchronous connection creation has not filled the pool.
 */
final class CreationGraceHikariDataSource extends HikariDataSource {
    private static final Logger log = LoggerFactory.getLogger(CreationGraceHikariDataSource.class);
    private final long creationGracePeriodMs;

    CreationGraceHikariDataSource(HikariConfig config, long creationGracePeriodMs) {
        super(config);
        this.creationGracePeriodMs = creationGracePeriodMs;
    }

    @Override
    public Connection getConnection() throws SQLException {
        try {
            return super.getConnection();
        } catch (SQLTransientConnectionException timeout) {
            if (creationGracePeriodMs == 0 || isClosed() || Thread.currentThread().isInterrupted()) {
                throw timeout;
            }

            // HikariPool's per-borrow timeout avoids changing the shared datasource timeout.
            HikariPool pool = (HikariPool) getHikariPoolMXBean();
            int total = pool.getTotalConnections();
            int maximum = getMaximumPoolSize();
            if (total >= maximum) {
                throw timeout;
            }

            log.debug("Pool '{}' timed out below capacity ({}/{}); allowing {}ms connection creation grace",
                    getPoolName(), total, maximum, creationGracePeriodMs);
            try {
                return pool.getConnection(creationGracePeriodMs);
            } catch (SQLException graceFailure) {
                graceFailure.addSuppressed(timeout);
                throw graceFailure;
            }
        }
    }
}

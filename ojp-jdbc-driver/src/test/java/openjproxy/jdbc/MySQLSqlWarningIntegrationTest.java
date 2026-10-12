package org.openjproxy.jdbc;

import org.junit.jupiter.params.provider.Arguments;
import org.openjproxy.jdbc.testutil.MySQLCompatibleDatabase;

import java.util.stream.Stream;

class MySQLSqlWarningIntegrationTest extends MySQLCompatibleSqlWarningIntegrationTest {

    static Stream<Arguments> connectionDetails() {
        return MySQLCompatibleDatabase.MYSQL.connectionArguments();
    }

    @Override
    protected MySQLCompatibleDatabase database() {
        return MySQLCompatibleDatabase.MYSQL;
    }
}

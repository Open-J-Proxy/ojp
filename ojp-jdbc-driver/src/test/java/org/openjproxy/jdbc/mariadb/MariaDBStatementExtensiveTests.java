package org.openjproxy.jdbc.mariadb;

import org.openjproxy.jdbc.mySql.MySQLCompatibleStatementExtensiveTests;
import org.junit.jupiter.params.provider.Arguments;
import org.openjproxy.jdbc.testutil.MySQLCompatibleDatabase;

import java.util.stream.Stream;

class MariaDBStatementExtensiveTests extends MySQLCompatibleStatementExtensiveTests {

    static Stream<Arguments> connectionDetails() {
        return MySQLCompatibleDatabase.MARIADB.connectionArguments();
    }

    @Override
    protected MySQLCompatibleDatabase database() {
        return MySQLCompatibleDatabase.MARIADB;
    }
}

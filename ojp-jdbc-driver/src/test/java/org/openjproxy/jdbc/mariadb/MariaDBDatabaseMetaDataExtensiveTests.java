package org.openjproxy.jdbc.mariadb;

import org.openjproxy.jdbc.mySql.MySQLCompatibleDatabaseMetaDataExtensiveTests;
import org.junit.jupiter.params.provider.Arguments;
import org.openjproxy.jdbc.testutil.MySQLCompatibleDatabase;

import java.util.stream.Stream;

class MariaDBDatabaseMetaDataExtensiveTests extends MySQLCompatibleDatabaseMetaDataExtensiveTests {

    static Stream<Arguments> connectionDetails() {
        return MySQLCompatibleDatabase.MARIADB.connectionArguments();
    }

    @Override
    protected MySQLCompatibleDatabase database() {
        return MySQLCompatibleDatabase.MARIADB;
    }
}

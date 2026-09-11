package org.openjproxy.grpc.helpers;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class SqlHelper {
    public static int executeUpdate(Connection conn, String s) throws SQLException {
        try (Statement stmt =  conn.createStatement()) {
            return stmt.executeUpdate(s);
        }
    }
}

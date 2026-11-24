package org.openjproxy.grpc.server.action.streaming;

import org.junit.jupiter.api.Test;

import javax.sql.rowset.serial.SerialClob;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MariaDbClobPositionTest {
    @Test
    void convertsUtf16OffsetsAndRejectsInvalidBoundaries() throws Exception {
        SerialClob clob = new SerialClob("A🚀B".toCharArray());
        assertEquals(1, CreateLobAction.mariaDbClobPosition(clob, 1));
        assertEquals(2, CreateLobAction.mariaDbClobPosition(clob, 2));
        assertEquals(3, CreateLobAction.mariaDbClobPosition(clob, 4));
        assertEquals(4, CreateLobAction.mariaDbClobPosition(clob, 5));
        assertThrows(SQLException.class, () -> CreateLobAction.mariaDbClobPosition(clob, 0));
        assertThrows(SQLException.class, () -> CreateLobAction.mariaDbClobPosition(clob, 3));
        assertThrows(SQLException.class, () -> CreateLobAction.mariaDbClobPosition(clob, 6));
    }
}

package org.openjproxy.grpc.server.lob;

import com.openjproxy.grpc.LobType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BufferedLobTest {
    @Test
    void shouldReturnByteLengthWhenAppendingAndOverwritingBinary() throws SQLException {
        BufferedLob lob = new BufferedLob(LobType.LT_BLOB);
        assertEquals(0L, lob.length());
        lob.write(1, new byte[]{1, 2, 3, 0, 0});
        assertEquals(5L, lob.length());
        lob.write(6, new byte[]{4, 0, 0});
        assertEquals(8L, lob.length());
        lob.write(1, new byte[]{9, 8});
        assertEquals(8L, lob.length());
        lob.write(9, new byte[0]);
        assertEquals(8L, lob.length());
    }

    @Test
    void shouldReturnUtf16LengthWhenAppendingAndOverwritingUnicode() throws SQLException {
        BufferedLob lob = new BufferedLob(LobType.LT_CLOB);
        assertEquals(0L, lob.length());
        lob.write(1, "é東京🙂".getBytes(StandardCharsets.UTF_8));
        assertEquals(5L, lob.length());
        lob.write(6, "z".getBytes(StandardCharsets.UTF_8));
        assertEquals(6L, lob.length());
        lob.write(6, "🙂".getBytes(StandardCharsets.UTF_8));
        assertEquals(7L, lob.length());
        lob.write(8, new byte[0]);
        assertEquals(7L, lob.length());
    }

    @Test
    void shouldPreserveAllBinaryValuesAndTrailingZerosWhenWritingChunks() throws SQLException {
        BufferedLob lob = new BufferedLob(LobType.LT_BLOB);
        byte[] first = new byte[65536];
        for (int index = 0; index < first.length; index++) {
            first[index] = (byte) index;
        }
        lob.write(1, first);
        lob.write(first.length + 1L, new byte[]{1, 0, 0});
        assertArrayEquals(first, lob.read(1, first.length));
        assertArrayEquals(new byte[]{1, 0, 0}, lob.read(first.length + 1L, Integer.MAX_VALUE));
        assertArrayEquals(lob.binaryContent(), lob.read(1, Integer.MAX_VALUE));
        assertArrayEquals(lob.binaryContent(), lob.read(1, Integer.MAX_VALUE));
    }

    @Test
    void shouldUseUtf16PositionsWhenAppendingAndSlicingUnicode() throws SQLException {
        BufferedLob lob = new BufferedLob(LobType.LT_CLOB);
        lob.write(1, "é東京🙂".getBytes(StandardCharsets.UTF_8));
        lob.write(6, "z".getBytes(StandardCharsets.UTF_8));
        assertEquals("é東京🙂z", lob.textContent());
        assertEquals("東京🙂", new String(lob.read(2, 4), StandardCharsets.UTF_8));
        assertEquals("é", new String(lob.read(1, 1), StandardCharsets.UTF_8));
        lob.write(6, "終".getBytes(StandardCharsets.UTF_8));
        assertEquals("é東京🙂終", lob.textContent());
    }

    @Test
    void shouldDistinguishEmptyValuesAndRejectInvalidRanges() throws SQLException {
        for (LobType type : new LobType[]{LobType.LT_BLOB, LobType.LT_CLOB}) {
            BufferedLob lob = new BufferedLob(type);
            lob.write(1, new byte[0]);
            assertEquals(0L, lob.length());
            assertArrayEquals(new byte[0], lob.read(1, Integer.MAX_VALUE));
            assertEquals("22003", assertThrows(SQLException.class, () -> lob.read(0, 1)).getSQLState());
            assertEquals("22003", assertThrows(SQLException.class, () -> lob.read(2, 1)).getSQLState());
            assertEquals("22003", assertThrows(SQLException.class, () -> lob.read(1, -1)).getSQLState());
            assertEquals("22003", assertThrows(SQLException.class,
                    () -> lob.write(2, new byte[]{1})).getSQLState());
        }
    }
}

package org.openjproxy.grpc.server.lob;

import com.openjproxy.grpc.LobType;
import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * Session-owned LOB storage for JDBC drivers without native Blob/Clob creation.
 * CLOB positions use Java/JDBC UTF-16 character units, not UTF-8 byte offsets.
 */
public class BufferedLob {
    @Getter
    private final LobType lobType;
    private byte[] bytes = new byte[0];
    private int byteLength;
    private final StringBuilder characters = new StringBuilder();

    public BufferedLob(LobType lobType) {
        if (lobType != LobType.LT_BLOB && lobType != LobType.LT_CLOB) {
            throw new IllegalArgumentException("Buffered LOB must be BLOB or CLOB");
        }
        this.lobType = lobType;
    }

    public synchronized int write(long position, byte[] data) throws SQLException {
        int start = checkedPosition(position);
        if (lobType == LobType.LT_CLOB) {
            String text = new String(data, StandardCharsets.UTF_8);
            if ((long) start + text.length() > Integer.MAX_VALUE) {
                throw new SQLException("CLOB exceeds the supported size", "22001");
            }
            characters.replace(start, Math.min(characters.length(), start + text.length()), text);
        } else {
            long end = (long) start + data.length;
            if (end > Integer.MAX_VALUE) {
                throw new SQLException("BLOB exceeds the supported size", "22001");
            }
            if (end > bytes.length) {
                int capacity = (int) Math.min(Integer.MAX_VALUE,
                        Math.max(end, Math.max(64L, (long) bytes.length * 2)));
                bytes = Arrays.copyOf(bytes, capacity);
            }
            System.arraycopy(data, 0, bytes, start, data.length);
            byteLength = Math.max(byteLength, (int) end);
        }
        return data.length;
    }

    public synchronized byte[] read(long position, int length) throws SQLException {
        if (length < 0) {
            throw new SQLException("LOB read length must not be negative", "22003");
        }
        int start = checkedPosition(position);
        int end = (int) Math.min(size(), (long) start + length);
        return lobType == LobType.LT_CLOB
                ? characters.substring(start, end).getBytes(StandardCharsets.UTF_8)
                : Arrays.copyOfRange(bytes, start, end);
    }

    public synchronized byte[] binaryContent() {
        return Arrays.copyOf(bytes, byteLength);
    }

    public synchronized String textContent() {
        return characters.toString();
    }

    private int size() {
        return lobType == LobType.LT_CLOB ? characters.length() : byteLength;
    }

    private int checkedPosition(long position) throws SQLException {
        if (position < 1 || position > (long) size() + 1) {
            throw new SQLException("LOB position is outside the value", "22003");
        }
        return (int) (position - 1);
    }
}

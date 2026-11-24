package org.openjproxy.grpc.server.statement;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;

/**
 * Keeps UTF-16 surrogate pairs together when JDBC drivers encode reader chunks separately.
 */
final class SurrogateSafeReader extends Reader {
    private final PushbackReader reader;

    SurrogateSafeReader(Reader reader) {
        this.reader = new PushbackReader(reader, 1);
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        int count = reader.read(buffer, offset, length);
        if (count <= 0 || length < 2 || !Character.isHighSurrogate(buffer[offset + count - 1])) {
            return count;
        }
        if (count > 1) {
            reader.unread(buffer[offset + count - 1]);
            return count - 1;
        }
        int next = reader.read();
        if (next != -1) {
            buffer[offset + 1] = (char) next;
            return 2;
        }
        return count;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}

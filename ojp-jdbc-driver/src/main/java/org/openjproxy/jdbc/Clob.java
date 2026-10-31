package org.openjproxy.jdbc;

import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.LobType;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.client.StatementService;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.io.Writer;
import java.sql.SQLException;

@Slf4j
public class Clob extends Lob implements java.sql.Clob {

    public Clob(Connection connection, LobServiceImpl lobService, StatementService statementService,
            LobReference lobReference) {
        super(connection, lobService, statementService, lobReference);
    }

    @Override
    public String getSubString(long pos, int length) throws SQLException {
        log.debug("getSubString: {}, {}", pos, length);
        BufferedInputStream bis = new BufferedInputStream(this.getBinaryStream(pos, length + 1L));
        try {
            return new String(bis.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Reader getCharacterStream() throws SQLException {
        log.debug("getCharacterStream called");
        return new InputStreamReader(this.getBinaryStream(1, this.length()), StandardCharsets.UTF_8);
    }

    @Override
    public InputStream getAsciiStream() throws SQLException {
        log.debug("getAsciiStream called");
        return null;
    }

    @Override
    public long position(String searchstr, long start) throws SQLException {
        log.debug("position: {}, {}", searchstr, start);
        return 0;
    }

    @Override
    public long position(java.sql.Clob searchstr, long start) throws SQLException {
        log.debug("position: <Clob>, {}", start);
        return 0;
    }

    @Override
    public int setString(long pos, String str) throws SQLException {
        log.debug("setString: {}, {}", pos, str);
        return this.setString(pos, str, 0, str.length());
    }

    @SneakyThrows
    @Override
    public int setString(long pos, String str, int offset, int len) throws SQLException {
        log.debug("setString: {}, {}, {}, {}", pos, str, offset, len);
        int writtenCount = 0;
        try (Writer writer = this.setCharacterStream(pos)) {
            for (int i = offset; i < len; i++) {
                writer.write(str.charAt(i));
                writtenCount++;
            }
        }
        return writtenCount;
    }

    @Override
    public OutputStream setAsciiStream(long pos) throws SQLException {
        log.debug("setAsciiStream: {}", pos);
        return this.setBinaryStream(LobType.LT_CLOB, pos);
    }

    @Override
    public Writer setCharacterStream(long pos) throws SQLException {
        log.debug("setCharacterStream: {}", pos);
        OutputStream os = this.setBinaryStream(LobType.LT_CLOB, pos);
        return new OutputStreamWriter(os);
    }

    @Override
    public void truncate(long len) throws SQLException {
        log.debug("truncate: {}", len);
    }

    @Override
    public void free() throws SQLException {
        log.debug("free called");
    }

    @Override
    public Reader getCharacterStream(long pos, long length) throws SQLException {
        log.debug("getCharacterStream: {}, {}", pos, length);
        return new InputStreamReader(super.getBinaryStream(pos, length), StandardCharsets.UTF_8);
    }
}

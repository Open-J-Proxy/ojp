package org.openjproxy.grpc.server.statement;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SurrogateSafeReaderTest {
    @Test
    void preservesSurrogatePairsAcrossChunkBoundaries() throws Exception {
        String text = "abc🚀xyz🚀";
        StringBuilder result = new StringBuilder();
        try (SurrogateSafeReader reader = new SurrogateSafeReader(new StringReader(text))) {
            char[] chunk = new char[4];
            int count;
            while ((count = reader.read(chunk, 0, chunk.length)) != -1) {
                result.append(new String(new String(chunk, 0, count).getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));
            }
        }
        assertEquals(text, result.toString());
    }
}

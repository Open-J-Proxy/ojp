package org.openjproxy.grpc.server.utils;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DateTimeUtilsTest {

    /**
     * Mirrors com.microsoft.sqlserver.jdbc.DateTimeOffset, whose getTimestamp() returns the UTC instant.
     */
    public static class FakeDateTimeOffset {
        private final Timestamp utcTimestamp;
        private final int minutesOffset;

        FakeDateTimeOffset(Instant utcInstant, int minutesOffset) {
            this.utcTimestamp = Timestamp.from(utcInstant);
            this.minutesOffset = minutesOffset;
        }

        public Timestamp getTimestamp() {
            return utcTimestamp;
        }

        public int getMinutesOffset() {
            return minutesOffset;
        }
    }

    @Test
    void shouldPreserveInstantAndOffsetWhenConvertingDateTimeOffset() {
        Instant utcInstant = Instant.parse("2024-12-01T08:10:10Z");

        OffsetDateTime converted = DateTimeUtils.extractOffsetDateTime(new FakeDateTimeOffset(utcInstant, 120));

        assertEquals(OffsetDateTime.of(2024, 12, 1, 10, 10, 10, 0, ZoneOffset.ofHours(2)), converted);
        assertEquals(utcInstant, converted.toInstant());
    }

    @Test
    void shouldReturnNullWhenDateTimeOffsetIsNull() {
        assertNull(DateTimeUtils.extractOffsetDateTime(null));
    }
}

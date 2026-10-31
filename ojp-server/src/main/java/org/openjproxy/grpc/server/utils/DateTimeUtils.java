package org.openjproxy.grpc.server.utils;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public class DateTimeUtils {

    /**
     * Converts a com.microsoft.sqlserver.jdbc.DateTimeOffset into a java.time.OffsetDateTime.
     *
     * @param rawObject - instance of com.microsoft.sqlserver.jdbc.DateTimeOffset
     * @return java.time.OffsetDateTime
     */
    public OffsetDateTime extractOffsetDateTime(Object rawObject) {
        try {

            if (rawObject == null) {
                return null;
            }

            // Print class name to verify
            log.info("raw object class: {}", rawObject.getClass().getName());

            // Step 2: Access getTimestamp and getMinutesOffset via reflection
            Method getTimestampMethod = rawObject.getClass().getMethod("getTimestamp");
            Method getMinutesOffsetMethod = rawObject.getClass().getMethod("getMinutesOffset");

            Timestamp timestamp = (Timestamp) getTimestampMethod.invoke(rawObject);
            int offsetMinutes = (Integer) getMinutesOffsetMethod.invoke(rawObject);

            // Step 3: Convert to OffsetDateTime. DateTimeOffset.getTimestamp() is the UTC instant,
            // so apply the offset to that instant instead of reinterpreting its local wall-clock.
            ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetMinutes * 60);
            return timestamp.toInstant().atOffset(offset);

        } catch (Exception e) {
            throw new RuntimeException("Failed to convert DateTimeOffset to OffsetDateTime", e);
        }
    }
}

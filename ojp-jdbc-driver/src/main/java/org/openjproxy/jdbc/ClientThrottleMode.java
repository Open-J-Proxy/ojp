package org.openjproxy.jdbc;

public enum ClientThrottleMode {
    OFF, PROACTIVE, REACTIVE, COMBINED;

    public static ClientThrottleMode fromString(String value) {
        if (value == null) {
            return REACTIVE;
        }
        switch (value.trim().toUpperCase()) {
            case "OFF": return OFF;
            case "PROACTIVE": return PROACTIVE;
            case "COMBINED": return COMBINED;
            default: return REACTIVE;
        }
    }
}

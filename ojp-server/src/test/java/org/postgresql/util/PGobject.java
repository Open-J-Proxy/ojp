package org.postgresql.util;

/**
 * Minimal PGobject test double used to verify reflection-based reconstruction without adding a pgjdbc dependency.
 */
public class PGobject {

    private String type;
    private String value;

    public String getType() {
        return this.type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getValue() {
        return this.value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}

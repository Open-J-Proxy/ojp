package org.openjproxy.jdbc;

import org.openjproxy.grpc.dto.Parameter;

import java.util.List;

/**
 * Reusable constants. Mainly to save memory by creating less repeated objects.
 */
public class Constants {
    public static final List<Parameter> EMPTY_PARAMETERS_LIST = List.of();
    public static final String USER = "user";
    public static final String PASSWORD = "password";
    public static final String SHA_256 = "SHA-256";
    public static final String OJP_DRIVER_PREFIX = "ojp_";
    public static final String EMPTY_STRING = "";
    public static final String H2_DRIVER_CLASS = "org.h2.Driver";
    public static final List<Object> EMPTY_OBJECT_LIST = List.of();

}

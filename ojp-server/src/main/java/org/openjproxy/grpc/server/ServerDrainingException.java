package org.openjproxy.grpc.server;

import org.openjproxy.constants.CommonConstants;

import java.sql.SQLException;

/**
 * Thrown when the server is draining (graceful shutdown in progress) and a request
 * would create a new session or connection.
 *
 * <p>It extends {@link SQLException} (SQLState {@code 08001}) so that every action that reports errors
 * through {@link GrpcExceptionHandler#sendSQLExceptionMetadata} automatically turns it into
 * {@code Status.UNAVAILABLE} with the {@value CommonConstants#SERVER_DRAINING_TRAILER_KEY}
 * trailer, without having to add special handling to each action.</p>
 */
public class ServerDrainingException extends SQLException {

    private static final String SQLSTATE_CONNECTION_FAILURE = "08001";

    public ServerDrainingException() {
        super(CommonConstants.SERVER_DRAINING_DESCRIPTION
                + ": not accepting new sessions, retry on another server", SQLSTATE_CONNECTION_FAILURE);
    }
}

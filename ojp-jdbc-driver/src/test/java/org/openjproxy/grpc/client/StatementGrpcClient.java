package org.openjproxy.grpc.client;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.StatementServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import org.openjproxy.grpc.GrpcChannelFactory;

import java.sql.SQLException;

import static org.openjproxy.grpc.client.GrpcExceptionHandler.handle;

public class StatementGrpcClient {
    public static void main(String[] args) throws SQLException {
            ManagedChannel channel = GrpcChannelFactory.createChannel("localhost", 8080);

        StatementServiceGrpc.StatementServiceBlockingStub stub
                = StatementServiceGrpc.newBlockingStub(channel);

        try {
            SessionInfo sessionInfo = stub.connect(ConnectionDetails.newBuilder()
                    .setUrl("jdbc:ojp_h2:~/test")
                    .setUser("sa")
                    .setPassword("").build());
            sessionInfo.getConnHash();
        } catch (StatusRuntimeException e) {
            handle(e);
        }
        channel.shutdown();
    }
}

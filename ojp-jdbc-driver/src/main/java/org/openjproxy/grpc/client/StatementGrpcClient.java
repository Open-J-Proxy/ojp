package org.openjproxy.grpc.client;

import org.openjproxy.grpc.GrpcChannelFactory;
import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.StatementServiceGrpc;
import org.openjproxy.config.GrpcClientConfig;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Properties;

import static org.openjproxy.grpc.client.GrpcExceptionHandler.handle;

public class StatementGrpcClient {
    private static int maxOutboundMessageSize = 16777216;

    static GrpcClientConfig grpcConfig;

    public StatementGrpcClient() {
        initializeGrpcConfig();
    }

    public static void initializeGrpcConfig() {
        try {
            grpcConfig = GrpcClientConfig.load();
        } catch (IOException e) {
            e.printStackTrace();
            grpcConfig = new GrpcClientConfig(new Properties());
        }

        maxOutboundMessageSize = grpcConfig.getMaxOutboundMessageSize();
    }

    public static void main(String[] args) throws SQLException {
        ManagedChannel channel = GrpcChannelFactory.createChannel("localhost", 8080);

        OutboundSizeLimitingInterceptor sizeInterceptor = new OutboundSizeLimitingInterceptor(maxOutboundMessageSize);
        StatementServiceGrpc.StatementServiceBlockingStub stub = StatementServiceGrpc.newBlockingStub(channel)
                .withInterceptors(sizeInterceptor);

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

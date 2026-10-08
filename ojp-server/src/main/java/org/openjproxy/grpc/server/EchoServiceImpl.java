package org.openjproxy.grpc.server;

import io.grpc.stub.StreamObserver;
import org.openjproxy.grpc.EchoRequest;
import org.openjproxy.grpc.EchoResponse;
import org.openjproxy.grpc.EchoServiceGrpc;

public final class EchoServiceImpl extends EchoServiceGrpc.EchoServiceImplBase {
    @Override
    public void echo(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
        responseObserver.onNext(EchoResponse.newBuilder().setMessage(request.getMessage()).build());
        responseObserver.onCompleted();
    }
}

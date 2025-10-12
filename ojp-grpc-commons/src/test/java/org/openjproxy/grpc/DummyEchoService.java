package org.openjproxy.grpc;

import io.grpc.stub.StreamObserver;

// Just echoing back the message
public class DummyEchoService extends EchoServiceGrpc.EchoServiceImplBase {
	@Override
	public void echo(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
		responseObserver.onNext(EchoResponse.newBuilder()
				.setMessage(request.getMessage())
				.build());
		responseObserver.onCompleted();
	}
}

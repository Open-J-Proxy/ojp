package org.openjproxy.grpc;

import echo.EchoRequest;
import echo.EchoResponse;
import io.grpc.stub.StreamObserver;
import org.openjproxy.grpc.EchoServiceGrpc;

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

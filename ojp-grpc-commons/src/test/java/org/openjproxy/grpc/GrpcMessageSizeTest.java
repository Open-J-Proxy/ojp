package org.openjproxy.grpc;

import echo.EchoRequest;
import echo.EchoResponse;
import echo.EchoServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

public class GrpcMessageSizeTest {

	static final int PORT = 5555; // Just a random port
	static final int SIZE_LIMIT = 4 * 1024 * 1024; // Since 4MB is the default
	static Server server;

	@BeforeAll
	static void startServer() throws Exception {
		server = ServerBuilder.forPort(PORT)
				.addService(new DummyEchoService())
				.maxInboundMessageSize(SIZE_LIMIT)
				.build()
				.start();
	}

	@AfterAll
	static void stopServer() throws Exception {
		server.shutdownNow();
	}

	EchoServiceGrpc.EchoServiceBlockingStub getStub(int inboundSize, int outboundSize) {
		ManagedChannel channel = GrpcChannelFactory.createChannel("localhost", PORT, inboundSize, outboundSize);
		return EchoServiceGrpc.newBlockingStub(channel);
	}

	String generateMessageOfSize(int sizeInBytes) {
		return "a".repeat(sizeInBytes);
	}

	// This should pass
	@Test
	void testMessageBelowSizeLimit() {
		String message = generateMessageOfSize(SIZE_LIMIT - 100); // Just under 4MB
		EchoServiceGrpc.EchoServiceBlockingStub stub = getStub(SIZE_LIMIT, SIZE_LIMIT);
		EchoResponse response = stub.echo(EchoRequest.newBuilder().setMessage(message).build());
		assertEquals(message, response.getMessage());
	}

	// This should pass
	@Test
	void testMessageAtSizeLimit() {
		String message = generateMessageOfSize(SIZE_LIMIT); // Exactly 4MB
		EchoServiceGrpc.EchoServiceBlockingStub stub = getStub(SIZE_LIMIT, SIZE_LIMIT);
		EchoResponse response = stub.echo(EchoRequest.newBuilder().setMessage(message).build());
		assertEquals(message, response.getMessage());
	}

	// This should fail
	@Test
	void testMessageExceedsSizeLimit() {
		String message = generateMessageOfSize(SIZE_LIMIT + 1); // Just over 4MB
		EchoServiceGrpc.EchoServiceBlockingStub stub = getStub(SIZE_LIMIT, SIZE_LIMIT);

		Exception exception = assertThrows(StatusRuntimeException.class, () -> {
			stub.echo(EchoRequest.newBuilder().setMessage(message).build());
		});

		System.out.println("Expected exception: " + exception.getMessage());
		assertTrue(exception.getMessage().contains("RESOURCE_EXHAUSTED"));
	}
}

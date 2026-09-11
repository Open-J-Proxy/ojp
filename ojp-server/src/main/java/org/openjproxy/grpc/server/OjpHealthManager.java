package org.openjproxy.grpc.server;

import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.protobuf.services.HealthStatusManager;
import lombok.Getter;

/**
 * OJP Health Manager for managing the health status of services in the OJP server.
 * This class provides methods to initialize the health status manager and set service statuses.
 */
public class OjpHealthManager {

	@Getter
	private static HealthStatusManager healthStatusManager;

	@Getter
	public enum Services {
		OJP_SERVER(""),
		OPENTELEMETRY_SERVICE("opentelemetry_service");

		private final String serviceName;

		Services(String serviceName) {
			this.serviceName = serviceName;
		}

	}

	public static void initialize() {
		if (healthStatusManager == null) {
			healthStatusManager = new HealthStatusManager();
			healthStatusManager.clearStatus(""); // Clear all statuses
		}

		// Set initial statuses
		healthStatusManager.setStatus(Services.OJP_SERVER.getServiceName(),
				HealthCheckResponse.ServingStatus.NOT_SERVING);
		healthStatusManager.setStatus(Services.OPENTELEMETRY_SERVICE.getServiceName(),
				HealthCheckResponse.ServingStatus.NOT_SERVING);
	}

	public static void setServiceStatus(Services service, HealthCheckResponse.ServingStatus status) {
		if (healthStatusManager == null) {
			initialize();
		}
		healthStatusManager.setStatus(service.getServiceName(), status);
	}
}

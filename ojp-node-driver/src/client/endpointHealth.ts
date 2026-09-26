import { OjpServerEndpoint } from './connectionString';

/**
 * Default cooldown period (ms) an endpoint is skipped for new connection attempts
 * after a connection-level failure, mirroring the "unhealthy retry delay" concept from
 * the reference JDBC driver's `MultinodeConnectionManager`. After this period elapses,
 * the endpoint becomes eligible again — there is no active background health-check
 * ping; the next real connection attempt against it IS the health check.
 */
export const DEFAULT_UNHEALTHY_RETRY_DELAY_MS = 30_000;

interface EndpointState {
  unhealthyUntil: number;
}

/**
 * Endpoint health is tracked process-wide (shared across every `OjpClient` instance in
 * the same Node.js process), not per-client — mirroring the reference JDBC driver,
 * where the connection manager/health directory is shared across every
 * `java.sql.Connection` in the same JVM. This lets one client's failure detection
 * immediately benefit every other client's endpoint selection.
 */
const endpointState = new Map<string, EndpointState>();
let roundRobinCursor = 0;

function keyOf(endpoint: OjpServerEndpoint): string {
  return `${endpoint.host}:${endpoint.port}`;
}

/**
 * Marks an endpoint as unhealthy after a connection-level failure (unreachable,
 * timeout, transport error). Only connection-level failures should call this — SQL/DB
 * errors (syntax errors, bad credentials, missing tables, etc.) must NOT mark a node
 * unhealthy, mirroring `GrpcExceptionHandler` in the reference JDBC driver.
 */
export function markEndpointUnhealthy(
  endpoint: OjpServerEndpoint,
  retryDelayMs: number = DEFAULT_UNHEALTHY_RETRY_DELAY_MS,
): void {
  endpointState.set(keyOf(endpoint), { unhealthyUntil: Date.now() + retryDelayMs });
}

export function isEndpointHealthy(endpoint: OjpServerEndpoint): boolean {
  const state = endpointState.get(keyOf(endpoint));
  return !state || Date.now() >= state.unhealthyUntil;
}

/**
 * Returns the endpoints in the order they should be tried for a new connection:
 * starting from a shared, rotating round-robin cursor (so consecutive `OjpClient`
 * instances spread their initial attempts across the fleet instead of always hammering
 * the same node first), with healthy endpoints ordered before unhealthy ones. Unhealthy
 * endpoints are still included, as a last resort, so the driver never permanently locks
 * itself out if health tracking turns out to be stale or wrong.
 */
export function orderEndpointsForAttempt(endpoints: OjpServerEndpoint[]): OjpServerEndpoint[] {
  if (endpoints.length <= 1) {
    return [...endpoints];
  }
  const start = roundRobinCursor % endpoints.length;
  roundRobinCursor = (roundRobinCursor + 1) % endpoints.length;
  const rotated = [...endpoints.slice(start), ...endpoints.slice(0, start)];
  const healthy = rotated.filter(isEndpointHealthy);
  const unhealthy = rotated.filter((endpoint) => !isEndpointHealthy(endpoint));
  return [...healthy, ...unhealthy];
}

/** Test-only helper to reset the shared health/round-robin state between test cases. */
export function resetEndpointHealthForTests(): void {
  endpointState.clear();
  roundRobinCursor = 0;
}

import { OjpServerEndpoint } from './connectionString';

/**
 * Default cooldown period (ms) an endpoint is skipped for new connection attempts
 * after a connection-level failure, mirroring the "unhealthy retry delay" concept from
 * the reference JDBC driver's `MultinodeConnectionManager`. Scheduled and on-connect
 * heartbeat probes can mark an endpoint healthy sooner or extend its unhealthy period.
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
const activeSessions = new Map<string, number>();
let roundRobinCursor = 0;
const lastHealthCheckAt = new Map<string, number>();

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

export function markEndpointHealthy(endpoint: OjpServerEndpoint): void {
  endpointState.delete(keyOf(endpoint));
}

export function registerEndpointSession(endpoint: OjpServerEndpoint): void {
  const key = keyOf(endpoint);
  activeSessions.set(key, (activeSessions.get(key) ?? 0) + 1);
}

export function unregisterEndpointSession(endpoint: OjpServerEndpoint): void {
  const key = keyOf(endpoint);
  const count = activeSessions.get(key) ?? 0;
  if (count <= 1) {
    activeSessions.delete(key);
  } else {
    activeSessions.set(key, count - 1);
  }
}

export function activeEndpointSessions(endpoint: OjpServerEndpoint): number {
  return activeSessions.get(keyOf(endpoint)) ?? 0;
}

export function shouldProbeEndpoints(endpoints: OjpServerEndpoint[], intervalMs: number): boolean {
  const now = Date.now();
  const key = JSON.stringify(endpoints.map(keyOf).sort());
  const lastProbe = lastHealthCheckAt.get(key);
  if (lastProbe !== undefined && now - lastProbe < intervalMs) {
    return false;
  }
  lastHealthCheckAt.set(key, now);
  return true;
}

export function clusterHealth(endpoints: OjpServerEndpoint[]): string {
  return endpoints
    .map((endpoint) => `${keyOf(endpoint)}(${isEndpointHealthy(endpoint) ? 'UP' : 'DOWN'})`)
    .join(';');
}

/**
 * Returns the endpoints in the order they should be tried for a new connection:
 * healthy endpoints come first and least-connections is the default selection policy.
 * Round-robin breaks ties and remains available as an explicit policy. Unhealthy
 * endpoints remain as a last resort so a stale health observation cannot permanently
 * lock the client out of the cluster.
 */
export function orderEndpointsForAttempt(
  endpoints: OjpServerEndpoint[],
  strategy: 'least-connections' | 'round-robin' = 'least-connections',
): OjpServerEndpoint[] {
  if (endpoints.length <= 1) {
    return [...endpoints];
  }
  const start = roundRobinCursor % endpoints.length;
  roundRobinCursor = (roundRobinCursor + 1) % endpoints.length;
  const rotated = [...endpoints.slice(start), ...endpoints.slice(0, start)];
  const healthy = rotated.filter(isEndpointHealthy);
  const unhealthy = rotated.filter((endpoint) => !isEndpointHealthy(endpoint));
  if (strategy === 'least-connections') {
    const leastLoadedFirst = (left: OjpServerEndpoint, right: OjpServerEndpoint): number =>
      activeEndpointSessions(left) - activeEndpointSessions(right);
    healthy.sort(leastLoadedFirst);
    unhealthy.sort(leastLoadedFirst);
  }
  return [...healthy, ...unhealthy];
}

/** Test-only helper to reset the shared health/round-robin state between test cases. */
export function resetEndpointHealthForTests(): void {
  endpointState.clear();
  activeSessions.clear();
  roundRobinCursor = 0;
  lastHealthCheckAt.clear();
}

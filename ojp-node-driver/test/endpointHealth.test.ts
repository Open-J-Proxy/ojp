import {
  activeEndpointSessions,
  clusterHealth,
  markEndpointHealthy,
  markEndpointUnhealthy,
  orderEndpointsForAttempt,
  registerEndpointSession,
  resetEndpointHealthForTests,
  shouldProbeEndpoints,
  unregisterEndpointSession,
} from '../src/client/endpointHealth';
import { OjpServerEndpoint } from '../src/client/connectionString';

describe('multinode endpoint health and selection', () => {
  const endpoints: OjpServerEndpoint[] = [
    { host: 'node1', port: 1059 },
    { host: 'node2', port: 1060 },
    { host: 'node3', port: 1061 },
  ];

  beforeEach(() => {
    resetEndpointHealthForTests();
  });

  it('prefers the endpoint with the fewest active sessions', () => {
    registerEndpointSession(endpoints[0]);
    registerEndpointSession(endpoints[0]);
    registerEndpointSession(endpoints[1]);

    expect(orderEndpointsForAttempt(endpoints)[0]).toEqual(endpoints[2]);
  });

  it('uses rotating round-robin order to break equal-load ties', () => {
    const first = orderEndpointsForAttempt(endpoints, 'round-robin');
    const second = orderEndpointsForAttempt(endpoints, 'round-robin');

    expect(first[0]).not.toEqual(second[0]);
    expect(new Set([...first, ...second].map(({ host }) => host))).toEqual(new Set(['node1', 'node2', 'node3']));
  });

  it('uses least-connections first and then round-robin among tied endpoints', () => {
    registerEndpointSession(endpoints[0]);

    const order = orderEndpointsForAttempt(endpoints);

    expect(order[0]).not.toEqual(endpoints[0]);
    expect(order.slice(1)).toContainEqual(endpoints[0]);
  });

  it('moves an unhealthy endpoint behind healthy endpoints', () => {
    markEndpointUnhealthy(endpoints[1], 60_000);

    const order = orderEndpointsForAttempt(endpoints);

    expect(order.slice(0, 2)).not.toContainEqual(endpoints[1]);
    expect(order[2]).toEqual(endpoints[1]);
  });

  it('reinstates a recovered endpoint as healthy', () => {
    markEndpointUnhealthy(endpoints[1], 60_000);
    markEndpointHealthy(endpoints[1]);

    expect(orderEndpointsForAttempt(endpoints)).toContainEqual(endpoints[1]);
    expect(clusterHealth(endpoints)).toContain('node2:1060(UP)');
  });

  it('tracks active session counts and does not underflow on repeated release', () => {
    registerEndpointSession(endpoints[0]);
    registerEndpointSession(endpoints[0]);
    unregisterEndpointSession(endpoints[0]);
    unregisterEndpointSession(endpoints[0]);
    unregisterEndpointSession(endpoints[0]);

    expect(activeEndpointSessions(endpoints[0])).toBe(0);
  });

  it('serializes endpoint health in configured endpoint order', () => {
    markEndpointUnhealthy(endpoints[2], 60_000);

    expect(clusterHealth(endpoints)).toBe(
      'node1:1059(UP);node2:1060(UP);node3:1061(DOWN)',
    );
  });

  it('limits probes to the configured interval', () => {
    jest.useFakeTimers();
    try {
      expect(shouldProbeEndpoints(5_000)).toBe(true);
      expect(shouldProbeEndpoints(5_000)).toBe(false);
      jest.advanceTimersByTime(5_000);
      expect(shouldProbeEndpoints(5_000)).toBe(true);
    } finally {
      jest.useRealTimers();
    }
  });
});

import { ClientThrottle, throttleFor } from '../src/client/clientThrottle';
import { OjpClient, OjpSqlError } from '../src';
import { SessionInfo } from '../src/proto/types';

const session = (signals: Partial<SessionInfo>): SessionInfo => ({
  connHash: 'throttle-test',
  clientUUID: 'client',
  sessionUUID: '',
  sessionStatus: 'SESSION_ACTIVE',
  ...signals,
});

describe('shared client admission control', () => {
  it.each(['', 'invalid'])('rejects invalid client throttle mode %j', (mode) => {
    const options = JSON.parse(JSON.stringify({ clientThrottleMode: mode }));
    expect(() => new OjpClient('jdbc:ojp[localhost:1059]_h2:mem:test', options))
      .toThrow(/clientThrottleMode/);
  });

  it('shares limits by pool and fails fast without consuming another permit', () => {
    const first = throttleFor('pool-a');
    expect(throttleFor('pool-a')).toBe(first);
    expect(throttleFor('pool-b')).not.toBe(first);
    first.update(session({ maxAdmission: 2, clientCount: 1 }));
    const release = first.acquire('reactive', false);
    expect(() => first.acquire('reactive', false)).toThrow(OjpSqlError);
    release();
    release();
    expect(() => first.acquire('reactive', false)()).not.toThrow();
  });

  it('uses the selected signal and bypasses admission for transactions and off mode', () => {
    const throttle = new ClientThrottle();
    throttle.update(session({
      maxAdmission: 8, observedPeak: 2, clientCount: 2,
      clusterHealth: 'a:1(UP);b:2(UP);c:3(DOWN)',
    }));
    const release = throttle.acquire('reactive', false);
    expect(() => throttle.acquire('combined', false)).toThrow(/overload/i);
    expect(() => throttle.acquire('off', false)()).not.toThrow();
    expect(() => throttle.acquire('reactive', true)()).not.toThrow();
    const proactive = Array.from({ length: 6 }, () => throttle.acquire('proactive', false));
    expect(() => throttle.acquire('proactive', false)).toThrow(/overload/i);
    proactive.forEach((done) => done());
    release();
  });

  it('decreases immediately and increases by at most one per update', () => {
    const throttle = new ClientThrottle();
    throttle.update(session({ maxAdmission: 2, clientCount: 1 }));
    throttle.update(session({ maxAdmission: 20, clientCount: 1 }));
    const one = throttle.acquire('proactive', false);
    const two = throttle.acquire('proactive', false);
    expect(() => throttle.acquire('proactive', false)).toThrow(/overload/i);
    throttle.update(session({ maxAdmission: 1, clientCount: 1 }));
    one();
    expect(() => throttle.acquire('proactive', false)).toThrow(/overload/i);
    two();
    expect(() => throttle.acquire('proactive', false)()).not.toThrow();
  });
});

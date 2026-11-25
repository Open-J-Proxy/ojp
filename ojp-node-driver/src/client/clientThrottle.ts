import { SessionInfo } from '../proto/types';
import { OjpSqlError } from './errors';

export type ClientThrottleMode = 'off' | 'proactive' | 'reactive' | 'combined';

export class ClientThrottle {
  private inFlight = 0;
  private proactive = Infinity;
  private reactive = Infinity;

  update(session: SessionInfo): void {
    const nodes = Math.max(1, (session.clusterHealth?.match(/\(UP(?:[,;)])/gi) ?? []).length);
    const clients = Math.max(1, session.clientCount ?? 0);
    const budget = (signal: number): number =>
      Math.max(1, Math.floor(Math.ceil(signal / clients) * nodes * 0.9));
    const advance = (current: number, next: number): number =>
      next < current ? next : Math.min(next, current + 1);
    const maxAdmission = session.maxAdmission ?? 0;
    const observedPeak = session.observedPeak ?? 0;
    if (maxAdmission > 0) {
      this.proactive = advance(this.proactive, budget(maxAdmission));
    }
    const reactiveSignal = observedPeak > 0 ? observedPeak : maxAdmission;
    if (reactiveSignal > 0) {
      this.reactive = advance(this.reactive, budget(reactiveSignal));
    }
  }

  acquire(mode: ClientThrottleMode, inTransaction: boolean): () => void {
    if (mode === 'off' || inTransaction) {
      return () => undefined;
    }
    const limit = mode === 'proactive' ? this.proactive
      : mode === 'reactive' ? this.reactive
        : Math.min(this.proactive, this.reactive);
    if (this.inFlight >= limit) {
      throw new OjpSqlError('Database overload: client admission limit exceeded.');
    }
    // Node's event loop executes this check and increment without an await.
    this.inFlight++;
    let released = false;
    return () => {
      if (!released) {
        this.inFlight = Math.max(0, this.inFlight - 1);
        released = true;
      }
    };
  }
}

const throttles = new Map<string, ClientThrottle>();

export function throttleFor(connHash: string): ClientThrottle {
  let throttle = throttles.get(connHash);
  if (!throttle) {
    throttle = new ClientThrottle();
    throttles.set(connHash, throttle);
  }
  return throttle;
}

export function resetClientThrottlesForTests(): void {
  throttles.clear();
}

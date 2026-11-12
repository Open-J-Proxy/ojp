import * as grpc from '@grpc/grpc-js';
import { OjpXAResource, OjpXAResourceHost } from '../src/xa/OjpXAResource';
import { OjpXaError } from '../src/xa/OjpXaError';
import { fromXidProto, toXidProto, xidEquals, xidToString, Xid } from '../src/xa/xid';
import * as XA from '../src/xa/xaConstants';
import { SessionInfo, XaRecoverResponse, XaResponse } from '../src/proto/types';
import { StatementServiceClient } from '../src/client/statementServiceClient';

function makeXid(gtrid: number, bqual = 1): Xid {
  return {
    formatId: 1,
    globalTransactionId: Buffer.from([gtrid]),
    branchQualifier: Buffer.from([bqual]),
  };
}

function makeSession(): SessionInfo {
  return {
    connHash: 'h',
    clientUUID: 'client-1',
    sessionUUID: 'session-1',
    sessionStatus: 'SESSION_ACTIVE',
    isXA: true,
  };
}

/** Fake StatementServiceClient exposing only the XA RPC surface, each callable overridden per test. */
function makeFakeClient(): jest.Mocked<Pick<
  StatementServiceClient,
  'xaStart' | 'xaEnd' | 'xaPrepare' | 'xaCommit' | 'xaRollback' | 'xaRecover' | 'xaForget'
  | 'xaSetTransactionTimeout' | 'xaGetTransactionTimeout' | 'xaIsSameRM'
>> {
  return {
    xaStart: jest.fn(),
    xaEnd: jest.fn(),
    xaPrepare: jest.fn(),
    xaCommit: jest.fn(),
    xaRollback: jest.fn(),
    xaRecover: jest.fn(),
    xaForget: jest.fn(),
    xaSetTransactionTimeout: jest.fn(),
    xaGetTransactionTimeout: jest.fn(),
    xaIsSameRM: jest.fn(),
  };
}

function makeHost(client: unknown, session: SessionInfo): OjpXAResourceHost & { updatedSessions: SessionInfo[] } {
  const updatedSessions: SessionInfo[] = [];
  return {
    updatedSessions,
    getClient: () => client as StatementServiceClient,
    getSession: () => session,
    updateSession: (s) => updatedSessions.push(s),
    mapError: (err) => (err instanceof Error ? err : new Error(String(err))),
  };
}

describe('Xid helpers', () => {
  it('round-trips through XidProto', () => {
    const xid = makeXid(5, 9);
    const proto = toXidProto(xid);
    expect(proto).toEqual({ formatId: 1, globalTransactionId: Buffer.from([5]), branchQualifier: Buffer.from([9]) });
    expect(fromXidProto(proto)).toEqual(xid);
  });

  it('compares structurally, not by reference', () => {
    const a = makeXid(1, 2);
    const b = makeXid(1, 2);
    const c = makeXid(1, 3);
    expect(xidEquals(a, b)).toBe(true);
    expect(xidEquals(a, c)).toBe(false);
  });

  it('renders a readable string', () => {
    const xid = makeXid(1, 2);
    expect(xidToString(xid)).toBe('Xid(formatId=1, gtrid=01, bqual=02)');
  });
});

describe('xaConstants (must match javax.transaction.xa exactly — verified via javap on JDK 25)', () => {
  it('XAResource flags', () => {
    expect(XA.TMNOFLAGS).toBe(0);
    expect(XA.TMJOIN).toBe(2097152);
    expect(XA.TMENDRSCAN).toBe(8388608);
    expect(XA.TMFAIL).toBe(536870912);
    expect(XA.TMONEPHASE).toBe(1073741824);
    expect(XA.TMRESUME).toBe(134217728);
    expect(XA.TMSTARTRSCAN).toBe(16777216);
    expect(XA.TMSUCCESS).toBe(67108864);
    expect(XA.TMSUSPEND).toBe(33554432);
    expect(XA.XA_OK).toBe(0);
    expect(XA.XA_RDONLY).toBe(3);
  });

  it('XAException error codes', () => {
    expect(XA.XA_RBBASE).toBe(100);
    expect(XA.XA_RBROLLBACK).toBe(100);
    expect(XA.XA_RBCOMMFAIL).toBe(101);
    expect(XA.XA_RBDEADLOCK).toBe(102);
    expect(XA.XA_RBINTEGRITY).toBe(103);
    expect(XA.XA_RBOTHER).toBe(104);
    expect(XA.XA_RBPROTO).toBe(105);
    expect(XA.XA_RBTIMEOUT).toBe(106);
    expect(XA.XA_RBTRANSIENT).toBe(107);
    expect(XA.XA_RBEND).toBe(107);
    expect(XA.XA_NOMIGRATE).toBe(9);
    expect(XA.XA_HEURHAZ).toBe(8);
    expect(XA.XA_HEURCOM).toBe(7);
    expect(XA.XA_HEURRB).toBe(6);
    expect(XA.XA_HEURMIX).toBe(5);
    expect(XA.XA_RETRY).toBe(4);
    expect(XA.XAER_ASYNC).toBe(-2);
    expect(XA.XAER_RMERR).toBe(-3);
    expect(XA.XAER_NOTA).toBe(-4);
    expect(XA.XAER_INVAL).toBe(-5);
    expect(XA.XAER_PROTO).toBe(-6);
    expect(XA.XAER_RMFAIL).toBe(-7);
    expect(XA.XAER_DUPID).toBe(-8);
    expect(XA.XAER_OUTSIDE).toBe(-9);
  });
});

describe('OjpXAResource', () => {
  const xid = makeXid(1);

  it('start() succeeds and updates the session', async () => {
    const client = makeFakeClient();
    const responseSession = makeSession();
    client.xaStart.mockImplementation((_req, cb) => cb(null, { session: responseSession, success: true, message: '' } as XaResponse));
    const host = makeHost(client, makeSession());
    const resource = new OjpXAResource(host);

    await resource.start(xid, XA.TMNOFLAGS);

    expect(client.xaStart).toHaveBeenCalledTimes(1);
    expect(host.updatedSessions).toEqual([responseSession]);
  });

  it('start() throws OjpXaError(errorCode=0) on a business-level failure', async () => {
    const client = makeFakeClient();
    client.xaStart.mockImplementation((_req, cb) =>
      cb(null, { session: makeSession(), success: false, message: 'duplicate xid' } as XaResponse));
    const resource = new OjpXAResource(makeHost(client, makeSession()));

    await expect(resource.start(xid)).rejects.toMatchObject({ errorCode: 0, message: 'duplicate xid' });
    await expect(resource.start(xid)).rejects.toBeInstanceOf(OjpXaError);
  });

  it('start() maps a connection-level transport failure to XAER_RMFAIL', async () => {
    const client = makeFakeClient();
    const grpcErr = Object.assign(new Error('server unavailable'), { code: grpc.status.UNAVAILABLE });
    client.xaStart.mockImplementation((_req, cb) => cb(grpcErr as grpc.ServiceError, undefined as never));
    const resource = new OjpXAResource(makeHost(client, makeSession()));

    await expect(resource.start(xid)).rejects.toMatchObject({ errorCode: XA.XAER_RMFAIL });
  });

  it('start() maps a non-connection-level transport failure to XAER_RMERR', async () => {
    const client = makeFakeClient();
    const grpcErr = Object.assign(new Error('invalid argument'), { code: grpc.status.INVALID_ARGUMENT });
    client.xaStart.mockImplementation((_req, cb) => cb(grpcErr as grpc.ServiceError, undefined as never));
    const resource = new OjpXAResource(makeHost(client, makeSession()));

    await expect(resource.start(xid)).rejects.toMatchObject({ errorCode: XA.XAER_RMERR });
  });

  it.each(['end', 'commit', 'rollback', 'forget'] as const)(
    '%s() always maps a transport failure to XAER_RMERR, even when connection-level',
    async (op) => {
      const client = makeFakeClient();
      const grpcErr = Object.assign(new Error('server unavailable'), { code: grpc.status.UNAVAILABLE });
      const rpcName = `xa${op.charAt(0).toUpperCase()}${op.slice(1)}` as 'xaEnd' | 'xaCommit' | 'xaRollback' | 'xaForget';
      (client[rpcName] as jest.Mock).mockImplementation((_req: unknown, cb: (err: unknown, res: unknown) => void) =>
        cb(grpcErr, undefined));
      const resource = new OjpXAResource(makeHost(client, makeSession()));

      const call = op === 'commit' ? resource.commit(xid, true) : (resource as unknown as Record<string, (x: Xid) => Promise<void>>)[op](xid);
      await expect(call).rejects.toMatchObject({ errorCode: XA.XAER_RMERR });
    },
  );

  it('prepare() returns the numeric result and updates the session', async () => {
    const client = makeFakeClient();
    const responseSession = makeSession();
    client.xaPrepare.mockImplementation((_req, cb) => cb(null, { session: responseSession, result: XA.XA_RDONLY }));
    const host = makeHost(client, makeSession());

    const result = await new OjpXAResource(host).prepare(xid);

    expect(result).toBe(XA.XA_RDONLY);
    expect(host.updatedSessions).toEqual([responseSession]);
  });

  it('recover() maps XidProtos back to Xids', async () => {
    const client = makeFakeClient();
    const protoXid = toXidProtoOf(makeXid(7, 8));
    client.xaRecover.mockImplementation((_req, cb) =>
      cb(null, { session: makeSession(), xids: [protoXid] } as XaRecoverResponse));
    const resource = new OjpXAResource(makeHost(client, makeSession()));

    const xids = await resource.recover(XA.TMSTARTRSCAN | XA.TMENDRSCAN);

    expect(xids).toEqual([makeXid(7, 8)]);
  });

  it('recover() maps a transient transport failure (DEADLINE_EXCEEDED) to XAER_RMFAIL', async () => {
    const client = makeFakeClient();
    const grpcErr = Object.assign(new Error('deadline exceeded'), { code: grpc.status.DEADLINE_EXCEEDED });
    client.xaRecover.mockImplementation((_req, cb) => cb(grpcErr as grpc.ServiceError, undefined as never));
    const resource = new OjpXAResource(makeHost(client, makeSession()));

    await expect(resource.recover(XA.TMSTARTRSCAN)).rejects.toMatchObject({ errorCode: XA.XAER_RMFAIL });
  });

  it('recover() maps a non-transient transport failure to XAER_RMERR', async () => {
    const client = makeFakeClient();
    const grpcErr = Object.assign(new Error('boom'), { code: grpc.status.INTERNAL });
    client.xaRecover.mockImplementation((_req, cb) => cb(grpcErr as grpc.ServiceError, undefined as never));
    const resource = new OjpXAResource(makeHost(client, makeSession()));

    await expect(resource.recover(XA.TMSTARTRSCAN)).rejects.toMatchObject({ errorCode: XA.XAER_RMERR });
  });

  it('isSameRM() compares two resources by their sessions', async () => {
    const client = makeFakeClient();
    client.xaIsSameRM.mockImplementation((_req, cb) => cb(null, { isSame: true }));
    const resourceA = new OjpXAResource(makeHost(client, makeSession()));
    const resourceB = new OjpXAResource(makeHost(client, makeSession()));

    await expect(resourceA.isSameRM(resourceB)).resolves.toBe(true);
  });

  it('setTransactionTimeout()/getTransactionTimeout() round-trip', async () => {
    const client = makeFakeClient();
    client.xaSetTransactionTimeout.mockImplementation((_req, cb) => cb(null, { session: makeSession(), success: true }));
    client.xaGetTransactionTimeout.mockImplementation((_req, cb) => cb(null, { session: makeSession(), seconds: 120 }));
    const resource = new OjpXAResource(makeHost(client, makeSession()));

    await expect(resource.setTransactionTimeout(120)).resolves.toBe(true);
    await expect(resource.getTransactionTimeout()).resolves.toBe(120);
  });
});

function toXidProtoOf(xid: Xid) {
  return toXidProto(xid);
}

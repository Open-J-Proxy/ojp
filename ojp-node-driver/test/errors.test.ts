import * as grpc from '@grpc/grpc-js';
import * as protobuf from 'protobufjs';
import { isConnectionLevelError, OjpConnectionError, OjpSqlError, toOjpError } from '../src/client/errors';
import { SQL_ERROR_METADATA_KEY } from '../src/proto/errorCodec';

function sqlError(type: number): Error {
  const metadata = new grpc.Metadata();
  metadata.set(
    SQL_ERROR_METADATA_KEY,
    Buffer.from(protobuf.Writer.create().uint32(10).string('database error').uint32(32).int32(type).finish()),
  );
  return Object.assign(new Error('RPC failed'), { code: grpc.status.INTERNAL, metadata });
}

describe('SQL error metadata', () => {
  it('classifies transient connection errors for failover', () => {
    const raw = sqlError(2);
    const mapped = toOjpError(raw);
    expect(mapped).toBeInstanceOf(OjpConnectionError);
    expect(mapped.message).toBe('database error');
    expect(isConnectionLevelError(raw)).toBe(true);
  });

  it('does not classify ordinary SQL errors as endpoint failures', () => {
    const raw = sqlError(0);
    expect(toOjpError(raw)).toBeInstanceOf(OjpSqlError);
    expect(isConnectionLevelError(raw)).toBe(false);
  });

  it('does not mark a cancelled RPC as an endpoint failure', () => {
    const raw = Object.assign(new Error('cancelled'), {
      code: grpc.status.CANCELLED,
      details: 'Cancelled on client',
    });
    expect(toOjpError(raw)).toBeInstanceOf(OjpConnectionError);
    expect(isConnectionLevelError(raw)).toBe(false);
  });

  it('does not classify pool exhaustion as a transport failure', () => {
    const raw = Object.assign(new Error('connection pool exhausted'), {
      code: grpc.status.RESOURCE_EXHAUSTED,
      details: 'connection pool exhausted',
    });
    expect(isConnectionLevelError(raw)).toBe(false);
  });

  it('classifies unstructured INTERNAL transport failures but not SQL trailers', () => {
    const raw = Object.assign(new Error('server internal failure'), { code: grpc.status.INTERNAL });
    expect(isConnectionLevelError(raw)).toBe(true);

    const sql = sqlError(0);
    expect(isConnectionLevelError(sql)).toBe(false);
  });

  it('does not classify a lost server-session binding as a transport failure', () => {
    const raw = Object.assign(new Error('Session abc binding was lost'), {
      code: grpc.status.INTERNAL,
      details: 'Session abc binding was lost',
    });
    expect(isConnectionLevelError(raw)).toBe(false);
  });

  it('does not classify pool exhaustion carried by a transient SQL trailer as transport failure', () => {
    const metadata = new grpc.Metadata();
    metadata.set(
      SQL_ERROR_METADATA_KEY,
      Buffer.from(protobuf.Writer.create().uint32(10).string('connection pool exhausted').uint32(32).int32(2).finish()),
    );
    const raw = Object.assign(new Error('RPC failed'), { code: grpc.status.INTERNAL, metadata });
    expect(isConnectionLevelError(raw)).toBe(false);
    expect(toOjpError(raw)).toBeInstanceOf(OjpSqlError);
  });
});

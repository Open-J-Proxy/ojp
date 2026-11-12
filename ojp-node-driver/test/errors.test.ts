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
});

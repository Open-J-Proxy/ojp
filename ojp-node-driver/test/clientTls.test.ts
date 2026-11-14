import * as grpc from '@grpc/grpc-js';
import { OjpClient } from '../src';

describe('OjpClient TLS options', () => {
  it('creates a TLS channel with the configured server trust and client identity', () => {
    const rootCert = Buffer.from('root');
    const privateKey = Buffer.from('private key');
    const certChain = Buffer.from('client certificate');
    const createSsl = jest.spyOn(grpc.credentials, 'createSsl')
      .mockReturnValue(grpc.credentials.createInsecure());

    try {
      new OjpClient('jdbc:ojp[localhost:1059]_h2:mem:test', {
        tls: { rootCert, privateKey, certChain },
      });

      expect(createSsl).toHaveBeenCalledWith(rootCert, privateKey, certChain);
    } finally {
      createSsl.mockRestore();
    }
  });

  it('requires a client key and certificate to be configured together', () => {
    expect(() => new OjpClient('jdbc:ojp[localhost:1059]_h2:mem:test', {
      tls: { privateKey: Buffer.from('private key') },
    })).toThrow('tls.privateKey and tls.certChain must be provided together.');
  });

  it('rejects contradictory insecure and TLS options', () => {
    expect(() => new OjpClient('jdbc:ojp[localhost:1059]_h2:mem:test', {
      insecure: true,
      tls: { rootCert: Buffer.from('root') },
    })).toThrow('tls options cannot be combined with insecure: true.');
  });
});

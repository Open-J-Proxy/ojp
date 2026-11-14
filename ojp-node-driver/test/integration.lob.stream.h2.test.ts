import { OjpClient } from '../src/client/OjpClient';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_LOB_H2_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;
const connections = readConnectionFixtures('h2_connections.csv', process.env.OJP_TEST_H2_CSV);

describeIfEnabled.each(connections)('OjpClient - H2 LOB streams: %s', (jdbcUrl, user, password) => {
  const url = ojpUrl(process.env.OJP_TEST_H2_ADDR ?? 'localhost:1059', jdbcUrl);
  let client: OjpClient;

  beforeAll(async () => {
    client = new OjpClient(url, { user, password });
    await client.connect();
  }, 30_000);

  afterAll(async () => {
    if (client) {
      await client.close();
    }
  }, 30_000);

  it('reads an uploaded BLOB stream with exact bytes and length', async () => {
    const content = Buffer.alloc(60_000);
    for (let index = 0; index < content.length; index++) {
      content[index] = (index * 31) % 256;
    }
    const reference = await client.createLob(content, 'BLOB');
    const chunks: Buffer[] = [];

    for await (const chunk of client.readLobStream(reference)) {
      chunks.push(Buffer.from(chunk));
    }

    const result = Buffer.concat(chunks);
    expect(result.length).toBe(content.length);
    expect(result.equals(content)).toBe(true);
  }, 30_000);

  it('reads a Unicode CLOB stream with exact text and encoded byte length', async () => {
    const content = 'H2 CLOB stream: äöü ñ 中文 🚀 — '.repeat(800);
    const reference = await client.createLob(content, 'CLOB');
    const chunks: Buffer[] = [];

    for await (const chunk of client.readLobStream(reference)) {
      chunks.push(Buffer.from(chunk));
    }

    const result = Buffer.concat(chunks);
    expect(result.length).toBe(Buffer.byteLength(content, 'utf8'));
    expect(result.toString('utf8')).toBe(content);
  }, 30_000);
});

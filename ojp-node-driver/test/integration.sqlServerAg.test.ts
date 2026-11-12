/**
 * Real OJP server and SQL Server integration, enabled by
 * OJP_ENABLE_SQLSERVER_AG_TESTS=true. Every CSV connection row runs the full suite.
 */
import { OjpClient } from '../src/client/OjpClient';
import { ojpUrl, readConnectionFixtures } from './connectionFixtures';

const enabled = process.env.OJP_ENABLE_SQLSERVER_AG_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;

const connections = readConnectionFixtures('sqlserver_connections.csv', process.env.OJP_TEST_SQLSERVER_CSV);

describeIfEnabled.each(connections)('OjpClient - SQL Server: %s', (jdbcUrl, OJP_USER, fixturePassword) => {
  const OJP_URL = ojpUrl(process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059', jdbcUrl);
  const OJP_PASSWORD = process.env.OJP_TEST_SQLSERVER_PASSWORD ?? fixturePassword;
  const tableName = `ojp_node_driver_ag_test_${Date.now()}`;

  const newClient = async (): Promise<OjpClient> => {
    const c = new OjpClient(OJP_URL, { user: OJP_USER, password: OJP_PASSWORD });
    await c.connect();
    return c;
  };

  let client: OjpClient;

  beforeAll(async () => {
    client = await newClient();
    await client.executeUpdate(
      `CREATE TABLE ${tableName} (
         id INT PRIMARY KEY,
         label NVARCHAR(200),
         price DECIMAL(10,2),
         active BIT,
         created_at DATETIME2,
         payload VARBINARY(MAX)
       )`,
    );
  }, 30_000);

  afterAll(async () => {
    if (client) {
      await client.executeUpdate(`DROP TABLE IF EXISTS ${tableName}`);
      await client.close();
    }
  }, 30_000);

  let txClient: OjpClient;

  afterEach(async () => {
    if (txClient) {
      await txClient.close();
    }
  });

  it('should insert and read a row with SQL Server types (NVARCHAR, DECIMAL, BIT, DATETIME2)', async () => {
    const now = new Date('2026-01-15T10:30:00.000Z');
    const affected = await client.executeUpdate(
      `INSERT INTO ${tableName} (id, label, price, active, created_at) VALUES (?, ?, ?, ?, ?)`,
      [1, 'first row', 1234.56, true, now],
    );
    expect(affected).toBe(1);

    const { columns, rows } = await client.executeQuery(
      `SELECT id, label, price, active, created_at FROM ${tableName} WHERE id = ?`,
      [1],
    );
    expect(columns.map((c) => c.toLowerCase())).toEqual(['id', 'label', 'price', 'active', 'created_at']);
    expect(rows).toHaveLength(1);
    expect(rows[0].id).toBe(1);
    expect(rows[0].label).toBe('first row');
    // DECIMAL/NUMERIC/MONEY arrive as a string (decoded BigDecimalWire), avoiding precision loss.
    expect(rows[0].price).toBe('1234.56');
    expect(rows[0].active).toBe(true);
    expect((rows[0].created_at as Date).getTime()).toBe(now.getTime());
  }, 15_000);

  it('should update and delete rows', async () => {
    await client.executeUpdate(`INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [2, 'to be updated']);

    const updated = await client.executeUpdate(`UPDATE ${tableName} SET label = ? WHERE id = ?`, ['updated', 2]);
    expect(updated).toBe(1);

    const { rows: afterUpdate } = await client.executeQuery(`SELECT label FROM ${tableName} WHERE id = ?`, [2]);
    expect(afterUpdate[0].label).toBe('updated');

    const deleted = await client.executeUpdate(`DELETE FROM ${tableName} WHERE id = ?`, [2]);
    expect(deleted).toBe(1);

    const { rows: afterDelete } = await client.executeQuery(`SELECT * FROM ${tableName} WHERE id = ?`, [2]);
    expect(afterDelete).toHaveLength(0);
  }, 15_000);

  it('should support transaction commit', async () => {
    txClient = await newClient();
    await txClient.startTransaction();
    await txClient.executeUpdate(`INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [10, 'committed']);
    await txClient.commit();

    const { rows } = await txClient.executeQuery(`SELECT label FROM ${tableName} WHERE id = ?`, [10]);
    expect(rows).toHaveLength(1);
    expect(rows[0].label).toBe('committed');
  }, 15_000);

  it('should support transaction rollback', async () => {
    txClient = await newClient();
    await txClient.startTransaction();
    await txClient.executeUpdate(`INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [11, 'should disappear']);
    await txClient.rollback();

    const { rows } = await txClient.executeQuery(`SELECT label FROM ${tableName} WHERE id = ?`, [11]);
    expect(rows).toHaveLength(0);
  }, 15_000);

  it('should return null for null values', async () => {
    await client.executeUpdate(`INSERT INTO ${tableName} (id, label) VALUES (?, ?)`, [20, null]);
    const { rows } = await client.executeQuery(`SELECT label FROM ${tableName} WHERE id = ?`, [20]);
    expect(rows[0].label).toBeNull();
  }, 15_000);

  it('should throw OjpSqlError for invalid SQL', async () => {
    txClient = await newClient();
    await expect(txClient.executeQuery('SELECT * FROM table_that_does_not_exist')).rejects.toThrow();
  }, 15_000);

  it('should read MONEY columns without an external sample database', async () => {
    const moneyTable = `${tableName}_money`;
    await client.executeUpdate(`CREATE TABLE ${moneyTable} (ProductID INT, Name NVARCHAR(100), ListPrice MONEY)`);
    try {
      await client.executeUpdate(`INSERT INTO ${moneyTable} VALUES (1, 'product', 123.45)`);
      const { columns, rows } = await client.executeQuery(
        `SELECT ProductID, Name, ListPrice FROM ${moneyTable} ORDER BY ProductID`,
      );
      expect(columns).toEqual(['ProductID', 'Name', 'ListPrice']);
      expect(rows).toHaveLength(1);
      expect(rows[0].ProductID).toBe(1);
      expect(rows[0].Name).toBe('product');
      expect(typeof rows[0].ListPrice).toBe('string');
      expect(Number(rows[0].ListPrice)).toBe(123.45);
    } finally {
      await client.executeUpdate(`DROP TABLE IF EXISTS ${moneyTable}`);
    }
  }, 15_000);

  describe('row-by-row mode (VARBINARY(MAX) forces RESULT_SET_ROW_BY_ROW_MODE on SQL Server)', () => {
    it('should read a VARBINARY(MAX) column correctly via fetchNextRows (row-by-row mode)', async () => {
      const payload = Buffer.from('Hello OJP LOB test - SQL Server row-by-row mode', 'utf8');
      await client.executeUpdate(`INSERT INTO ${tableName} (id, label, payload) VALUES (?, ?, ?)`, [30, 'with lob', payload]);

      const { rows } = await client.executeQuery(`SELECT payload FROM ${tableName} WHERE id = ?`, [30]);
      expect(rows).toHaveLength(1);
      expect(Buffer.isBuffer(rows[0].payload)).toBe(true);
      expect((rows[0].payload as Buffer).equals(payload)).toBe(true);
    }, 15_000);

    it('should paginate multiple rows in row-by-row mode via executeQueryStream', async () => {
      const lobTableName = `${tableName}_lob`;
      await client.executeUpdate(`CREATE TABLE ${lobTableName} (id INT PRIMARY KEY, payload VARBINARY(MAX))`);
      try {
        const total = 12;
        for (let i = 0; i < total; i++) {
          await client.executeUpdate(
            `INSERT INTO ${lobTableName} (id, payload) VALUES (?, ?)`,
            [i, Buffer.from(`payload-${i}`, 'utf8')],
          );
        }

        let count = 0;
        for await (const row of client.executeQueryStream(`SELECT id, payload FROM ${lobTableName} ORDER BY id`)) {
          expect((row.payload as Buffer).toString('utf8')).toBe(`payload-${count}`);
          count++;
        }
        expect(count).toBe(total);
      } finally {
        await client.executeUpdate(`DROP TABLE IF EXISTS ${lobTableName}`);
      }
    }, 30_000);
  });

  describe('createLob (BLOB upload via bidirectional streaming)', () => {
    it('should upload a small BLOB (single chunk) and read it back via bind', async () => {
      const lobTableName = `${tableName}_lob_small`;
      await client.executeUpdate(`CREATE TABLE ${lobTableName} (id INT PRIMARY KEY, payload VARBINARY(MAX))`);
      try {
        const content = Buffer.from('small BLOB content', 'utf8');
        const ref = await client.createLob(content, 'BLOB');
        await client.executeUpdate(`INSERT INTO ${lobTableName} (id, payload) VALUES (?, ?)`, [1, ref]);

        const { rows } = await client.executeQuery(`SELECT payload FROM ${lobTableName} WHERE id = ?`, [1]);
        expect((rows[0].payload as Buffer).equals(content)).toBe(true);
      } finally {
        await client.executeUpdate(`DROP TABLE IF EXISTS ${lobTableName}`);
      }
    }, 15_000);

    it('should upload a large BLOB (multiple 64KB chunks) and read it back byte by byte — regression test for the multi-chunk session bug', async () => {
      const lobTableName = `${tableName}_lob_big`;
      await client.executeUpdate(`CREATE TABLE ${lobTableName} (id INT PRIMARY KEY, payload VARBINARY(MAX))`);
      try {
        // 200,003 bytes forces 4 chunks (LOB_CHUNK_SIZE = 65536) — the server only returns
        // a "stateful" session (sessionUUID populated) after the first chunk; subsequent
        // chunks need to reuse it to land on the same already-started connection/Blob.
        const content = Buffer.alloc(200_003);
        for (let i = 0; i < content.length; i++) {
          content[i] = i % 256;
        }
        const ref = await client.createLob(content, 'BLOB');
        await client.executeUpdate(`INSERT INTO ${lobTableName} (id, payload) VALUES (?, ?)`, [1, ref]);

        const { rows } = await client.executeQuery(`SELECT payload FROM ${lobTableName} WHERE id = ?`, [1]);
        const readBack = rows[0].payload as Buffer;
        expect(readBack.length).toBe(content.length);
        expect(readBack.equals(content)).toBe(true);
      } finally {
        await client.executeUpdate(`DROP TABLE IF EXISTS ${lobTableName}`);
      }
    }, 30_000);
  });

  describe('real pagination (executeQueryStream) against SQL Server', () => {
    const pageTableName = `${tableName}_paging`;
    const total = 250;

    beforeAll(async () => {
      await client.executeUpdate(`CREATE TABLE ${pageTableName} (id INT PRIMARY KEY, label NVARCHAR(100))`);
      const valuesSql = Array.from({ length: total }, (_, i) => `(${i}, 'pag-${i}')`).join(', ');
      await client.executeUpdate(`INSERT INTO ${pageTableName} (id, label) VALUES ${valuesSql}`);
    }, 30_000);

    afterAll(async () => {
      await client.executeUpdate(`DROP TABLE IF EXISTS ${pageTableName}`);
    }, 30_000);

    it('should consume a large result set (multiple blocks) on demand, row by row', async () => {
      const streamClient = await newClient();
      try {
        let count = 0;
        for await (const row of streamClient.executeQueryStream(
          `SELECT id, label FROM ${pageTableName} ORDER BY id`,
        )) {
          expect(row.id).toBe(count);
          expect(row.label).toBe(`pag-${count}`);
          count++;
        }
        expect(count).toBe(total);
      } finally {
        await streamClient.close();
      }
    }, 30_000);
  });
});

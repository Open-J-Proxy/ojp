import 'reflect-metadata';
import { randomBytes } from 'crypto';
import { Column, DataSource, Entity, PrimaryGeneratedColumn } from 'typeorm';
import { ojpUrl, readConnectionFixtures } from '../../ojp-node-driver/test/connectionFixtures';
import { createOjpPostgresDriver } from '../src';
import { exerciseProductExample, productEntity } from './productScenario';

const enabled = process.env.OJP_ENABLE_INTEGRATION_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;

const connections = readConnectionFixtures('postgresql_connections.csv', process.env.OJP_TEST_POSTGRES_CSV);
const userTable = `ojp_orm_user_${randomBytes(6).toString('hex')}`;
const productTable = `ojp_orm_product_${randomBytes(6).toString('hex')}`;
const Product = productEntity(productTable);

@Entity({ name: userTable })
class TestUser {
  @PrimaryGeneratedColumn()
    id!: number;

  @Column({ type: 'varchar', length: 100 })
    name!: string;

  @Column({ type: 'varchar', length: 150, unique: true })
    email!: string;
}

// Generous hook/test timeouts: this suite talks to a real ojp-server + PostgreSQL over
// gRPC, and local dev machines running the server, Docker and Jest at the same time can
// be heavily loaded, so a single round trip can occasionally take well over 30s.
const HOOK_TIMEOUT_MS = 120_000;
const TEST_TIMEOUT_MS = 60_000;

describeIfEnabled.each(connections)('TypeORM PostgreSQL: %s', (jdbcUrl, user, fixturePassword) => {
  let dataSource: DataSource;

  beforeAll(async () => {
    dataSource = new DataSource({
      type: 'postgres',
      driver: createOjpPostgresDriver(),
      username: user,
      password: process.env.OJP_TEST_PASSWORD ?? fixturePassword,
      database: new URL(jdbcUrl.slice('jdbc:'.length)).pathname.slice(1),
      extra: { ojpUrl: ojpUrl(process.env.OJP_TEST_SERVER_ENDPOINT ?? 'localhost:1059', jdbcUrl), ojpCallTimeoutMs: HOOK_TIMEOUT_MS },
      entities: [TestUser, Product],
      synchronize: true,
      logging: false,
    });
    await dataSource.initialize();
  }, HOOK_TIMEOUT_MS);

  afterAll(async () => {
    if (dataSource?.isInitialized) {
      try {
        await dataSource.query(`DROP TABLE IF EXISTS "${productTable}"`);
        await dataSource.query(`DROP TABLE IF EXISTS "${userTable}"`);
      } finally {
        await dataSource.destroy();
      }
    }
  }, HOOK_TIMEOUT_MS);

  afterEach(async () => {
    await dataSource.getRepository(TestUser).clear();
    await dataSource.getRepository(Product).clear();
  }, HOOK_TIMEOUT_MS);

  it('runs the product CRUD and rollback example', async () => {
    await exerciseProductExample(dataSource, Product);
  }, TEST_TIMEOUT_MS);

  it('should insert a row and read back the auto-generated id via RETURNING', async () => {
    const repository = dataSource.getRepository(TestUser);

    const saved = await repository.save({ name: 'Ada Lovelace', email: 'ada@example.com' });

    expect(saved.id).toBeGreaterThan(0);

    const found = await repository.findOneByOrFail({ id: saved.id });
    expect(found).toMatchObject({ name: 'Ada Lovelace', email: 'ada@example.com' });
  }, TEST_TIMEOUT_MS);

  it('should update and delete an existing row', async () => {
    const repository = dataSource.getRepository(TestUser);
    const saved = await repository.save({ name: 'Grace Hopper', email: 'grace@example.com' });

    await repository.update({ id: saved.id }, { name: 'Grace B. Hopper' });
    const updated = await repository.findOneByOrFail({ id: saved.id });
    expect(updated.name).toBe('Grace B. Hopper');

    await repository.delete({ id: saved.id });
    const afterDelete = await repository.findOneBy({ id: saved.id });
    expect(afterDelete).toBeNull();
  }, TEST_TIMEOUT_MS);

  it('should commit a successful transaction', async () => {
    await dataSource.transaction(async (manager) => {
      await manager.save(TestUser, { name: 'Alan Turing', email: 'alan@example.com' });
      await manager.save(TestUser, { name: 'Katherine Johnson', email: 'katherine@example.com' });
    });

    const count = await dataSource.getRepository(TestUser).count();
    expect(count).toBe(2);
  }, TEST_TIMEOUT_MS);

  it('uses the requested transaction isolation level', async () => {
    await dataSource.transaction('SERIALIZABLE', async (manager) => {
      const rows = await manager.query('SHOW transaction_isolation');
      expect(rows[0].transaction_isolation).toBe('serializable');
    });
  }, TEST_TIMEOUT_MS);

  it('should roll back every write when the transaction callback throws', async () => {
    await expect(
      dataSource.transaction(async (manager) => {
        await manager.save(TestUser, { name: 'Margaret Hamilton', email: 'margaret@example.com' });
        throw new Error('force rollback');
      }),
    ).rejects.toThrow('force rollback');

    const count = await dataSource.getRepository(TestUser).count();
    expect(count).toBe(0);
  }, TEST_TIMEOUT_MS);

  it('should enforce a unique constraint (schema synchronize applied real DDL)', async () => {
    const repository = dataSource.getRepository(TestUser);
    await repository.save({ name: 'Duplicate 1', email: 'dup@example.com' });

    await expect(repository.save({ name: 'Duplicate 2', email: 'dup@example.com' })).rejects.toThrow();
  }, TEST_TIMEOUT_MS);
});

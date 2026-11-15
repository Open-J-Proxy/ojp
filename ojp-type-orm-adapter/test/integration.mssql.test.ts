/**
 * Real end-to-end integration test: TypeORM (via `@ojp/typeorm-driver`'s SQL Server shim)
 * talking to a real ojp-server, which forwards to a real SQL Server (Always On AG) database.
 *
 * Requires:
 *   - A local ojp-server listening on localhost:1065 (or OJP_TEST_PORT), with the SQL Server
 *     JDBC driver loaded in ojp-libs/.
 *   - The `sql-primary`/`sql-secondary` Always On AG containers running (see ~/dev/arch/pocs),
 *     with `sql-primary`'s port published to the host (defaults to 14330) and a
 *     `ojp_typeorm_driver_test` database already created on the primary.
 *
 * Only runs when OJP_ENABLE_SQLSERVER_AG_TESTS=true, so it doesn't break the default
 * `npm test` in environments without this infrastructure running.
 */
import 'reflect-metadata';
import { Column, DataSource, Entity, PrimaryGeneratedColumn } from 'typeorm';
import { createOjpMssqlDriver } from '../src';

const enabled = process.env.OJP_ENABLE_SQLSERVER_AG_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;

const OJP_PORT = process.env.OJP_TEST_PORT ?? '1065';
const BACKEND_HOST = process.env.OJP_TEST_MSSQL_BACKEND_HOST ?? 'localhost';
const BACKEND_PORT = process.env.OJP_TEST_MSSQL_BACKEND_PORT ?? '14330';
const OJP_DATABASE = process.env.OJP_TEST_MSSQL_DATABASE ?? 'ojp_typeorm_driver_test';
const OJP_USER = process.env.OJP_TEST_SQLSERVER_USER ?? 'sa';
const OJP_PASSWORD = process.env.OJP_TEST_SQLSERVER_PASSWORD ?? 'Teste@AG2019Local!';

const OJP_URL = `jdbc:ojp[localhost:${OJP_PORT}]_sqlserver://${BACKEND_HOST}:${BACKEND_PORT};`
  + `databaseName=${OJP_DATABASE};encrypt=true;trustServerCertificate=true;`;

@Entity({ name: 'ojp_typeorm_driver_test_users' })
class TestUser {
  @PrimaryGeneratedColumn()
    id!: number;

  @Column({ type: 'nvarchar', length: 100 })
    name!: string;

  @Column({ type: 'nvarchar', length: 150, unique: true })
    email!: string;

  @Column({ type: 'decimal', precision: 12, scale: 2, default: 0 })
    balance!: string;

  @Column({ type: 'bigint', default: 0 })
    visits!: string;

  @Column({ type: 'datetimeoffset', nullable: true })
    lastLoginAt!: Date | null;
}

// Generous hook/test timeouts: this suite talks to a real ojp-server + SQL Server AG over
// gRPC, and local dev machines running the server, Docker and Jest at the same time can
// be heavily loaded, so a single round trip can occasionally take well over 30s.
const HOOK_TIMEOUT_MS = 120_000;
const TEST_TIMEOUT_MS = 60_000;

describeIfEnabled('TypeORM SQL Server shim - real integration (ojp-server + SQL Server AG)', () => {
  let dataSource: DataSource;

  beforeAll(async () => {
    dataSource = new DataSource({
      type: 'mssql',
      driver: createOjpMssqlDriver(),
      username: OJP_USER,
      password: OJP_PASSWORD,
      database: OJP_DATABASE,
      extra: { ojpUrl: OJP_URL, ojpCallTimeoutMs: HOOK_TIMEOUT_MS },
      entities: [TestUser],
      synchronize: true,
      logging: false,
    });
    await dataSource.initialize();
  }, HOOK_TIMEOUT_MS);

  afterAll(async () => {
    if (dataSource?.isInitialized) {
      await dataSource.query('DROP TABLE IF EXISTS ojp_typeorm_driver_test_users');
      await dataSource.destroy();
    }
  }, HOOK_TIMEOUT_MS);

  afterEach(async () => {
    await dataSource.getRepository(TestUser).clear();
  }, HOOK_TIMEOUT_MS);

  it('should insert a row and read back the auto-generated id via the OUTPUT-INTO-SELECT batch', async () => {
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

  it('should round-trip typed columns (decimal, bigint, datetimeoffset) via explicit MssqlParameter typing', async () => {
    const repository = dataSource.getRepository(TestUser);
    const lastLoginAt = new Date('2026-01-15T10:30:00.000Z');

    const saved = await repository.save({
      name: 'Typed Params',
      email: 'typed@example.com',
      balance: '1234567.89',
      visits: '5000000000',
      lastLoginAt,
    });

    const found = await repository.findOneByOrFail({ id: saved.id });
    expect(found.balance).toBe('1234567.89');
    expect(found.visits).toBe('5000000000');
    expect(new Date(found.lastLoginAt as unknown as string).getTime()).toBe(lastLoginAt.getTime());
  }, TEST_TIMEOUT_MS);

  it('should paginate with take/skip (OFFSET ... FETCH)', async () => {
    const repository = dataSource.getRepository(TestUser);
    await repository.save([
      { name: 'User 1', email: 'user1@example.com' },
      { name: 'User 2', email: 'user2@example.com' },
      { name: 'User 3', email: 'user3@example.com' },
    ]);

    const page = await repository.find({ order: { id: 'ASC' }, skip: 1, take: 1 });

    expect(page).toHaveLength(1);
    expect(page[0].name).toBe('User 2');
  }, TEST_TIMEOUT_MS);
});

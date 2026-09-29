/**
 * Real end-to-end integration test: TypeORM (via `@ojp/typeorm-driver`'s Postgres shim)
 * talking to a real ojp-server, which forwards to a real PostgreSQL database.
 *
 * Requires:
 *   - ojp-server listening on localhost:1059 (or OJP_TEST_PORT), with the PostgreSQL
 *     driver loaded in ojp-libs/.
 *   - PostgreSQL reachable from *inside* the ojp-server container/process at the host
 *     configured in OJP_TEST_BACKEND_HOST (defaults to the docker bridge IP used by
 *     this repo's local dev containers, since ojp-server and the test Postgres
 *     container are not on the same docker network by default).
 *
 * Only runs when OJP_ENABLE_INTEGRATION_TESTS=true.
 */
import 'reflect-metadata';
import { Column, DataSource, Entity, PrimaryGeneratedColumn } from 'typeorm';
import { createOjpPostgresDriver } from '../src';

const enabled = process.env.OJP_ENABLE_INTEGRATION_TESTS === 'true';
const describeIfEnabled = enabled ? describe : describe.skip;

const OJP_PORT = process.env.OJP_TEST_PORT ?? '1065';
const BACKEND_HOST = process.env.OJP_TEST_BACKEND_HOST ?? 'localhost';
const BACKEND_PORT = process.env.OJP_TEST_BACKEND_PORT ?? '5442';
const OJP_DATABASE = process.env.OJP_TEST_DATABASE ?? 'ojptest';
const OJP_USER = process.env.OJP_TEST_USER ?? 'ojptest';
const OJP_PASSWORD = process.env.OJP_TEST_PASSWORD ?? 'ojptest123';

const OJP_URL = `jdbc:ojp[localhost:${OJP_PORT}]_postgresql://${BACKEND_HOST}:${BACKEND_PORT}/${OJP_DATABASE}`;

@Entity({ name: 'ojp_typeorm_driver_test_users' })
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

describeIfEnabled('TypeORM Postgres shim - real integration (ojp-server + PostgreSQL)', () => {
  let dataSource: DataSource;

  beforeAll(async () => {
    dataSource = new DataSource({
      type: 'postgres',
      driver: createOjpPostgresDriver(),
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

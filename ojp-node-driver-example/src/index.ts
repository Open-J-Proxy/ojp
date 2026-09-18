/**
 * OJP + TypeORM — beginner-friendly walkthrough.
 *
 * WHAT THIS SHOWS
 * ----------------
 * How to point a normal TypeORM `DataSource` at OJP instead of a direct database
 * connection, and then use TypeORM exactly as you always would (entities,
 * repositories, transactions). Nothing below is OJP-specific except the `driver` and
 * `extra.ojpUrl` options passed to `new DataSource(...)`.
 *
 * HOW THE PIECES FIT TOGETHER
 * ----------------------------
 *   [this script] --TypeORM--> [@ojp/typeorm-driver] --gRPC--> [ojp-server] --JDBC--> [real DB]
 *
 * Your Node.js process never opens a socket straight to Postgres/SQL Server: every
 * query is shipped over gRPC to `ojp-server`, which owns the real connection pool
 * (HikariCP) and talks to the database on your app's behalf. That's what lets many app
 * instances share a small, controlled number of real DB connections.
 *
 * BEFORE YOU RUN THIS
 * --------------------
 * 1. Have an `ojp-server` running and reachable (see ../../ojp-server/README.md).
 * 2. Have a database it can reach — PostgreSQL or SQL Server (see
 *    documents/environment-setup/run-local-databases.md for ready-to-use Docker
 *    commands).
 * 3. `cp .env.example .env` and adjust the values for your setup.
 * 4. `npm install && npm start`.
 *
 * The example is destructive on its own table only: it creates `ojp_example_products`
 * (via TypeORM's `synchronize`) and cleans it up again at the end. It does not touch
 * any other table in your database.
 */
import 'reflect-metadata';
import * as dotenv from 'dotenv';
import { DataSource } from 'typeorm';
import { createOjpPostgresDriver, createOjpMssqlDriver } from '@ojp/typeorm-driver';
import { Product } from './entity/Product';

dotenv.config();

const OJP_HOST = process.env.OJP_HOST ?? 'localhost';
const OJP_PORT = process.env.OJP_PORT ?? '1059';
const DB_TYPE = (process.env.DB_TYPE ?? 'postgres').toLowerCase();
const DB_HOST = process.env.DB_HOST ?? 'localhost';
const DB_USER = process.env.DB_USER ?? 'testuser';
const DB_PASSWORD = process.env.DB_PASSWORD ?? 'testpassword';
const DB_NAME = process.env.DB_NAME ?? 'defaultdb';

if (DB_TYPE !== 'postgres' && DB_TYPE !== 'mssql') {
  throw new Error(`Unsupported DB_TYPE "${DB_TYPE}". Use "postgres" or "mssql".`);
}

/**
 * Builds the TypeORM `DataSourceOptions` for the selected dialect. The only OJP-specific
 * bits are `driver` (routes every call through @ojp/typeorm-driver) and `extra.ojpUrl`
 * (the OJP connection string: `jdbc:ojp[host:port]_<dialect>://realHost:realPort/...`).
 * Everything else (`entities`, `synchronize`, …) is plain TypeORM configuration.
 */
function buildDataSource(): DataSource {
  if (DB_TYPE === 'postgres') {
    const dbPort = process.env.DB_PORT ?? '5432';
    return new DataSource({
      type: 'postgres',
      driver: createOjpPostgresDriver(),
      username: DB_USER,
      password: DB_PASSWORD,
      database: DB_NAME,
      extra: {
        ojpUrl: `jdbc:ojp[${OJP_HOST}:${OJP_PORT}]_postgresql://${DB_HOST}:${dbPort}/${DB_NAME}`,
      },
      entities: [Product],
      synchronize: true, // Auto-creates ojp_example_products for this demo; avoid in production.
      logging: false,
    });
  }

  const dbPort = process.env.DB_PORT ?? '1433';
  return new DataSource({
    type: 'mssql',
    driver: createOjpMssqlDriver(),
    username: DB_USER,
    password: DB_PASSWORD,
    database: DB_NAME,
    extra: {
      ojpUrl: `jdbc:ojp[${OJP_HOST}:${OJP_PORT}]_sqlserver://${DB_HOST}:${dbPort};`
        + `databaseName=${DB_NAME};encrypt=true;trustServerCertificate=true;`,
    },
    entities: [Product],
    synchronize: true, // Auto-creates ojp_example_products for this demo; avoid in production.
    logging: false,
  });
}

async function main(): Promise<void> {
  const dataSource = buildDataSource();

  console.log(`Connecting via ojp-server (${OJP_HOST}:${OJP_PORT}) -> ${DB_TYPE} (${DB_HOST})...`);
  await dataSource.initialize();
  console.log('Connected. TypeORM synchronized the "ojp_example_products" table.\n');

  const products = dataSource.getRepository(Product);

  try {
    // 1) CREATE — plain repository.save(), same as with a direct DB connection.
    const laptop = await products.save({ name: 'Laptop', price: 1999.90, quantity: 5 });
    const mouse = await products.save({ name: 'Mouse', price: 49.90, quantity: 25 });
    console.log(`Inserted products #${laptop.id} and #${mouse.id}.`);

    // 2) READ — fetch everything back.
    const all = await products.find();
    console.log('\nAll products:');
    console.table(all);

    // 3) UPDATE — change the price of one row.
    await products.update({ id: laptop.id }, { price: 1799.90 });
    const updatedLaptop = await products.findOneByOrFail({ id: laptop.id });
    console.log(`\nUpdated laptop price -> ${updatedLaptop.price}`);

    // 4) TRANSACTION — OJP supports real commit/rollback: this block inserts two rows
    // and then throws on purpose, so both inserts must be rolled back.
    console.log('\nRunning a transaction that intentionally fails, to show rollback...');
    await dataSource.transaction(async (manager) => {
      await manager.save(Product, { name: 'Keyboard', price: 129.90, quantity: 10 });
      await manager.save(Product, { name: 'Monitor', price: 899.90, quantity: 0 });
      throw new Error('Simulated failure — both inserts above must be rolled back.');
    }).catch((err) => console.log(`Transaction rolled back as expected: ${(err as Error).message}`));

    const countAfterRollback = await products.count();
    console.log(`Product count after rollback: ${countAfterRollback} (Keyboard/Monitor were NOT persisted).`);

    // 5) DELETE — remove one row.
    await products.delete({ id: mouse.id });
    const countAfterDelete = await products.count();
    console.log(`\nDeleted the mouse. Remaining products: ${countAfterDelete}.`);
  } finally {
    // Clean up so re-running this example always starts from an empty table.
    await dataSource.query('DELETE FROM ojp_example_products');
    await dataSource.destroy();
    console.log('\nConnection closed.');
  }
}

main().catch((err) => {
  console.error('Example failed:', err);
  process.exitCode = 1;
});

# ojp-node-driver-example

A small, beginner-friendly, runnable example: **TypeORM** talking to **PostgreSQL or
SQL Server through [`@ojp/typeorm-driver`](../ojp-typeorm-driver)**, which in turn goes
through [`ojp-server`](../ojp-server) instead of opening a direct database connection.

If you're evaluating OJP's Node.js support, start here — it's the fastest way to see the
whole round trip working end-to-end.

## How it fits together

```
[this example] --TypeORM--> [@ojp/typeorm-driver] --gRPC--> [ojp-server] --JDBC--> [PostgreSQL / SQL Server]
```

`ojp-server` owns the real connection pool. Your Node.js app never opens a socket
straight to the database — every query is shipped over gRPC instead. That's what lets
many app instances share a small, controlled number of real database connections
without overwhelming it.

## What it does

Running `npm start` will:

1. Connect through OJP to the database you configured.
2. Create a `ojp_example_products` table (via TypeORM's `synchronize`).
3. Insert a couple of rows (**create**).
4. Read them all back (**read**).
5. Update one row's price (**update**).
6. Run a transaction that inserts two more rows and then intentionally throws, proving
   the writes are rolled back (**transaction rollback**).
7. Delete one row (**delete**).
8. Clean the table up and close the connection.

Every step in [`src/index.ts`](src/index.ts) has a comment explaining what it does and
why. There's nothing OJP-specific in the entity ([`src/entity/Product.ts`](src/entity/Product.ts))
or in the repository calls — only the `DataSource`'s `driver` and `extra.ojpUrl` options
are OJP-aware. Everything else is plain TypeORM.

## Prerequisites

- `ojp-server` running locally (default: `localhost:1059`). See
  [`../ojp-server/README.md`](../ojp-server/README.md).
- A database it can reach — PostgreSQL or SQL Server. The quickest way to get one
  locally is Docker; see
  [`documents/environment-setup/run-local-databases.md`](../documents/environment-setup/run-local-databases.md)
  for ready-to-use `docker run` commands (the defaults in `.env.example` match that doc).
- [`@ojp/typeorm-driver`](../ojp-typeorm-driver) (and its dependency
  [`@ojp/node-driver`](../ojp-node-driver)) built locally (`npm run build` in each), since
  this example depends on them via a relative `file:` path — no npm package is published yet.

## Setup

```bash
cp .env.example .env   # adjust DB_TYPE / OJP_HOST / OJP_PORT / DB_* as needed
npm install
npm start
```

## Configuration (`.env`)

| Variable | Default | Description |
|---|---|---|
| `OJP_HOST` / `OJP_PORT` | `localhost` / `1059` | Address of your `ojp-server`. |
| `DB_TYPE` | `postgres` | `postgres` or `mssql` — selects the TypeORM driver shim. |
| `DB_HOST` / `DB_PORT` | `localhost` / `5432` (postgres) or `1433` (mssql) | Real database address. |
| `DB_NAME` | `defaultdb` | Database name. |
| `DB_USER` / `DB_PASSWORD` | `testuser` / `testpassword` | Database credentials. |

`.env` is gitignored — never commit real credentials.

## Switching database dialects

Set `DB_TYPE=mssql` and update the `DB_*` variables (see the commented-out example
block in [`.env.example`](.env.example)) — no code changes needed. The same entity and
the same repository calls run against either dialect.

## Where to go next

This example intentionally only exercises basic CRUD and one transaction. For the full
picture of what the Node.js driver currently supports (and doesn't yet), see the
["Current Implementation Level Assessment"](../ojp-node-driver/README.md#current-implementation-level-assessment)
section in `ojp-node-driver`'s README.

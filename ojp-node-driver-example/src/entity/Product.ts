import { Column, Entity, PrimaryGeneratedColumn } from 'typeorm';

/**
 * A plain TypeORM entity — nothing OJP-specific here. This is the whole point of the
 * example: once the DataSource is wired to OJP (see index.ts), the rest of your TypeORM
 * code (entities, repositories, queries) stays exactly the same as it would with a
 * direct database connection.
 */
@Entity({ name: 'ojp_example_products' })
export class Product {
  @PrimaryGeneratedColumn()
    id!: number;

  @Column({ type: 'varchar', length: 100 })
    name!: string;

  // `float` (maps to `double precision`/`float` on Postgres/SQL Server) keeps this
  // example simple and portable across both dialects. For real money amounts you would
  // normally reach for DECIMAL/NUMERIC instead.
  @Column({ type: 'float' })
    price!: number;

  // `int` keeps this column simple and portable. (Plain `boolean` columns currently need
  // special handling through this early Postgres shim — see the README's "Known
  // limitations" section — so this example sticks to types that are known to work well.)
  @Column({ type: 'int', default: 0 })
    quantity!: number;
}

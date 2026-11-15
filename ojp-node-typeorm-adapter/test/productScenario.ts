import { Column, DataSource, Entity, PrimaryGeneratedColumn } from 'typeorm';

export function productEntity(tableName: string) {
  @Entity({ name: tableName })
  class Product {
    @PrimaryGeneratedColumn()
      id!: number;

    @Column({ type: 'varchar', length: 100 })
      name!: string;

    @Column({ type: 'float' })
      price!: number;

    @Column({ type: 'int', default: 0 })
      quantity!: number;
  }
  return Product;
}

export async function exerciseProductExample(dataSource: DataSource, Product: ReturnType<typeof productEntity>) {
  const products = dataSource.getRepository(Product);
  const laptop = await products.save({ name: 'Laptop', price: 1999.9, quantity: 5 });
  const mouse = await products.save({ name: 'Mouse', price: 49.9, quantity: 25 });
  expect(laptop.id).toBeGreaterThan(0);
  expect(await products.count()).toBe(2);
  await products.update({ id: laptop.id }, { price: 1799.9 });
  expect((await products.findOneByOrFail({ id: laptop.id })).price).toBeCloseTo(1799.9);
  await expect(dataSource.transaction(async (manager) => {
    await manager.save(Product, { name: 'Keyboard', price: 129.9, quantity: 10 });
    await manager.save(Product, { name: 'Monitor', price: 899.9, quantity: 0 });
    throw new Error('force rollback');
  })).rejects.toThrow('force rollback');
  expect(await products.count()).toBe(2);
  expect(await products.findOneBy({ name: 'Keyboard' })).toBeNull();
  await products.delete({ id: mouse.id });
  expect(await products.count()).toBe(1);
}

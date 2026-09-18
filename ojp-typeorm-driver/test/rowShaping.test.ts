import { stringifyBigInts } from '../src/common/rowShaping';

describe('stringifyBigInts', () => {
  it('should convert bigint column values to their decimal string form', () => {
    const rows = [{ id: 1, visits: 5000000000n }];
    expect(stringifyBigInts(rows)).toEqual([{ id: 1, visits: '5000000000' }]);
  });

  it('should leave rows without any bigint value untouched (same reference)', () => {
    const row = { id: 1, name: 'Ada' };
    const rows = [row];
    expect(stringifyBigInts(rows)).toEqual([row]);
    expect(stringifyBigInts(rows)[0]).toBe(row);
  });

  it('should convert multiple bigint columns in the same row', () => {
    const rows = [{ a: 1n, b: 2n, c: 'x' }];
    expect(stringifyBigInts(rows)).toEqual([{ a: '1', b: '2', c: 'x' }]);
  });

  it('should handle multiple rows independently', () => {
    const rows = [{ id: 1n }, { id: 2 }, { id: 3n }];
    expect(stringifyBigInts(rows)).toEqual([{ id: '1' }, { id: 2 }, { id: '3' }]);
  });

  it('should return an empty array unchanged', () => {
    expect(stringifyBigInts([])).toEqual([]);
  });
});

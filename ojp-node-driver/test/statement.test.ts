import { OjpClient, OjpPreparedStatement, OjpStatement } from '../src';
import { OjpNullParam, toParameterProtos } from '../src/client/valueMapper';

describe('L2 statement API', () => {
  const url = 'jdbc:ojp[localhost:1059]_h2:mem:test';

  it('creates statement wrappers that reuse the owning client without exposing session state', async () => {
    const client = new OjpClient(url);
    const query = jest.spyOn(client, 'executeQuery').mockResolvedValue({
      columns: ['value'],
      rows: [{ value: 1 }],
      metadata: { columnCount: 1, columns: [{ index: 1, label: 'value' }] },
    });

    const update = jest.spyOn(client, 'executeStatementUpdate').mockResolvedValue({ affectedRows: 1 });

    const statement = client.createStatement();
    expect(statement).toBeInstanceOf(OjpStatement);
    await statement.executeQuery('SELECT 1');
    await statement.executeUpdate('UPDATE t SET value = ?', [1]);

    expect(query).toHaveBeenCalledWith('SELECT 1', []);
    expect(update).toHaveBeenCalledWith('UPDATE t SET value = ?', [1], {});
  });

  it('supports plain statement batches without exposing server handles', async () => {
    const client = new OjpClient(url);
    const add = jest.spyOn(client, 'addStatementBatch').mockResolvedValue('statement-id');
    const execute = jest.spyOn(client, 'executeStatementBatch').mockResolvedValue([1, 1]);
    const statement = client.createStatement();

    await statement.addBatch('INSERT INTO t(id) VALUES (1)');
    await statement.addBatch('INSERT INTO t(id) VALUES (2)');
    await expect(statement.executeBatch()).resolves.toEqual([1, 1]);

    expect(add).toHaveBeenNthCalledWith(1, 'INSERT INTO t(id) VALUES (1)', undefined);
    expect(add).toHaveBeenNthCalledWith(2, 'INSERT INTO t(id) VALUES (2)', 'statement-id');
    expect(execute).toHaveBeenCalledWith('statement-id');
  });

  it('keeps 1-based bindings, supports rebinding, and rejects an unset parameter slot', async () => {
    const client = new OjpClient(url);
    const update = jest.spyOn(client, 'executePreparedUpdate').mockResolvedValue({ affectedRows: 2 });
    const statement = client.prepareStatement('UPDATE t SET value = ? WHERE id = ?');

    statement.setParameter(2, 9);
    await expect(statement.executeUpdate()).rejects.toThrow('parameter 1 has not been set');
    statement.setParameter(1, 'first');
    expect(await statement.executeUpdate()).toBe(2);
    expect(update).toHaveBeenCalledWith(
      'UPDATE t SET value = ? WHERE id = ?',
      ['first', 9],
      {},
      undefined,
    );

    statement.setParameter(1, 'second');
    await statement.executeUpdate();
    expect(update).toHaveBeenLastCalledWith(
      'UPDATE t SET value = ? WHERE id = ?',
      ['second', 9],
      {},
      undefined,
    );
  });

  it('uses the update-result protocol for SQL Server OUTPUT and labels metadata', async () => {
    const client = new OjpClient(url);
    const query = jest.spyOn(client, 'executeDmlReturningQuery').mockResolvedValue({
      columns: ['id'],
      rows: [{ id: 3 }],
      metadata: { columnCount: 1, columns: [{ index: 1, label: 'id' }] },
    });
    const statement = client.prepareStatement('INSERT INTO t(value) OUTPUT inserted.id VALUES (?)');

    const result = await statement.executeQuery(['row']);

    expect(query).toHaveBeenCalledWith(
      'INSERT INTO t(value) OUTPUT inserted.id VALUES (?)',
      ['row'],
    );
    expect(result.metadata.columnCount).toBe(1);
    expect(result.rows).toEqual([{ id: 3 }]);
  });

  it('returns generated-key rows through the prepared statement API', async () => {
    const client = new OjpClient(url);
    const update = jest.spyOn(client, 'executePreparedUpdate')
      .mockResolvedValueOnce({ affectedRows: 1, statementUUID: 'prepared-id' })
      .mockResolvedValueOnce({ affectedRows: 1 });
    const keys = { columns: ['id'], rows: [{ id: 7 }], metadata: { columnCount: 1, columns: [] } };
    const getKeys = jest.spyOn(client, 'getGeneratedKeys').mockResolvedValue(keys);
    const statement = client.prepareStatement('INSERT INTO t(value) VALUES (?)', { generatedKeys: true });

    await statement.executeUpdate(['value']);
    await statement.executeUpdate(['second value']);
    await expect(statement.getGeneratedKeys()).resolves.toBe(keys);
    expect(getKeys).toHaveBeenCalledWith('prepared-id');
    expect(update).toHaveBeenLastCalledWith(
      'INSERT INTO t(value) VALUES (?)',
      ['second value'],
      { generatedKeys: true },
      'prepared-id',
    );
  });

  it('adds bound values to a server-side batch and returns its update counts', async () => {
    const client = new OjpClient(url);
    const add = jest.spyOn(client, 'addPreparedBatch').mockResolvedValue('prepared-id');
    const execute = jest.spyOn(client, 'executePreparedBatch').mockResolvedValue([1, 1]);
    const statement = client.prepareStatement('INSERT INTO t(id) VALUES (?)');

    await statement.addBatch([1]);
    await statement.addBatch([2]);
    await expect(statement.executeBatch()).resolves.toEqual([1, 1]);
    expect(add).toHaveBeenNthCalledWith(1, 'INSERT INTO t(id) VALUES (?)', [1], undefined, {});
    expect(add).toHaveBeenNthCalledWith(2, 'INSERT INTO t(id) VALUES (?)', [2], 'prepared-id', {});
    expect(execute).toHaveBeenCalledWith('prepared-id');
  });

  it('encodes SQL NULL using its explicit java.sql.Types code', () => {
    const parameter = new OjpNullParam(12);
    expect(toParameterProtos([parameter])).toEqual([
      { index: 1, type: 'PT_NULL', values: [{ intValue: 12 }] },
    ]);
  });
});

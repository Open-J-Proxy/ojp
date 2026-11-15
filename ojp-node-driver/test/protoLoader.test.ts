import { loadStatementServiceProto } from '../src/proto/loader';

describe('statement service proto loader', () => {
  it('loads the Google date and time types used by the contract', () => {
    const definition = loadStatementServiceProto();

    expect(definition['google.type.Date']).toMatchObject({
      type: {
        field: [
          { name: 'year', number: 1, type: 'TYPE_INT32' },
          { name: 'month', number: 2, type: 'TYPE_INT32' },
          { name: 'day', number: 3, type: 'TYPE_INT32' },
        ],
      },
    });
    expect(definition['google.type.TimeOfDay']).toMatchObject({
      type: {
        field: [
          { name: 'hours', number: 1, type: 'TYPE_INT32' },
          { name: 'minutes', number: 2, type: 'TYPE_INT32' },
          { name: 'seconds', number: 3, type: 'TYPE_INT32' },
          { name: 'nanos', number: 4, type: 'TYPE_INT32' },
        ],
      },
    });
  });
});

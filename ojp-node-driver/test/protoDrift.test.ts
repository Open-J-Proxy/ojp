import * as protobuf from 'protobufjs';

const { extractTsTypes, findTypeErrors } = require('../scripts/verify-proto-types');

describe('protobuf drift check', () => {
  const message = new protobuf.Type('Example').add(new protobuf.Field('current_field', 1, 'string'));
  const enumType = new protobuf.Enum('Choice', { CURRENT: 0, OTHER: 1 });
  const reachable = new Map<string, protobuf.Type | protobuf.Enum>([
    ['.com.openjproxy.grpc.Example', message],
    ['.com.openjproxy.grpc.Choice', enumType],
  ]);

  it('detects removed fields and added or removed enum members', () => {
    const types = extractTsTypes('types.ts', `
      interface Example { currentField: string; removedField: string }
      type Choice = 'CURRENT' | 'REMOVED'
    `);
    expect(findTypeErrors(reachable, types)).toEqual(expect.arrayContaining([
      expect.stringContaining('Extra field "removedField"'),
      expect.stringContaining('Missing enum value "OTHER"'),
      expect.stringContaining('Extra enum value "REMOVED"'),
    ]));
  });

  it('accepts exact field and enum shapes', () => {
    const types = extractTsTypes('types.ts', `
      interface Example { currentField: string }
      type Choice = 'CURRENT' | 'OTHER'
    `);
    expect(findTypeErrors(reachable, types)).toEqual([]);
  });
});

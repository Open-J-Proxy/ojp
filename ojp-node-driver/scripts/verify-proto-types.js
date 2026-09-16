#!/usr/bin/env node
/**
 * Guards src/proto/types.ts against silent drift from StatementService.proto.
 *
 * This is NOT code generation: types.ts stays hand-written on purpose (BigInt-as-string
 * for int64, oneof members modeled as plain optional properties, a custom TimestampWithZone
 * shape, etc. — all more ergonomic than what a generic generator would produce). This script
 * only detects drift: it fails if the .proto gains/renames/removes a message, enum, or field
 * that is reachable from an RPC this driver actually implements, without a matching update in
 * types.ts.
 *
 * Scope: only messages/enums reachable (transitively) from IMPLEMENTED_RPCS are checked.
 * The `callResource` RPC family (TargetCall/CallResourceRequest/CallResourceResponse/
 * ResourceType/CallType) and DbName (server-internal only, never sent over the wire) are
 * intentionally out of scope — this driver's MVP does not implement callResource.
 */
const fs = require('fs');
const path = require('path');
const protobuf = require('protobufjs');
const ts = require('typescript');

const PROTO_PATH = require.resolve('@ojp/grpc-contract/proto/StatementService.proto');
const GOOGLE_PROTO_INCLUDE_DIR = path.dirname(require.resolve('google-proto-files/package.json'));
const TYPES_PATH = path.join(__dirname, '../src/proto/types.ts');
const OJP_PACKAGE_PREFIX = '.com.openjproxy.grpc.';

// RPC methods actually implemented by this driver (see src/proto/loader.ts and src/client/).
// Keep this list in sync by hand when a new RPC is implemented — the whole point of this
// script is to guard types.ts's contents, not to auto-discover the driver's own scope.
const IMPLEMENTED_RPCS = [
  'connect', 'executeUpdate', 'executeQuery', 'fetchNextRows',
  'createLob', 'readLob', 'terminateSession',
  'startTransaction', 'commitTransaction', 'rollbackTransaction',
  'xaStart', 'xaEnd', 'xaPrepare', 'xaCommit', 'xaRollback',
  'xaRecover', 'xaForget', 'xaSetTransactionTimeout', 'xaGetTransactionTimeout', 'xaIsSameRM',
];

// Proto message/enum name -> TS type name, listed only where they intentionally differ.
const TYPE_NAME_OVERRIDES = {
  ParameterTypeProto: 'ParameterType',
  LobType: 'LobTypeProto',
};

function toCamelCase(protoFieldName) {
  return protoFieldName.replace(/_([a-zA-Z0-9])/g, (_, c) => c.toUpperCase());
}

function loadProtoRoot() {
  const root = new protobuf.Root();
  // protobufjs has no built-in multi-directory include-path resolution; replicate the same
  // strategy @grpc/proto-loader uses (see src/proto/loader.ts's includeDirs) by hand.
  root.resolvePath = (origin, target) => {
    if (fs.existsSync(target)) {
      return target;
    }
    const relativeToOrigin = path.resolve(path.dirname(origin || PROTO_PATH), target);
    if (fs.existsSync(relativeToOrigin)) {
      return relativeToOrigin;
    }
    const viaGoogleProtoFiles = path.join(GOOGLE_PROTO_INCLUDE_DIR, target);
    if (fs.existsSync(viaGoogleProtoFiles)) {
      return viaGoogleProtoFiles;
    }
    // Fall through to protobufjs's own bundled well-known types (google/protobuf/*.proto).
    return target;
  };
  root.loadSync(PROTO_PATH, { keepCase: true });
  root.resolveAll();
  return root;
}

/** Walks the request/response types of every IMPLEMENTED_RPCS method, following own-package
 *  message fields transitively. Returns a Map<fullName, protobuf.Type|protobuf.Enum>. */
function collectReachableTypes(root) {
  const service = root.lookupService('com.openjproxy.grpc.StatementService');
  const reachable = new Map();
  const queue = [];

  for (const rpcName of IMPLEMENTED_RPCS) {
    const method = service.methods[rpcName];
    if (!method) {
      throw new Error(`RPC "${rpcName}" (listed in IMPLEMENTED_RPCS) not found in the .proto service definition.`);
    }
    queue.push(method.resolvedRequestType, method.resolvedResponseType);
  }

  while (queue.length > 0) {
    const type = queue.shift();
    if (!type || reachable.has(type.fullName)) {
      continue;
    }
    reachable.set(type.fullName, type);
    if (type.fieldsArray) {
      for (const field of type.fieldsArray) {
        field.resolve();
        if (field.resolvedType && field.resolvedType.fullName.startsWith(OJP_PACKAGE_PREFIX)) {
          queue.push(field.resolvedType);
        }
      }
    }
  }
  return reachable;
}

/** Parses types.ts and returns Map<exportedTypeName, Set<propertyName> | null>.
 *  `null` marks a type alias (e.g. a string-literal union modeling a proto enum) — presence
 *  is checked, but it has no properties to cross-check field-by-field. */
function extractTsTypes(filePath) {
  const sourceText = fs.readFileSync(filePath, 'utf8');
  const sourceFile = ts.createSourceFile(filePath, sourceText, ts.ScriptTarget.Latest, true);
  const types = new Map();

  sourceFile.forEachChild((node) => {
    if (ts.isInterfaceDeclaration(node)) {
      const props = new Set();
      for (const member of node.members) {
        if (ts.isPropertySignature(member) && member.name) {
          props.add(member.name.getText(sourceFile));
        }
      }
      types.set(node.name.text, props);
    } else if (ts.isTypeAliasDeclaration(node)) {
      types.set(node.name.text, null);
    }
  });

  return types;
}

function main() {
  const root = loadProtoRoot();
  const reachableProtoTypes = collectReachableTypes(root);
  const tsTypes = extractTsTypes(TYPES_PATH);
  const errors = [];

  for (const [fullName, protoType] of reachableProtoTypes) {
    const shortName = fullName.slice(OJP_PACKAGE_PREFIX.length);
    const tsName = TYPE_NAME_OVERRIDES[shortName] || shortName;
    const tsProps = tsTypes.get(tsName);

    if (tsProps === undefined) {
      errors.push(
        `Missing TS type for proto message/enum "${shortName}" `
        + `(expected an interface or type named "${tsName}" in ${path.relative(process.cwd(), TYPES_PATH)}).`,
      );
      continue;
    }

    const isMessage = protoType instanceof protobuf.Type;
    if (isMessage && tsProps !== null) {
      for (const field of protoType.fieldsArray) {
        const camelName = toCamelCase(field.name);
        if (!tsProps.has(camelName)) {
          errors.push(
            `Missing field "${camelName}" (proto field "${field.name}") on TS type "${tsName}" `
            + `for message "${shortName}".`,
          );
        }
      }
    }
  }

  if (errors.length > 0) {
    console.error(`Proto/TypeScript drift detected between StatementService.proto and ${path.relative(process.cwd(), TYPES_PATH)}:\n`);
    for (const error of errors) {
      console.error(` - ${error}`);
    }
    console.error(
      `\n${errors.length} issue(s) found. types.ts is hand-written on purpose (see its header `
      + 'comment) — update it by hand to match the .proto, then re-run this check.',
    );
    process.exitCode = 1;
    return;
  }

  console.log(
    `OK: types.ts matches StatementService.proto `
    + `(${reachableProtoTypes.size} reachable message/enum type(s) checked, `
    + `${IMPLEMENTED_RPCS.length} RPC(s) as entry points).`,
  );
}

main();

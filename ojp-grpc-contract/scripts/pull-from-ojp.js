/**
 * Maintainer-only helper: copies the latest StatementService.proto from a sibling `ojp`
 * monorepo checkout (`../../ojp-grpc-commons/src/main/proto`, i.e. assumes this package's
 * parent folder sits next to `ojp-grpc-commons`) into this package's proto/ folder.
 *
 * This does NOT run automatically as part of install/build/test — the whole point of this
 * package is that consumers pin an explicit, verified version instead of silently picking up
 * whatever the upstream .proto currently looks like. After running this script:
 *   1. Review the diff against the previous proto/*.proto content.
 *   2. Decide the semver bump (MAJOR: breaking wire change, MINOR: additive/compatible
 *      change, PATCH: no semantic change, e.g. comments only).
 *   3. `npm version <major|minor|patch>`
 *   4. `npm run record-hash`
 */
const fs = require('fs');
const path = require('path');

const SOURCE_DIR = path.resolve(__dirname, '../../ojp-grpc-commons/src/main/proto');
const DEST_DIR = path.resolve(__dirname, '../proto');
const FILES = ['StatementService.proto'];

for (const file of FILES) {
  const source = path.join(SOURCE_DIR, file);
  const destination = path.join(DEST_DIR, file);
  if (!fs.existsSync(source)) {
    console.error(
      `Source not found: ${source}\n`
      + 'This script only works with the "ojp" monorepo checked out as a sibling of this '
      + 'package\'s parent folder (e.g. .../ojp/ojp-grpc-contract and .../ojp/ojp-grpc-commons).',
    );
    process.exit(1);
  }
  fs.copyFileSync(source, destination);
  console.log(`Pulled: ${source} -> ${destination}`);
}

console.log(
  '\nNext: review the diff, choose the semver bump, run `npm version <major|minor|patch>`, '
  + 'then `npm run record-hash`.',
);

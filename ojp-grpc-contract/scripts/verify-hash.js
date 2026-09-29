/**
 * Fails if proto/*.proto no longer matches the hash recorded in CONTRACT_HASH.json for the
 * current package.json version.
 *
 * This is the safeguard behind the "explicit version bump, not silent copy" design: any
 * downstream package (e.g. @ojp/node-driver) pins an exact version of @ojp/grpc-contract,
 * so a wire-contract change MUST be accompanied by a deliberate version bump here — it can
 * never drift in silently. Wire this into `npm test`/CI for this package.
 */
const fs = require('fs');
const path = require('path');
const { computeProtoHash } = require('./hash');

const pkg = require('../package.json');
const HASH_FILE = path.resolve(__dirname, '../CONTRACT_HASH.json');

if (!fs.existsSync(HASH_FILE)) {
  console.error('CONTRACT_HASH.json is missing. Run "npm run record-hash" to generate it.');
  process.exit(1);
}

const recorded = JSON.parse(fs.readFileSync(HASH_FILE, 'utf8'));
const actual = computeProtoHash();

if (recorded.version !== pkg.version) {
  console.error(
    `CONTRACT_HASH.json records version ${recorded.version}, but package.json is at `
    + `${pkg.version}. Run "npm run record-hash" after bumping the version.`,
  );
  process.exit(1);
}

if (recorded.sha256 !== actual) {
  console.error(
    'proto/*.proto content no longer matches the hash recorded for this version.\n'
    + 'If this change is intentional: bump the package version (npm version <bump>), then '
    + 'run "npm run record-hash".',
  );
  process.exit(1);
}

console.log(`OK: proto/*.proto matches the recorded contract hash for v${pkg.version}.`);

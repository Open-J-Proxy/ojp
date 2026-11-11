/**
 * Records the current proto/*.proto hash against the current package.json version into
 * CONTRACT_HASH.json.
 *
 * Run this after intentionally changing the contract AND bumping the package version
 * (`npm version major|minor|patch`) — never run it just to "fix" a failing `npm run verify`
 * without a version bump, since that would defeat the whole point of this package (pinning
 * an exact, verifiable contract version).
 */
const fs = require('fs');
const path = require('path');
const { computeProtoHash } = require('./hash');

const pkg = require('../package.json');
const HASH_FILE = path.resolve(__dirname, '../CONTRACT_HASH.json');

const record = { version: pkg.version, sha256: computeProtoHash() };
fs.writeFileSync(HASH_FILE, `${JSON.stringify(record, null, 2)}\n`);
console.log(`Recorded contract hash for v${pkg.version}: ${record.sha256}`);

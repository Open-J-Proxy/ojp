#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./scripts/hash.sh
source "$SCRIPT_DIR/hash.sh"

hash_file="$OJP_CONTRACT_ROOT_DIR/CONTRACT_HASH.json"
if [[ ! -f "$hash_file" ]]; then
  printf 'CONTRACT_HASH.json is missing. Run "npm run record-hash" to generate it.\n' >&2
  exit 1
fi

package_version="$(sed -n 's/^[[:space:]]*"version":[[:space:]]*"\([^"]*\)".*/\1/p' "$OJP_CONTRACT_ROOT_DIR/package.json" | head -n 1)"
recorded_version="$(sed -n 's/^[[:space:]]*"version":[[:space:]]*"\([^"]*\)".*/\1/p' "$hash_file" | head -n 1)"
recorded_hash="$(sed -n 's/^[[:space:]]*"sha256":[[:space:]]*"\([^"]*\)".*/\1/p' "$hash_file" | head -n 1)"
actual_hash="$(compute_proto_hash)"

if [[ "$recorded_version" != "$package_version" ]]; then
  printf 'CONTRACT_HASH.json records version %s, but package.json is at %s. Run "npm run record-hash" after bumping the version.\n' \
    "$recorded_version" "$package_version" >&2
  exit 1
fi

if [[ "$recorded_hash" != "$actual_hash" ]]; then
  printf 'proto/*.proto content no longer matches the hash recorded for this version.\nIf this change is intentional: bump the package version (npm version <bump>), then run "npm run record-hash".\n' >&2
  exit 1
fi

printf 'OK: proto/*.proto matches the recorded contract hash for v%s.\n' "$package_version"

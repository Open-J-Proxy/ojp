#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./scripts/hash.sh
source "$SCRIPT_DIR/hash.sh"

package_version="$(sed -n 's/^[[:space:]]*"version":[[:space:]]*"\([^"]*\)".*/\1/p' "$OJP_CONTRACT_ROOT_DIR/package.json" | head -n 1)"
if [[ -z "$package_version" ]]; then
  printf 'Could not read package version from package.json.\n' >&2
  exit 1
fi

contract_hash="$(compute_proto_hash)"
printf '{\n  "version": "%s",\n  "sha256": "%s"\n}\n' "$package_version" "$contract_hash" \
  > "$OJP_CONTRACT_ROOT_DIR/CONTRACT_HASH.json"
printf 'Recorded contract hash for v%s: %s\n' "$package_version" "$contract_hash"

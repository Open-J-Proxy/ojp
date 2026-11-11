#!/usr/bin/env bash

OJP_CONTRACT_SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
OJP_CONTRACT_ROOT_DIR="$(cd -- "$OJP_CONTRACT_SCRIPT_DIR/.." && pwd)"
OJP_CONTRACT_PROTO_DIR="$OJP_CONTRACT_ROOT_DIR/proto"

compute_proto_hash() {
  local payload_file proto_file digest
  payload_file="$(mktemp)"

  while IFS= read -r -d '' proto_file; do
    printf '%s' "${proto_file##*/}" >> "$payload_file"
    cat "$proto_file" >> "$payload_file"
  done < <(find "$OJP_CONTRACT_PROTO_DIR" -maxdepth 1 -type f -name '*.proto' -print0 | LC_ALL=C sort -z)

  if ! digest="$(sha256sum "$payload_file")"; then
    rm -f "$payload_file"
    return 1
  fi
  rm -f "$payload_file"
  printf '%s\n' "${digest%% *}"
}

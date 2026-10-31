#!/usr/bin/env bash
set -euo pipefail

mode="${1:-generate}"
if [[ "$mode" != "generate" && "$mode" != "--check" ]]; then
    echo "Usage: $0 [generate|--check]" >&2
    exit 2
fi

module_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$module_dir/.." && pwd)"
proto_dir="$repo_root/ojp-grpc-commons/src/main/proto"
maven_target="$repo_root/ojp-grpc-commons/target"
generated_dir="$module_dir/gen"
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT

mvn -q -f "$repo_root/pom.xml" -pl ojp-grpc-commons generate-sources -DskipTests

protoc="$(find "$maven_target/protoc-plugins" -maxdepth 1 -type f -name 'protoc-*.exe' ! -name 'protoc-gen-*' -print -quit)"
if [[ -z "$protoc" ]]; then
    echo "Maven did not provide the expected protoc binary under $maven_target/protoc-plugins" >&2
    exit 1
fi

date_proto="$(find "$maven_target/protoc-dependencies" -path '*/google/type/date.proto' -print -quit)"
timeofday_proto="$(find "$maven_target/protoc-dependencies" -path '*/google/type/timeofday.proto' -print -quit)"
if [[ -z "$date_proto" || -z "$timeofday_proto" ]]; then
    echo "Maven did not provide the google/type proto dependencies" >&2
    exit 1
fi

proto_includes=("-I$proto_dir")
for dependency_dir in "$maven_target"/protoc-dependencies/*; do
    if [[ -d "$dependency_dir" ]]; then
        proto_includes+=("-I$dependency_dir")
    fi
done

output_dir="$module_dir"
if [[ "$mode" == "--check" ]]; then
    output_dir="$temporary_dir/output"
fi
mkdir -p "$output_dir/gen"

"$protoc" \
    "${proto_includes[@]}" \
    --php_out="$output_dir/gen" \
    "$proto_dir/StatementService.proto" \
    "$date_proto" \
    "$timeofday_proto"

if [[ "$mode" == "--check" ]]; then
    diff -ru "$generated_dir" "$output_dir/gen"
fi

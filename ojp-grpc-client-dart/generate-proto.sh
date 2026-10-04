#!/usr/bin/env bash
set -euo pipefail

module_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$module_dir/.." && pwd)"
proto_dir="$repo_root/ojp-grpc-commons/src/main/proto"
maven_target="$repo_root/ojp-grpc-commons/target"
output_dir="$module_dir/lib/src/generated"

if ! command -v protoc >/dev/null 2>&1; then
  echo "protoc is required to generate Dart protocol bindings" >&2
  exit 1
fi
if ! command -v protoc-gen-dart >/dev/null 2>&1; then
  echo "Install the Dart protoc plugin with: dart pub global activate protoc_plugin" >&2
  exit 1
fi

mvn -q -f "$repo_root/pom.xml" -pl ojp-grpc-commons generate-sources -DskipTests

proto_includes=("-I$proto_dir")
for dependency_dir in "$maven_target"/protoc-dependencies/*; do
  if [[ -d "$dependency_dir" ]]; then
    proto_includes+=("-I$dependency_dir")
  fi
done

mkdir -p "$output_dir"
protoc \
  "${proto_includes[@]}" \
  --plugin="protoc-gen-dart=$(command -v protoc-gen-dart)" \
  --dart_out="grpc:$output_dir" \
  "$proto_dir/StatementService.proto" \
  "$proto_dir/echo.proto" \
  "$proto_dir/containers.proto"

#!/usr/bin/env bash
set -euo pipefail

module_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$module_dir/.." && pwd)"
proto_dir="$repo_root/ojp-grpc-commons/src/main/proto"
maven_target="$repo_root/ojp-grpc-commons/target"
output_dir="$module_dir/lib/src/generated"

if ! command -v protoc-gen-dart >/dev/null 2>&1; then
  echo "Install the Dart protoc plugin with: dart pub global activate protoc_plugin" >&2
  exit 1
fi

mvn -q -f "$repo_root/pom.xml" -pl ojp-grpc-commons generate-sources -DskipTests
protoc="$(find "$maven_target/protoc-plugins" -maxdepth 1 -type f -name 'protoc-*.exe' ! -name 'protoc-gen-*' -print -quit)"
if [[ -z "$protoc" ]]; then
  echo "Maven did not provide the expected protoc binary under $maven_target/protoc-plugins" >&2
  exit 1
fi

proto_includes=("-I$proto_dir")
for dependency_dir in "$maven_target"/protoc-dependencies/*; do
  if [[ -d "$dependency_dir" ]]; then
    proto_includes+=("-I$dependency_dir")
  fi
done

google_types=()
for type_proto in date.proto timeofday.proto; do
  source="$(find "$maven_target/protoc-dependencies" -path "*/google/type/$type_proto" -print -quit)"
  if [[ -z "$source" ]]; then
    echo "Maven did not provide google/type/$type_proto" >&2
    exit 1
  fi
  google_types+=("$source")
done

mkdir -p "$output_dir"
"$protoc" \
  "${proto_includes[@]}" \
  --plugin="protoc-gen-dart=$(command -v protoc-gen-dart)" \
  --dart_out="grpc:$output_dir" \
  "$proto_dir/StatementService.proto" \
  "$proto_dir/echo.proto" \
  "$proto_dir/containers.proto" \
  "${google_types[@]}"

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
target_dir="$repo_root/ojp-grpc-commons/target"
output_dir="$module_dir/lib"
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT

mvn -q -f "$repo_root/pom.xml" -pl ojp-grpc-commons generate-sources -DskipTests

includes=(-I"$proto_dir")
for dependency_dir in "$target_dir"/protoc-dependencies/*; do
  if [[ -d "$dependency_dir" ]]; then
    includes+=(-I"$dependency_dir")
  fi
done

if [[ "$mode" == "--check" ]]; then
  output_dir="$temporary_dir/generated"
fi
mkdir -p "$output_dir"
compiler="$(bundle exec ruby -e 'print Gem.bin_path("grpc-tools", "grpc_tools_ruby_protoc")')"
"$compiler" "${includes[@]}" \
  --ruby_out="$output_dir" \
  --grpc_out="$output_dir" \
  "$proto_dir/StatementService.proto" \
  "$proto_dir/echo.proto"

if [[ "$mode" == "--check" ]]; then
  for generated_file in StatementService_pb.rb StatementService_services_pb.rb echo_pb.rb echo_services_pb.rb; do
    diff -u "$module_dir/lib/$generated_file" "$output_dir/$generated_file"
  done
fi

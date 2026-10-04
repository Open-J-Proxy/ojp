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
generated_dir="$module_dir/internal/gen/go"
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT

mvn -q -f "$repo_root/pom.xml" -pl ojp-grpc-commons generate-sources -DskipTests

protoc="$(find "$maven_target/protoc-plugins" -maxdepth 1 -type f -name 'protoc-*.exe' ! -name 'protoc-gen-*' -print -quit)"
if [[ -z "$protoc" ]]; then
  echo "Maven did not provide the expected protoc binary under $maven_target/protoc-plugins" >&2
  exit 1
fi

export GOBIN="$temporary_dir/bin"
mkdir -p "$GOBIN"
(
  cd "$module_dir"
  go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.36.12
  go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.6.1
)

output_root="$module_dir"
if [[ "$mode" == "--check" ]]; then
  output_root="$temporary_dir/output"
fi
mkdir -p "$output_root"

proto_includes=("-I$proto_dir")
for dependency_dir in "$maven_target"/protoc-dependencies/*; do
  if [[ -d "$dependency_dir" ]]; then
    proto_includes+=("-I$dependency_dir")
  fi
done

"$protoc" \
  "${proto_includes[@]}" \
  --plugin="protoc-gen-go=$GOBIN/protoc-gen-go" \
  --plugin="protoc-gen-go-grpc=$GOBIN/protoc-gen-go-grpc" \
  --go_out="$output_root" \
  --go_opt=module=github.com/open-j-proxy/ojp-client-go-database-sql \
  --go_opt=MStatementService.proto=github.com/open-j-proxy/ojp-client-go-database-sql/internal/gen/go/com/openjproxy/grpc \
  --go_opt=Mecho.proto=github.com/open-j-proxy/ojp-client-go-database-sql/internal/gen/go/org/openjproxy/grpc \
  --go_opt=Mcontainers.proto=github.com/open-j-proxy/ojp-client-go-database-sql/internal/gen/go/ojp/transport/v1 \
  --go-grpc_out="$output_root" \
  --go-grpc_opt=module=github.com/open-j-proxy/ojp-client-go-database-sql \
  --go-grpc_opt=MStatementService.proto=github.com/open-j-proxy/ojp-client-go-database-sql/internal/gen/go/com/openjproxy/grpc \
  --go-grpc_opt=Mecho.proto=github.com/open-j-proxy/ojp-client-go-database-sql/internal/gen/go/org/openjproxy/grpc \
  --go-grpc_opt=Mcontainers.proto=github.com/open-j-proxy/ojp-client-go-database-sql/internal/gen/go/ojp/transport/v1 \
  "$proto_dir/StatementService.proto" \
  "$proto_dir/echo.proto" \
  "$proto_dir/containers.proto"

if [[ "$mode" == "--check" ]]; then
  diff -ru "$generated_dir" "$output_root/internal/gen/go"
fi

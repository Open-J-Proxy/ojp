Gem::Specification.new do |spec|
  spec.name = "ojp-client-ruby-dbi"
  spec.version = "0.1.0"
  spec.summary = "Ruby DBI client for Open J Proxy"
  spec.description = "An L1 Ruby DBI client for OJP backed by the StatementService gRPC API."
  spec.authors = ["Open J Proxy contributors"]
  spec.license = "Apache-2.0"
  spec.required_ruby_version = ">= 3.2"
  spec.files = Dir["lib/**/*.rb", "README.md", "generate-proto.sh"]
  spec.require_paths = ["lib"]

  spec.add_dependency "dbi", "~> 0.4"
  spec.add_dependency "google-protobuf", "~> 4.36"
  spec.add_dependency "googleapis-common-protos-types", "~> 1.23"
  spec.add_dependency "grpc", "~> 1.84"

  spec.add_development_dependency "grpc-tools", "~> 1.84"
  spec.add_development_dependency "minitest", "~> 6.0"
end

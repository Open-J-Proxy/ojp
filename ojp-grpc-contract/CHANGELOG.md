# Changelog

All notable changes to the OJP gRPC contract distributed by this package are documented
here. Versions follow the wire-compatibility policy described in `README.md`.

## 1.0.0

Initial extraction of `StatementService.proto` into its own versioned package, replacing
the ad-hoc `sync-proto` filesystem copy previously used by `@ojp/node-driver`. Content is
byte-identical to `ojp-grpc-commons/src/main/proto/StatementService.proto` as of the `ojp`
`main` branch at commit `7b86a25d`.

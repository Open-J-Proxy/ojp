# @ojp/grpc-contract

Versioned source of truth for the **OJP gRPC/Protobuf contract** (`.proto` files) used by
Java and non-Java clients, including [`@ojp/node-driver`](../ojp-node-driver).

> Status: **MVP under development**. API subject to change until stable v1.0.0 of the
> OJP Node.js ecosystem as a whole.

## Why this package exists

The proto definitions live here once. The Java `ojp-grpc-commons` module generates its
stubs from this directory, while other clients generate or load bindings from the same
files. This avoids copying protocol definitions into language-specific client sources.

The package is versioned with SemVer and pinned by a content hash
(`CONTRACT_HASH.json`). A wire-contract change requires a deliberate version bump, and
`npm run verify` detects edits that have not been recorded. The hash scripts use Bash.

## What's included

| File | Description |
|---|---|
| `proto/StatementService.proto` | The statement service contract used by `ojp-server` and all OJP clients. |
| `proto/echo.proto` | The echo/health-check service contract used by OJP clients. |
| `proto/containers.proto` | Shared transport container messages used by OJP clients. |
| `CONTRACT_HASH.json` | `{ version, sha256 }` — the recorded hash of `proto/*.proto` for the current package version. |

## Versioning policy

This package follows SemVer, applied to **wire-contract compatibility**, not just source
code changes:

- **MAJOR** — a breaking change (removed/renamed RPC or message field, changed field type
  or number).
- **MINOR** — an additive, backward-compatible change (new RPC, new optional field).
- **PATCH** — no semantic change to the wire contract (e.g. comments, formatting).

`npm run verify` fails the build if `proto/*.proto` no longer matches the hash recorded for
the current `package.json` version — this makes an un-bumped contract change impossible to
merge unnoticed.

## Usage (consumers)

```json
{
  "dependencies": {
    "@ojp/grpc-contract": "1.0.0"
  }
}
```

```ts
import * as path from 'path';

const protoPath = require.resolve('@ojp/grpc-contract/proto/StatementService.proto');
```

While this package isn't published to a registry yet, consumers in this workspace use a
`file:` reference (`"@ojp/grpc-contract": "file:../ojp-grpc-contract"`). **Note this is
not a true version pin**: npm's `file:` protocol always resolves to whatever is currently
on disk at that path, regardless of the target's own `package.json` version field — unlike
a registry dependency, it will silently pick up local changes on the next `npm install`.
Until this package is published to a registry, the "pinning" is a matter of workflow
discipline (only touch `ojp-grpc-contract/` deliberately, per the maintainer workflow
below) rather than a mechanical guarantee enforced by npm itself.

## Maintainer workflow — updating the contract

Edit the proto files in `proto/` directly. After reviewing a contract change, bump the
package version according to the compatibility policy and record the new hash:

```bash
git diff proto/          # review exactly what changed
npm version <major|minor|patch>   # per the versioning policy above
npm run record-hash      # runs the Bash script to update CONTRACT_HASH.json
npm run verify           # confirms everything is consistent
```

Then run `mvn clean install -pl ojp-grpc-commons` to regenerate the Java stubs, regenerate
affected non-Java bindings, and test the clients before publishing/tagging a release.

## Scripts

```bash
npm run verify          # fails if proto/*.proto drifted from the recorded hash
npm run record-hash     # (re-)records the hash for the current package.json version
```

### Windows

Install [Git for Windows](https://git-scm.com/download/win), which includes Git Bash, and
open Git Bash in the repository. From the contract package directory, run the same npm
commands above; Git Bash provides Bash and `sha256sum` for the scripts. You can also run
them directly with `bash scripts/verify-hash.sh` or `bash scripts/record-hash.sh`.

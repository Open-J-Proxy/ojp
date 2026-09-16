# @ojp/grpc-contract

Versioned distribution of the **OJP gRPC/Protobuf contract** (`.proto` files) for Node.js
clients such as [`@ojp/node-driver`](../ojp-node-driver).

> Status: **MVP under development**. API subject to change until stable v1.0.0 of the
> OJP Node.js ecosystem as a whole.

## Why this package exists

`ojp-server` and `ojp-jdbc-driver` (the reference Java implementation) share the gRPC
contract by depending on the same Maven artifact, `ojp-grpc-commons` — a single compiled
source of truth, no copying involved.

Node.js clients can't consume a Maven artifact. Early on, `@ojp/node-driver` solved this by
running a `sync-proto` script that did a raw filesystem copy of `StatementService.proto`
from a sibling `ojp` checkout. That worked, but had two real problems once the Node.js
drivers became independent repositories (not submodules of `ojp`):

1. **Fragile path assumption** — the copy script assumed `ojp-grpc-commons` sits in a
   specific relative location on disk, which only holds true on a machine that happens to
   have both repos checked out side by side. It breaks for a fresh, standalone clone of
   `ojp-node-driver` (exactly the deployment/CI shape an independent repo needs to support).
2. **Silent drift** — nothing verified the copied `.proto` still matched the upstream
   contract. A change on the `ojp-grpc-commons` side could silently go unnoticed by the
   Node.js driver until a runtime failure.

This package fixes both: the `.proto` file(s) live here, versioned with SemVer and pinned
by a content hash (`CONTRACT_HASH.json`), and consumers depend on an **explicit version**
of this package instead of an ambient filesystem copy. A wire-contract change always
requires a deliberate version bump here — it can never drift in silently.

## What's included

| File | Description |
|---|---|
| `proto/StatementService.proto` | The gRPC service contract used by `ojp-server` and all OJP clients. |
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
pinned `file:` reference (`"@ojp/grpc-contract": "file:../ojp-grpc-contract"`), which npm
resolves the exact same way it would a registry package — only the source differs.

## Maintainer workflow — updating the contract

This package must never be hand-edited directly. To pull in a change made upstream (in the
main `ojp` monorepo's `ojp-grpc-commons`):

```bash
npm run pull-from-ojp   # copies the latest .proto from a sibling ../ojp checkout
git diff proto/          # review exactly what changed
npm version <major|minor|patch>   # per the versioning policy above
npm run record-hash      # updates CONTRACT_HASH.json to match
npm run verify           # confirms everything is consistent
```

Then bump the pinned version in every consumer (e.g. `@ojp/node-driver`'s `package.json`)
and re-test them against the new contract before publishing/tagging a release.

## Scripts

```bash
npm run verify          # fails if proto/*.proto drifted from the recorded hash
npm run record-hash     # (re-)records the hash for the current package.json version
npm run pull-from-ojp   # maintainer-only: refresh proto/ from a sibling ojp checkout
```

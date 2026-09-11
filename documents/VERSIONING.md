# Versioning Policy

This document explains how Open J Proxy versions are assigned, what each segment means,
and how the branching model maps to version numbers.

---

## Version format

Open J Proxy uses **Semantic Versioning** (`MAJOR.MINOR.PATCH`):

| Segment | When it changes |
|---|---|
| `MAJOR` | Breaking, API-incompatible changes |
| `MINOR` | Backwards-compatible new features |
| `PATCH` | Backwards-compatible bug or security fixes |

Examples:

| Version | Meaning |
|---|---|
| `1.0.0` | First production-ready, stable release |
| `1.1.0` | New feature(s) added, fully backwards-compatible |
| `1.0.1` | Bug or security fix on the 1.0.x LTS line |
| `2.0.0` | A yearly LTS release that might contain breaking changes |

---

## Development (SNAPSHOT) versions

Between releases the project version carries a `-SNAPSHOT` suffix:

```
1.0.1-SNAPSHOT   ← work in progress toward 1.0.1
1.1.0-SNAPSHOT   ← work in progress toward 1.1.0
```

Snapshot artifacts are **not stable** and must not be used in production.

---

## Branching model

```
v1.0.0 (tag)
  │
  ├── lts/1.0 ──── 1.0.1 ──── 1.0.2 ──── 1.0.3 ──── ...
  │
  └── main ──────── 1.1.0 ──── 1.2.0 ──── ... ──── 2.0.0
```

| Branch    | Version line | Purpose |
|---|---|---|
| `main`    | Latest minor/major | Active feature development |
| `lts/1.0` | `1.0.x` only | Long-term support maintenance |
| `lts/2.0` | `2.0.x` only | Future LTS line |

---

## Version progression rules

### On `main`

| Event | Example transition |
|---|---|
| Release `X.Y.0` (new minor) | `1.1.0-SNAPSHOT` → release `1.1.0` → next dev `1.2.0-SNAPSHOT` |
| Release `X.Y.Z` (Z > 0, patch) | `1.1.1-SNAPSHOT` → release `1.1.1` → next dev `1.1.2-SNAPSHOT` |
| Explicit major bump | explicit `2.0.0` → release `2.0.0` → next dev `2.1.0-SNAPSHOT` |

After any `.0` release on `main`, development automatically advances to the next MINOR version.
This reflects that `main` is the feature trunk; `lts/*` owns patch maintenance.

### On `lts/X.Y`

| Event | Example transition |
|---|---|
| Any patch release | `1.0.1-SNAPSHOT` → release `1.0.1` → next dev `1.0.2-SNAPSHOT` |

Only `X.Y.z` versions are permitted on `lts/X.Y`. Attempting to release a different line
(e.g. `1.1.0` from `lts/1.0`) causes the release workflow to fail with a clear error.

---

## The 1.0.0 → LTS + 1.1 transition

When `1.0.0` is released from `main`:

1. The workflow tags `v1.0.0` and sets `main` to `1.1.0-SNAPSHOT` (minor bump after a `.0` release).
2. Separately, the maintainer creates branch `lts/1.0` from the `v1.0.0` tag and sets it to `1.0.1-SNAPSHOT`.

Result:

```
tag:    v1.0.0

lts/1.0:  1.0.1-SNAPSHOT   ← receives bug/security fixes only
main:     1.1.0-SNAPSHOT    ← receives new features
```

See [`documents/guides/LTS_BRANCHING.md`](guides/LTS_BRANCHING.md) for the exact commands.

---

## Fix propagation between branches

When a bug affects both an LTS branch and `main`, fix it on the **oldest affected branch first**,
then forward-port (cherry-pick) to newer branches:

```
lts/1.0  →  main
```

If multiple LTS lines exist:

```
lts/1.0  →  lts/2.0  →  main
```

This approach ensures the fix does not accidentally depend on APIs that only exist in newer versions.

---

## When to use a major version bump

`2.0.0` (or higher) will be done on September 20th every year and might contain changes that are **not** backwards-compatible. Examples:

- Removing or renaming a public API method or class
- Changing an SPI contract in a breaking way
- Dropping support for a Java version that current users still use
- Changing the gRPC protocol in a way that makes old drivers incompatible with new servers

`2.0.0` must be explicitly triggered via the `release_version` workflow input. It is never
created automatically.

---

## GitHub branch protection

The following settings should be configured in repository **Settings → Rules / Branch protection rules**:

### `main`

- Require a pull request before merging
- Require at least 1 approval
- Require CI checks to pass (`Main CI`)
- No force pushes
- No branch deletion

### `lts/**`

- Require a pull request before merging
- Require at least 1 approval
- Require CI checks to pass (`Main CI`)
- No force pushes
- No branch deletion

> These rules cannot be automated from the repository itself. They must be configured manually
> in GitHub **Settings → Branches** (classic protection rules) or
> **Settings → Rules → Rulesets** (repository rules).

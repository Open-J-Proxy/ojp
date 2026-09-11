# LTS Branching Guide

This document explains how to create an LTS branch, perform the post-1.0.0 transition,
release a patch on the LTS line, and manage fix propagation.

---

## Overview

Open J Proxy uses one long-lived development trunk (`main`) and one branch per maintained LTS
release line (`lts/X.Y`). Individual releases are immutable tags (`v1.0.0`, `v1.0.1`, …).
There are no separate branches per patch release.

```
v1.0.0 (tag)
  │
  ├── lts/1.0  ──── v1.0.1 ──── v1.0.2 ──── ...
  │
  └── main  ──────── v1.1.0 ──── v1.2.0 ──── ... ──── v2.0.0
```

---

## Step-by-step: releasing 1.0.0 and setting up LTS

> **First-time 1.0.0 release?** See [`RELEASE_1_0_0.md`](RELEASE_1_0_0.md) for the complete
> runbook, including the recommended Release Candidate (RC) strategy that reduces the risk
> of having to ship an emergency patch immediately after GA.

### 1. Release 1.0.0 from `main`

The repository is currently on `1.0.0-SNAPSHOT` on `main`.

Trigger the release workflow:

```
GitHub UI → Actions → "Release to Maven Central & Docker Hub" → Run workflow
Branch: main
Inputs: (leave all defaults)
```

The workflow will:
- Tag `v1.0.0`
- Set `main` to `1.1.0-SNAPSHOT` (minor bump — not `1.0.1-SNAPSHOT`)
- Push both to `main`

After the workflow completes, `main` is at `1.1.0-SNAPSHOT` and tag `v1.0.0` exists.

### 2. Create the `lts/1.0` branch

Run these commands (requires push access to the repository):

```bash
# Fetch the latest state, including the new tag
git fetch --tags origin

# Create lts/1.0 from the exact 1.0.0 release commit
git checkout -b lts/1.0 v1.0.0

# Set the development version on the branch to 1.0.1-SNAPSHOT
mvn --batch-mode versions:set \
    -DnewVersion=1.0.1-SNAPSHOT \
    -DprocessAllModules=true \
    -DgenerateBackupPoms=false

git add -A -- ':!**/target/**'
git commit -m "chore(release): prepare next development iteration 1.0.1-SNAPSHOT"

# Push the new branch
git push origin lts/1.0
```

### 3. Configure branch protection for `lts/1.0`

In **GitHub Settings → Branches** (or Settings → Rules → Rulesets), add a rule for pattern `lts/**`:

- ✅ Require a pull request before merging
- ✅ Require at least 1 approval
- ✅ Require status checks to pass: `Main CI` (or the relevant check name)
- ✅ No force pushes
- ✅ No branch deletion

### 4. Verify the final state

```
tag:    v1.0.0

lts/1.0:  pom version = 1.0.1-SNAPSHOT
main:     pom version = 1.1.0-SNAPSHOT
```

---

## Releasing 1.0.1 (LTS patch release)

1. Merge the bug/security fix PR into `lts/1.0` (via pull request).
2. Trigger the release workflow:

```
GitHub UI → Actions → "Release to Maven Central & Docker Hub" → Run workflow
Branch: lts/1.0
Inputs: (leave all defaults)
```

The workflow will:
- Read `1.0.1-SNAPSHOT` from `lts/1.0`
- Release `1.0.1`
- Tag `v1.0.1`
- Set `lts/1.0` to `1.0.2-SNAPSHOT`
- Push back to `lts/1.0`

---

## Releasing 1.1.0 (main feature release)

1. When `main` is ready for a feature release (currently at `1.1.0-SNAPSHOT`):

```
GitHub UI → Actions → "Release to Maven Central & Docker Hub" → Run workflow
Branch: main
Inputs: (leave all defaults)
```

The workflow will:
- Read `1.1.0-SNAPSHOT` from `main`
- Release `1.1.0`
- Tag `v1.1.0`
- Set `main` to `1.2.0-SNAPSHOT` (minor bump after `.0` release)
- Push back to `main`

---

## Fix propagation: LTS → main

When a bug affects both `lts/1.0` and `main`:

1. **Fix on `lts/1.0` first** (open a PR targeting `lts/1.0`).
2. After the fix is merged, cherry-pick the commit(s) to `main`:

```bash
git fetch origin lts/1.0
git checkout main
git cherry-pick <commit-sha>
git push origin main   # or open a PR
```

This order ensures the fix does not accidentally rely on APIs that only exist in newer versions.

If multiple LTS lines exist in the future:

```
lts/1.0  →  lts/2.0  →  main
```

---

## Adding a future LTS line (e.g. lts/2.0)

The release workflow is parameterised by branch name. No code changes are needed.

```bash
# After releasing 2.0.0 from main:
git fetch --tags origin
git checkout -b lts/2.0 v2.0.0

mvn --batch-mode versions:set \
    -DnewVersion=2.0.1-SNAPSHOT \
    -DprocessAllModules=true \
    -DgenerateBackupPoms=false

git add -A -- ':!**/target/**'
git commit -m "chore(release): prepare next development iteration 2.0.1-SNAPSHOT"
git push origin lts/2.0
```

Then add branch protection for `lts/**` in GitHub Settings (the pattern already covers future branches).

---

## Version validation in the release workflow

The release workflow validates that the released version is compatible with the current branch:

| Branch | Allowed versions | Rejected examples |
|---|---|---|
| `main` | Any valid `X.Y.Z` | None (explicit overrides accepted freely) |
| `lts/1.0` | `1.0.z` only | `1.1.0`, `2.0.0` |
| `lts/2.0` | `2.0.z` only | `2.1.0`, `1.0.5` |

Attempting an invalid release fails the workflow immediately before any version is changed.

---

## Running the version logic tests locally

```bash
bash scripts/test-release-logic.sh
```

All 19 cases must pass before modifying the release workflow.

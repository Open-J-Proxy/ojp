# 1.0.0 Release Runbook

This document is the step-by-step runbook for the **1.0.0 GA release** of Open J Proxy,
scheduled for **September 20, 2026**.

The goal is to make the release as safe as possible by releasing a **Release Candidate (RC)**
first, verifying it end-to-end, and only then promoting to the final GA version.
This avoids having to ship an emergency `1.0.1` patch moments after the first stable release.

---

## Table of Contents

1. [Risk mitigation strategy](#1-risk-mitigation-strategy)
2. [Pre-release checklist](#2-pre-release-checklist)
3. [Phase 1 — Release Candidate (`1.0.0-RC1`)](#3-phase-1--release-candidate-100-rc1)
4. [Phase 2 — RC validation](#4-phase-2--rc-validation)
5. [Phase 3 — GA release (`1.0.0`)](#5-phase-3--ga-release-100)
6. [Phase 4 — Post-release (LTS branch setup)](#6-phase-4--post-release-lts-branch-setup)
7. [Rollback](#7-rollback)

---

## 1. Risk mitigation strategy

The main risk with releasing `1.0.0` directly is that any critical defect discovered
after the fact requires a public `1.0.1` patch — which is disruptive for the "first
stable release" milestone.

The mitigation is a two-phase approach:

```
1.0.0-SNAPSHOT  →  1.0.0-RC1  (release candidate, published to Maven Central)
                    ↓  validation period (≥ 1 week)
                    ↓  no critical defects found?
                →  1.0.0  (GA, published to Maven Central + Docker Hub)
```

**RC versions are real Maven Central publications** — they are installable artifacts,
just not yet stamped as stable. Maven Central accepts `1.0.0-RC1` as a valid version
string.

**If the RC reveals a critical defect:** fix on `main`, release `1.0.0-RC2`, and
repeat the validation phase. Only ship `1.0.0` GA when an RC has passed validation.

---

## 2. Pre-release checklist

Complete all items before releasing the RC.

- [ ] All CI checks pass on the latest commit on `main`
- [ ] `mvn clean compile` succeeds locally with Java 25
- [ ] `mvn checkstyle:check` reports zero violations
- [ ] H2 integration tests pass: `mvn test -DenableH2Tests=true` (in `ojp-jdbc-driver`)
- [ ] JDBC drivers are present in `ojp-server/ojp-libs/` (run `bash ojp-server/download-drivers.sh`)
- [ ] Docker Desktop (or equivalent) is running and you are logged in: `docker login`
- [ ] GitHub Secrets are configured (see [Prerequisites in RELEASE_PROCESS.md](RELEASE_PROCESS.md#prerequisites--one-time-setup))
- [ ] ROADMAP.md, SUPPORT.md, and CHANGELOG (if any) reflect the September 20, 2026 date
- [ ] `main` HEAD is on `1.0.0-SNAPSHOT` (verify: `mvn help:evaluate -Dexpression=project.version -q -DforceStdout`)

---

## 3. Phase 1 — Release Candidate (`1.0.0-RC1`)

The release workflow natively supports pre-release versions: supply `release_version=1.0.0-RC1`
and the workflow will publish that version **without bumping the pom to a next SNAPSHOT** —
the repository remains on `1.0.0-SNAPSHOT` so the GA release can follow immediately after
validation by triggering the workflow again with no override.

### 3a — Automated RC release (preferred)

```
GitHub → Actions → "Release to Maven Central & Docker Hub" → Run workflow
  Branch: main
  Release version: 1.0.0-RC1
  Dry run: unchecked
```

The workflow will:
- Set all poms to `1.0.0-RC1`, build, deploy to Maven Central, build and push the Docker image
- Tag `v1.0.0-RC1`
- Create a GitHub **pre-release** (not marked as latest)
- **Not push `rrobetti/ojp:latest`** — the `latest` tag is reserved for GA
- **Not advance the pom** — `main` stays at `1.0.0-SNAPSHOT`

Proceed to [Phase 2 — RC validation](#4-phase-2--rc-validation).

---

### 3b — Manual RC release (fallback)

Use only if the automated workflow is unavailable.

### 3.1 Set the RC version

```bash
git checkout main
git pull origin main

# Verify starting state
mvn help:evaluate -Dexpression=project.version -q -DforceStdout
# Expected: 1.0.0-SNAPSHOT
```

### 3.2 Build and verify locally

```bash
# Compile + checkstyle
mvn clean compile

# Full build (no tests, confirming all modules link correctly)
mvn clean install -DskipTests -Dgpg.skip=true
```

Fix any compilation or checkstyle failures before continuing.

### 3.3 Deploy to Maven Central

```bash
# Temporarily set version to RC1 for the deploy
mvn --batch-mode versions:set \
    -DnewVersion=1.0.0-RC1 \
    -DprocessAllModules=true \
    -DgenerateBackupPoms=false

# Deploy all modules with the release profile (signs + sources + javadoc)
mvn clean deploy -Prelease -DskipTests
```

Go to <https://central.sonatype.com/publishing/deployments> and **publish** the
staged bundle. Wait for the **Published** status before continuing.

> Note: RC artifacts will be visible on Maven Central. Early adopters can reference them explicitly.

### 3.4 Build and push the Docker image

```bash
VERSION=1.0.0-RC1

# Build the image
docker build -t rrobetti/ojp:${VERSION} ojp-server/

# Push the RC tag (do NOT tag as 'latest' — that is reserved for GA)
docker push rrobetti/ojp:${VERSION}
```

Verify at <https://hub.docker.com/r/rrobetti/ojp/tags>.

### 3.5 Commit the RC version and create a tag

```bash
git add -A -- ':!**/target/**'
git commit -m "chore(release): prepare release 1.0.0-RC1"
git tag -a v1.0.0-RC1 -m "Release 1.0.0-RC1"
```

### 3.6 Reset to SNAPSHOT (do NOT advance to 1.1.0-SNAPSHOT yet)

After publishing the RC, keep `main` at `1.0.0-SNAPSHOT` so the GA release can
be triggered cleanly from the same base.

```bash
mvn --batch-mode versions:set \
    -DnewVersion=1.0.0-SNAPSHOT \
    -DprocessAllModules=true \
    -DgenerateBackupPoms=false

git add -A -- ':!**/target/**'
git commit -m "chore(release): back to 1.0.0-SNAPSHOT after RC1"

git push origin main
git push origin v1.0.0-RC1
```

### 3.7 Create a GitHub pre-release

Go to **GitHub → Releases → Draft a new release**:

- Tag: `v1.0.0-RC1`
- Title: `1.0.0-RC1 (Release Candidate)`
- Check **This is a pre-release**
- Click **Generate release notes** and review the PR list
- Publish

---

## 4. Phase 2 — RC validation

Allow at least **one week** of testing before promoting to GA.

### What to test

- [ ] Deploy `rrobetti/ojp:1.0.0-RC1-RC1` against each supported database (PostgreSQL, MySQL, MariaDB, H2)
- [ ] Run the full `ojp-jdbc-driver` integration test suite against the RC server
- [ ] Verify JDBC driver `1.0.0-RC1` from Maven Central (not from local) connects correctly
- [ ] Verify the Spring Boot starter auto-wires correctly against the RC artifacts
- [ ] Verify Docker image starts and responds to health checks
- [ ] Review any issue reports from early testers

### If a critical defect is found

1. Fix on `main` via normal PR process.
2. Repeat [Phase 1](#3-phase-1--release-candidate-100-rc1) with version `1.0.0-RC2`.
3. Validate `1.0.0-RC2` for another week.
4. Continue until a release candidate completes validation without critical defects.

---

## 5. Phase 3 — GA release (`1.0.0`)

Once the RC has passed validation, proceed with the GA release.

> **Preferred: use the automated workflow.** This is the safest path because the
> workflow handles version-bump, tagging, GitHub Release creation, and LTS transition
> atomically. Use the manual steps below only if the workflow is unavailable.

### 5a — Automated GA release (preferred)

```
GitHub → Actions → "Release to Maven Central & Docker Hub" → Run workflow
  Branch: main
  Inputs: leave all defaults
  Dry run: unchecked
```

The workflow will:
- Release `1.0.0`
- Tag `v1.0.0`
- Advance `main` to `1.1.0-SNAPSHOT`
- Create the GitHub Release with auto-generated notes

Proceed to [Phase 4](#6-phase-4--post-release-lts-branch-setup) after the workflow succeeds.

---

### 5b — Manual GA release (fallback)

Use only if the automated workflow is unavailable.

#### Step 1 — Confirm starting state

```bash
git checkout main && git pull origin main
mvn help:evaluate -Dexpression=project.version -q -DforceStdout
# Must print: 1.0.0-SNAPSHOT
```

#### Step 2 — Set the GA version

```bash
mvn --batch-mode versions:set \
    -DnewVersion=1.0.0 \
    -DprocessAllModules=true \
    -DgenerateBackupPoms=false

mvn help:evaluate -Dexpression=project.version -q -DforceStdout
# Must print: 1.0.0
```

#### Step 3 — Build and verify

```bash
mvn clean compile
```

Fix any failures before continuing. Do not proceed with a broken build.

#### Step 4 — Deploy to Maven Central

```bash
mvn clean deploy -Prelease -DskipTests
```

Go to <https://central.sonatype.com/publishing/deployments> and **publish** the
staged bundle. Wait for **Published** status.

#### Step 5 — Build and push the Docker image

```bash
VERSION=1.0.0

docker build -t rrobetti/ojp:${VERSION} ojp-server/
docker push rrobetti/ojp:${VERSION}

# Tag as latest only after GA confirmation
docker tag rrobetti/ojp:${VERSION} rrobetti/ojp:latest
docker push rrobetti/ojp:latest
```

#### Step 6 — Commit the release version and tag

```bash
git add -A -- ':!**/target/**'
git commit -m "chore(release): prepare release 1.0.0"
git tag -a v1.0.0 -m "Release 1.0.0"
```

#### Step 7 — Advance to next development version

On `main`, after a `X.Y.0` release, the next development version is `X.(Y+1).0-SNAPSHOT`
(not `X.Y.1-SNAPSHOT` — that belongs to the LTS branch).

```bash
mvn --batch-mode versions:set \
    -DnewVersion=1.1.0-SNAPSHOT \
    -DprocessAllModules=true \
    -DgenerateBackupPoms=false

git add -A -- ':!**/target/**'
git commit -m "chore(release): prepare next development iteration 1.1.0-SNAPSHOT"

git push origin main
git push origin v1.0.0
```

#### Step 8 — Create the GitHub Release

Go to **GitHub → Releases → Draft a new release**:

- Tag: `v1.0.0`
- Title: `1.0.0 — General Availability`
- Uncheck **This is a pre-release**
- Click **Generate release notes** and review the PR list
- Publish

---

## 6. Phase 4 — Post-release (LTS branch setup)

After `v1.0.0` is published (automated or manual), create the LTS maintenance branch.

See [LTS_BRANCHING.md — Step 2: Create the lts/1.0 branch](LTS_BRANCHING.md#2-create-the-lts10-branch)
for the exact commands.

Summary:

```bash
git fetch --tags origin
git checkout -b lts/1.0 v1.0.0

mvn --batch-mode versions:set \
    -DnewVersion=1.0.1-SNAPSHOT \
    -DprocessAllModules=true \
    -DgenerateBackupPoms=false

git add -A -- ':!**/target/**'
git commit -m "chore(release): prepare next development iteration 1.0.1-SNAPSHOT"
git push origin lts/1.0
```

Configure branch protection for `lts/**` in GitHub Settings (see [LTS_BRANCHING.md — Step 3](LTS_BRANCHING.md#3-configure-branch-protection-for-lts10)).

Final state:

```
tag:    v1.0.0

lts/1.0:  pom version = 1.0.1-SNAPSHOT  ← bug/security fixes only
main:     pom version = 1.1.0-SNAPSHOT  ← new features
```

---

## 7. Rollback

If a critical defect is found **after** GA publication:

| Artifact | Can it be retracted? |
|---|---|
| Maven Central | **No.** Maven Central does not allow artifact deletion. The artifact stays; release `1.0.1` ASAP. |
| Docker Hub | **Yes.** Delete the `1.0.0` and `latest` tags from the Docker Hub web UI while preparing `1.0.1`. |
| GitHub Release | **Yes.** Convert to pre-release or delete the release from GitHub → Releases. The git tag remains. |

This is exactly why the RC phase exists — to catch critical defects before they reach Maven Central.

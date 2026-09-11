# OJP Release Process

This document describes the automated one-click release process for all OJP modules and
the manual steps still required when the automated workflow cannot be used.

> **Releasing 1.0.0 GA?** Use the dedicated runbook:
> [`RELEASE_1_0_0.md`](RELEASE_1_0_0.md). It covers the recommended Release Candidate
> (RC) strategy, pre-release checklist, manual fallback steps, LTS branch setup, and
> rollback guidance specific to the first GA release.

---

## Table of Contents

1. [Overview](#overview)
2. [Prerequisites & One-Time Setup](#prerequisites--one-time-setup)
   - [Sonatype Central Account](#1-sonatype-central-account)
   - [GPG Signing Key](#2-gpg-signing-key)
   - [Docker Hub Access Token](#3-docker-hub-access-token)
   - [GitHub Secrets](#4-github-secrets)
   - [Branch Protection](#5-branch-protection)
3. [Automated Release (GitHub Workflow)](#automated-release-github-workflow)
   - [How to Trigger](#how-to-trigger)
   - [What the Workflow Does](#what-the-workflow-does)
   - [Version Scheme](#version-scheme)
   - [Dry Run Mode](#dry-run-mode)
4. [Manual Release Process](#manual-release-process)
5. [Maven Central Integration Guide](#maven-central-integration-guide)
   - [Namespace Registration](#namespace-registration)
   - [Credentials](#credentials)
   - [Required Artifacts](#required-artifacts)
   - [The Release Profile](#the-release-profile)
6. [Post-Release Checklist](#post-release-checklist)
7. [Troubleshooting](#troubleshooting)
8. [Suggestions for Future Improvements](#suggestions-for-future-improvements)

---

## Overview

OJP uses a **single-repo multi-module Maven build** containing the following publishable modules:

| Module | Published to | Notes |
|--------|-------------|-------|
| `ojp-parent` (root `pom.xml`) | Maven Central | Parent POM |
| `ojp-grpc-commons` | Maven Central | Shared gRPC types |
| `ojp-jdbc-driver` | Maven Central | Shaded fat JAR |
| `ojp-datasource-api` | Maven Central | SPI abstraction |
| `ojp-datasource-hikari` | Maven Central | HikariCP provider |
| `ojp-datasource-dbcp` | Maven Central | Apache DBCP provider |
| `ojp-xa-pool-commons` | Maven Central | XA pool utilities |
| `ojp-testcontainers` | Maven Central | Test helpers |
| `spring-boot-starter-ojp` | Maven Central | Spring Boot auto-config |
| `ojp-server` | Maven Central + Docker Hub | Executable (shaded) JAR to Maven Central, Docker image to Docker Hub |

The release workflow lives at `.github/workflows/release.yml`.

---

## Prerequisites & One-Time Setup

All of the following need to be configured once, before the first automated release.

### 1. Sonatype Central Account

1. Register at <https://central.sonatype.com/> using the organization's account.
2. **Verify the namespace** `org.openjproxy`:
   - Go to **Publishing** → **Namespaces** → **Add Namespace**.
   - Enter `org.openjproxy`.
   - Sonatype will ask you to add a DNS TXT record or create a GitHub repository
     under the matching organization to prove ownership.
   - For GitHub verification: create (or confirm) the repo
     `https://github.com/openjproxy/OSSRH-XXXXXX` with the token Sonatype provides.
3. Once verified, generate a **User Token** (not your login credentials!):
   - Account menu → **Generate User Token**.
   - Save the **username** and **password** shown — these are the `SONATYPE_USERNAME`
     and `SONATYPE_PASSWORD` secrets.

> **Important:** The token username looks like a UUID (e.g. `a1b2c3d4`),
> not your email address. Always use the token, never your real credentials.

### 2. GPG Signing Key

Maven Central requires all artifacts to be signed with a GPG key.

```bash
# 1. Generate a new RSA-4096 key (or use an existing one)
gpg --full-generate-key
#    Kind: RSA and RSA
#    Size: 4096
#    Expiry: 2y (recommended)
#    Name / Email: OJP Release <release@openjproxy.org>

# 2. List your key to get the KEY_ID (last 8 hex chars of the fingerprint)
gpg --list-secret-keys --keyid-format=long
# Example output: sec   rsa4096/AABBCCDD11223344

# 3. Upload the public key to a key server so Maven Central can verify signatures
gpg --keyserver keyserver.ubuntu.com --send-keys AABBCCDD11223344
gpg --keyserver keys.openpgp.org    --send-keys AABBCCDD11223344

# 4. Export the private key in ASCII-armor format for the GitHub secret
gpg --armor --export-secret-keys AABBCCDD11223344 > ojp-release-key.asc
# The content of ojp-release-key.asc is the value of the GPG_PRIVATE_KEY secret.
# Delete this file after storing the secret.
rm ojp-release-key.asc
```

> **Key security:** The private key never leaves the GitHub Actions runner. The
> exported ASCII armor is stored as an encrypted GitHub secret and imported
> into the runner's ephemeral GPG keyring by `actions/setup-java@v4`.

### 3. Docker Hub Access Token

1. Log in to <https://hub.docker.com/> with the `rrobetti` account.
2. Go to **Account Settings → Security → Access Tokens → New Access Token**.
3. Name it `ojp-release-ci`, grant **Read, Write, Delete** permissions.
4. Copy the generated token — this is the `DOCKERHUB_TOKEN` secret.

### 4. GitHub Secrets

Navigate to the OJP repository → **Settings → Secrets and variables → Actions**
and add the following **Repository secrets**:

| Secret name | Description |
|-------------|-------------|
| `SONATYPE_USERNAME` | Sonatype Central user-token username |
| `SONATYPE_PASSWORD` | Sonatype Central user-token password |
| `GPG_PRIVATE_KEY` | ASCII-armored GPG private key (full `-----BEGIN PGP PRIVATE KEY BLOCK-----` content) |
| `GPG_PASSPHRASE` | Passphrase for the GPG key |
| `DOCKERHUB_USER` | Docker Hub username (`rrobetti`) |
| `DOCKERHUB_TOKEN` | Docker Hub access token |
| `RELEASE_TOKEN` | (Recommended) A Personal Access Token (PAT) with `repo` scope, used to push the version-bump commit back to `main`. If omitted, `GITHUB_TOKEN` is used, which may be blocked by branch-protection rules. |

### 5. Branch Protection

If `main` has branch-protection rules that require pull-request reviews, the
release workflow cannot directly push the version-bump commit to `main` using
`GITHUB_TOKEN`.

**Recommended options (pick one):**

- **Option A — PAT bypass:** Create the `RELEASE_TOKEN` PAT (see above) owned by
  an account that has "bypass branch protection" permissions.
- **Option B — Allow bot pushes:** In branch protection, add
  `github-actions[bot]` to the bypass list.
- **Option C — Release branch:** Modify the workflow to push changes to a
  `release/vX.Y.Z` branch and open a PR automatically (more process, more safety).

---

## Automated Release (GitHub Workflow)

### How to Trigger

1. On GitHub, go to **Actions → Release to Maven Central & Docker Hub**.
2. Click **Run workflow** (top-right of the workflow list).
3. Leave **Dry run** unchecked for a real release, or check it for a test run.
4. Click **Run workflow** to start.

That's it — one click.

### What the Workflow Does

```
checkout → compute versions → set release version in all poms
  → build (no tests) → deploy to Maven Central (-Prelease)
  → build & push Docker image
  → update docs to new release version
  → commit release pom changes → create annotated Git tag on the release commit
  → bump to next SNAPSHOT → commit next-dev version
  → push to main + push tag
  → create GitHub Release with auto-generated release notes
```

Detailed steps in the workflow file: `.github/workflows/release.yml`.

#### Git tagging strategy

The tag (`v<release-version>`) is created on the commit that contains the
release version in the poms *before* the next-development-version bump commit.
This means the tag always points to the exact source that was built and
published — a clean snapshot with no `-SNAPSHOT` suffix anywhere.

#### Auto-generated release notes

When the GitHub Release is created, the workflow calls GitHub's
[Generate release notes API](https://docs.github.com/en/rest/releases/releases#generate-release-notes-content-for-a-release)
to populate the release body automatically from merged pull requests since the
previous release tag. No manual editing of release notes is required.

Pull requests are grouped into labelled sections (New Features, Bug Fixes,
etc.) as defined in `.github/release.yml`. To get a PR into the right section,
apply the appropriate label before merging it. A fixed **Artifacts** footer
(Maven Central and Docker Hub links) is always appended by the workflow.

### Version Scheme

From `1.0.0` onwards, release versions are plain semver with no suffix:

| Branch | Current (SNAPSHOT) | `release_version` input | Released as | Next dev |
|---|---|---|---|---|
| `main` | `1.0.0-SNAPSHOT` | _(none)_ | `1.0.0` | `1.1.0-SNAPSHOT` ¹ |
| `main` | `1.1.0-SNAPSHOT` | _(none)_ | `1.1.0` | `1.2.0-SNAPSHOT` ¹ |
| `main` | `1.1.1-SNAPSHOT` | _(none)_ | `1.1.1` | `1.1.2-SNAPSHOT` |
| `lts/1.0` | `1.0.1-SNAPSHOT` | _(none)_ | `1.0.1` | `1.0.2-SNAPSHOT` |
| `lts/1.0` | `1.0.8-SNAPSHOT` | _(none)_ | `1.0.8` | `1.0.9-SNAPSHOT` |
| `main` | `1.0.0-SNAPSHOT` | `1.0.0-RC1` | `1.0.0-RC1` ² | _(unchanged)_ |
| `main` | `1.0.0-SNAPSHOT` | `1.0.0-RC2` | `1.0.0-RC2` ² | _(unchanged)_ |
| `main` | `1.0.0-SNAPSHOT` | `1.0.0-SNAPSHOT1` | `1.0.0-SNAPSHOT1` ² | _(unchanged)_ |
| `lts/1.0` | `1.0.1-SNAPSHOT` | `1.0.1-RC1` | `1.0.1-RC1` ² | _(unchanged)_ |

> ¹ On `main`, after any `X.Y.0` release the next dev version advances to `X.(Y+1).0-SNAPSHOT`
> because new backwards-compatible features belong in the next minor version.
> The `lts/X.Y` branch owns `X.Y.z` patch maintenance.
>
> ² **Pre-release versions** (`X.Y.Z-<qualifier>` where qualifier starts with a letter, e.g. `RC1`,
> `RC2`, `SNAPSHOT1`, `alpha1`): published as-is without version bump. The pom stays at the
> current SNAPSHOT version. The Docker image is not tagged as `latest`.
> The GitHub Release is created as a pre-release. After validation, trigger the workflow again
> with no override to publish the final GA version.

See [`documents/VERSIONING.md`](../VERSIONING.md) for full version attribution rules
and [`documents/guides/LTS_BRANCHING.md`](guides/LTS_BRANCHING.md) for LTS branch creation.

The version update is performed by Maven Versions Plugin:

```bash
mvn versions:set -DnewVersion=<version> -DprocessAllModules=true -DgenerateBackupPoms=false
```

This updates:
- Every module's `<version>` tag
- Every module's `<parent><version>` tag
- Every inter-module dependency `<version>` tag whose value matched the old version

### Dry Run Mode

Checking **Dry run** will:
- Calculate and print both versions (release + next dev)
- Set the release version in pom files and compile all modules (but not install/deploy)
- **Skip** Maven Central deployment
- **Skip** Docker Hub push
- **Skip** git commits and push

Useful for verifying the version logic without publishing anything.

---

## Manual Release Process

Use this process only when the automated workflow is unavailable.

> **Before starting:**
> - Make sure you are on `main` and it is up to date.
> - Confirm that all CI tests pass on the latest commit.

### Step 1 — Update versions

```bash
# Replace 0.4.1-SNAPSHOT with the desired release version (e.g. 0.4.2-beta)
mvn versions:set -DnewVersion=0.4.2-beta -DprocessAllModules=true -DgenerateBackupPoms=false
```

Verify that all `pom.xml` files (root + modules) now show `0.4.2-beta`.

### Step 2 — Enable release plugins

In `pom.xml` (root) and `ojp-jdbc-driver/pom.xml`:
uncomment the `maven-gpg-plugin` and `central-publishing-maven-plugin` blocks.

> **Note:** When using the automated workflow you do **not** do this step —
> the `release` Maven profile (activated by `-Prelease`) handles it automatically.

### Step 3 — Deploy parent POM

```bash
mvn deploy -N -DskipTests
```

Go to <https://central.sonatype.com/publishing/deployments> and **publish** the
staged parent POM. Wait until it shows **Published** before continuing.

### Step 4 — Install local dependencies

```bash
mvn clean install -pl ojp-grpc-commons -DskipTests
mvn clean install -pl ojp-testcontainers -DskipTests
```

These modules are needed in the local repository before deploying modules that
depend on them.

### Step 5 — Deploy all library modules

```bash
mvn clean deploy -pl ojp-jdbc-driver     -DskipTests
mvn clean deploy -pl ojp-testcontainers  -DskipTests
mvn clean deploy -pl spring-boot-starter-ojp -DskipTests
mvn clean deploy -pl ojp-datasource-api  -DskipTests
mvn clean deploy -pl ojp-datasource-dbcp -DskipTests
mvn clean deploy -pl ojp-datasource-hikari -DskipTests
mvn clean deploy -pl ojp-xa-pool-commons -DskipTests
```

Publish each staged bundle on <https://central.sonatype.com/publishing/deployments>.

### Step 6 — Build and push Docker image

```bash
# Download open-source JDBC drivers first
bash ojp-server/download-drivers.sh ./ojp-server/ojp-libs

# Build locally first to verify
docker build -t rrobetti/ojp:<version> ojp-server/

# Push to Docker Hub
docker login
docker push rrobetti/ojp:<version>
docker tag rrobetti/ojp:<version> rrobetti/ojp:latest
docker push rrobetti/ojp:latest
```

Verify at <https://hub.docker.com/r/rrobetti/ojp>.

### Step 7 — Commit, tag, and push

```bash
git add -A
git commit -m "chore(release): prepare release 0.4.2-beta"
git tag -a v0.4.2-beta -m "Release 0.4.2-beta"
```

### Step 8 — Re-comment the release plugins

Uncomment → comment the `maven-gpg-plugin` and `central-publishing-maven-plugin`
blocks back in both pom files.

### Step 9 — Bump to next development version

```bash
mvn versions:set -DnewVersion=0.4.2-SNAPSHOT -DprocessAllModules=true -DgenerateBackupPoms=false
git add -A
git commit -m "chore(release): prepare next development iteration 0.4.2-SNAPSHOT"
git push origin main
git push origin v0.4.2-beta
```

### Step 10 — Create GitHub Release

Go to **GitHub → Releases → Draft a new release**, select the tag `v0.4.2-beta`,
and click **Generate release notes** to auto-populate the body from merged PRs
since the previous release. Review and publish.

---

## Maven Central Integration Guide

### Namespace Registration

Artifacts are published under the Maven **groupId** `org.openjproxy`.
Sonatype Central requires the namespace to be verified before any artifacts can
be published. Verification is done once per namespace (see [Prerequisites](#1-sonatype-central-account)).

### Credentials

Sonatype Central uses **User Tokens** for authentication, not your login
credentials. Tokens are generated per account on the Sonatype Central portal:

- URL: <https://central.sonatype.com/account>
- Token format: the username looks like a UUID, the password is a long random string.

These are stored as `SONATYPE_USERNAME` and `SONATYPE_PASSWORD` in GitHub Secrets,
and injected into Maven's `settings.xml` by `actions/setup-java@v4`:

```yaml
- uses: actions/setup-java@v4
  with:
    server-id: central           # matches <publishingServerId>central</publishingServerId>
    server-username: SONATYPE_USERNAME
    server-password: SONATYPE_PASSWORD
```

At runtime the plugin reads `SONATYPE_USERNAME` and `SONATYPE_PASSWORD` environment
variables mapped to that server entry.

### Required Artifacts

Maven Central enforces the presence of **four files** per artifact:

| File | Generated by |
|------|-------------|
| `*.jar` | Normal Maven build |
| `*-sources.jar` | `maven-source-plugin` (in `release` profile) |
| `*-javadoc.jar` | `maven-javadoc-plugin` (in `release` profile) |
| `*.jar.asc` etc. | `maven-gpg-plugin` (in `release` profile) |

### The Release Profile

The parent `pom.xml` declares a Maven profile `release` (activated by `-Prelease`)
that enables all three publishing requirements:

```xml
<profile>
  <id>release</id>
  <build>
    <plugins>
      <!-- 1. Sources JAR -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-source-plugin</artifactId>
        ...
      </plugin>
      <!-- 2. Javadoc JAR -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-javadoc-plugin</artifactId>
        ...
      </plugin>
      <!-- 3. GPG signing (loopback mode for CI) -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-gpg-plugin</artifactId>
        ...
      </plugin>
      <!-- 4. Sonatype Central publishing -->
      <plugin>
        <groupId>org.sonatype.central</groupId>
        <artifactId>central-publishing-maven-plugin</artifactId>
        <version>1.0.0-RC1</version>
        <extensions>true</extensions>
        <configuration>
          <publishingServerId>central</publishingServerId>
          <autoPublish>true</autoPublish>
          <waitForPublishing>true</waitForPublishing>
        </configuration>
      </plugin>
    </plugins>
  </build>
</profile>
```

**Key configuration options:**

| Option | Value | Meaning |
|--------|-------|---------|
| `autoPublish` | `true` | Publish automatically after validation (no manual portal step) |
| `waitForPublishing` | `true` | Block the build until publishing completes (required for CI success) |
| `publishingServerId` | `central` | Must match the `<server><id>` in Maven settings |

### ojp-server: Dual Distribution

`ojp-server` is distributed in **two forms**:

- **Executable (shaded) JAR → Maven Central** — produced by `maven-shade-plugin` with
  `shadedArtifactAttached=true`. This is a self-contained, runnable JAR that includes all
  dependencies and sets `org.openjproxy.grpc.server.GrpcServer` as the main class. Users
  can download and run it without Docker:
  ```bash
  java -jar ojp-server-<version>-shaded.jar
  ```
- **Docker image → Docker Hub** — built via a multi-stage `Dockerfile` (jlink custom JRE on Alpine, ~127MB). Open-source JDBC drivers are downloaded into `ojp-libs/` before the build and copied into the image.

  The image does not declare `EXPOSE` — port mapping is the operator's responsibility via `-p`. The server defaults to port `1059` (gRPC) and `9159` (Prometheus) as defined in `ServerConfiguration`; override at runtime with `-e OJP_SERVER_PORT=<port>` and `-e OJP_PROMETHEUS_PORT=<port>`, with a matching `-p` on the `docker run` command.

Both are published as part of the same release workflow run.

---

## Post-Release Checklist

After the workflow completes:

- [ ] Confirm Maven Central publication: <https://central.sonatype.com/search?q=org.openjproxy>
- [ ] Verify the Docker image: `docker pull rrobetti/ojp:<version>`
- [ ] Check the GitHub Release page for the new tag and release notes
- [ ] Verify `main` branch now shows the next `SNAPSHOT` version
- [ ] Update the website / documentation to reference the new version
- [ ] Announce the release (GitHub Discussions, Discord, etc.)

---

## Troubleshooting

### "GPG signing failed"

- Ensure `GPG_PRIVATE_KEY` contains the full ASCII-armored key including the
  `-----BEGIN PGP PRIVATE KEY BLOCK-----` and `-----END PGP PRIVATE KEY BLOCK-----` lines.
- Ensure `GPG_PASSPHRASE` matches the passphrase used when the key was created.
- The public key must be uploaded to a keyserver (e.g. `keyserver.ubuntu.com`).

### "401 Unauthorized from Sonatype"

- The credentials must be **User Tokens**, not your login username/password.
- Regenerate the token at <https://central.sonatype.com/account> and update the secrets.

### "Namespace not verified"

- The `org.openjproxy` namespace must be verified before any artifacts can be published.
- Follow the verification steps in [Prerequisites](#1-sonatype-central-account).

### "Push to main failed — branch protection"

- The workflow uses `RELEASE_TOKEN` (a PAT) to push commits and tags.
- If `RELEASE_TOKEN` is not set, it falls back to `GITHUB_TOKEN`, which may not
  have permission to push to protected branches.
- See [Branch Protection](#5-branch-protection) for solutions.

### "versions:set did not update inter-module dependency versions"

- Run `mvn versions:set` with `-DprocessAllModules=true` (already the default in
  the workflow).
- If a dependency version still shows the old value, manually update that `pom.xml`
  and commit the fix.

### "Deployment fails intermittently"

This is a known occasional issue (noted in the original manual process) with Sonatype
Central. Re-running the workflow (or re-running from the failed step) typically resolves it.

### Docker build fails: "proprietary JDBC drivers in pom.xml"

Ensure `ojp-server/pom.xml` does not contain proprietary JDBC driver dependencies
(e.g. Oracle, IBM DB2 commercial) before the Docker image build step. These must
not be baked into the public Docker image.

---

## Suggestions for Future Improvements

### 1. ~~Remove the `-beta` suffix after `1.0.0-GA`~~ ✅ Implemented

The release workflow now produces plain `X.Y.Z` versions (no suffix) for all GA releases
from `1.0.0` onwards. The `-beta` path has been removed.

### 2. Semantic versioning automation

Consider using [Conventional Commits](https://www.conventionalcommits.org/) and
a tool like [semantic-release](https://semantic-release.gitbook.io/) or
[release-please](https://github.com/googleapis/release-please) to automate version
bumps based on commit messages (patch for `fix:`, minor for `feat:`, major for
`BREAKING CHANGE:`).

### 3. Separate release branch strategy

Instead of pushing directly to `main`, open a release PR:

```yaml
# In the workflow: push to a release branch and open a PR
git checkout -b "release/v${{ steps.versions.outputs.release }}"
git push origin "release/v${{ steps.versions.outputs.release }}"
gh pr create --base main --title "chore(release): v${{ steps.versions.outputs.release }}" --body "..."
```

This preserves the review gate even during releases and leaves a clear audit trail.

### 4. Staging mode for manual approval

If you want a final human review before publishing to Maven Central, change
`autoPublish` to `false` in the release profile:

```xml
<autoPublish>false</autoPublish>
```

The workflow will stage the artifacts and you manually approve them at
<https://central.sonatype.com/publishing/deployments>.

### 5. Publish ojp-grpc-commons separately

`ojp-grpc-commons` has no source/javadoc plugins in its own `pom.xml`. These are
now provided by the parent's `release` profile, but adding them explicitly to the
module's `pom.xml` (for documentation clarity) would make each module self-contained.

### 6. Rollback workflow

Add a companion `rollback.yml` workflow that, given a version tag, reverts the
pom changes and allows re-release from the same version. Useful if Maven Central
publication succeeds but Docker push fails.

### 7. ~~Changelog automation~~ ✅ Implemented

GitHub automatic release notes are now enabled via `generate_release_notes`
in the release workflow and configured through `.github/release.yml`. Pull
request labels control which section each PR appears in. No third-party tools
are needed.

### 8. Reusable workflow

Extract the version calculation and Maven deploy steps into a
[reusable workflow](https://docs.github.com/en/actions/using-workflows/reusing-workflows)
so that sub-projects or forks can invoke the same release logic without duplicating YAML.

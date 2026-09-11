# Support Policy

This document describes which versions of Open J Proxy receive active maintenance and for how long.

---

## Supported Versions

| Version line | Status   | Support type          | End of support |
|---|---|---|---|
| `1.0.x`      | **LTS**  | Bug + security fixes  | 3 years from `1.0.0` GA release (targeting September 20, 2029) |
| `main` / latest | **Current** | All changes | Rolling — no fixed end date |

---

## LTS line: `1.0.x`

`1.0.x` is the first **Long-Term Support** release line of Open J Proxy.

### What the LTS line receives

- Bug fixes
- Security fixes
- Critical compatibility fixes (e.g. Java LTS runtime compatibility)
- Necessary dependency updates to address published CVEs

### What the LTS line does NOT receive

- New features
- Breaking changes
- Unnecessary refactoring
- Non-critical dependency upgrades

All backwards-compatible new features go to `main` and produce `1.1.0`, `1.2.0`, etc.
Breaking changes go to `main` and produce `2.0.0` when they justify a major release.

---

## Branch and tag model

```
v1.0.0 (tag)
  │
  ├── lts/1.0 ──── v1.0.1 ──── v1.0.2 ──── v1.0.3 ──── ...
  │
  └── main ──────── v1.1.0 ──── v1.2.0 ──── ... ──── v2.0.0
```

- `lts/1.0` is the maintenance branch for the 1.0.x line.
- Individual releases are immutable tags (`v1.0.1`, `v1.0.2`, …).
- `main` is the active development trunk.

---

## Reporting security vulnerabilities

Please do **not** open a public GitHub Issue for security vulnerabilities.

Use GitHub's private vulnerability reporting:
**Repository → Security → Report a vulnerability**

Or email the maintainers directly via the contact listed in the repository profile.

We aim to acknowledge reports within 48 hours and provide a fix or mitigation within 14 days for critical issues.

---

## Older versions (pre-1.0.0)

Versions `0.x.y-beta` are **not supported**. Users on those versions are encouraged to upgrade to `1.0.0` or later.

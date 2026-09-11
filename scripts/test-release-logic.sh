#!/usr/bin/env bash
# =============================================================================
# test-release-logic.sh — Unit tests for OJP release version computation logic
#
# Run with:  bash scripts/test-release-logic.sh
#
# Exit code 0 = all tests passed
# Exit code 1 = one or more tests failed
# =============================================================================
set -euo pipefail

PASS=0
FAIL=0

# ANSI colours (gracefully degraded when terminal does not support them)
RED=$'\033[0;31m'
GRN=$'\033[0;32m'
RST=$'\033[0m'

# -----------------------------------------------------------------------------
# Core version-calculation function — mirrors the logic in release.yml
# Returns: "<release_version>|<next_dev_version>|<is_prerelease>" or "ERROR:<message>"
# Arguments: $1=branch  $2=current_pom_version  $3=release_version_override
# -----------------------------------------------------------------------------
compute_versions() {
    local branch="$1"
    local current="$2"
    local override="$3"

    local lts_branch=false
    local lts_major=""
    local lts_minor=""

    if echo "${branch}" | grep -qE '^lts/[0-9]+\.[0-9]+$'; then
        lts_branch=true
        lts_major=$(echo "${branch}" | cut -d/ -f2 | cut -d. -f1)
        lts_minor=$(echo "${branch}" | cut -d/ -f2 | cut -d. -f2)
    fi

    local release
    local is_prerelease=false
    if [[ -n "${override}" ]]; then
        release="${override}"
        # Accept plain X.Y.Z  OR  X.Y.Z-<qualifier> (qualifier starts with a letter)
        if ! echo "${release}" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z][A-Za-z0-9]*)?$'; then
            echo "ERROR: release_version '${release}' must be X.Y.Z or X.Y.Z-<qualifier> (e.g. 1.2.0, 1.0.0-RC1)"
            return
        fi
        if echo "${release}" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+-'; then
            is_prerelease=true
        fi
    else
        if [[ "${current}" != *"-SNAPSHOT" ]]; then
            echo "ERROR: Current version '${current}' does not end in -SNAPSHOT"
            return
        fi
        release="${current%-SNAPSHOT}"
        if ! echo "${release}" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
            echo "ERROR: Auto-derived release version '${release}' is not a valid semver X.Y.Z"
            return
        fi
    fi

    # Extract numeric base (X.Y.Z) — for pre-releases like 1.0.0-RC1 strip qualifier
    local base_release="${release%%-*}"
    local rel_major rel_minor rel_patch
    rel_major=$(echo "${base_release}" | cut -d. -f1)
    rel_minor=$(echo "${base_release}" | cut -d. -f2)
    rel_patch=$(echo "${base_release}" | cut -d. -f3)

    if [[ "${lts_branch}" == "true" ]]; then
        if [[ "${rel_major}" != "${lts_major}" || "${rel_minor}" != "${lts_minor}" ]]; then
            echo "ERROR: Branch '${branch}' only allows ${lts_major}.${lts_minor}.x releases. Attempted: ${release}"
            return
        fi
    fi

    local next
    if [[ "${is_prerelease}" == "true" ]]; then
        next="${current}"
    elif [[ "${lts_branch}" == "true" ]]; then
        local next_patch=$(( rel_patch + 1 ))
        next="${rel_major}.${rel_minor}.${next_patch}-SNAPSHOT"
    elif [[ "${branch}" == "main" && "${rel_patch}" == "0" ]]; then
        local next_minor=$(( rel_minor + 1 ))
        next="${rel_major}.${next_minor}.0-SNAPSHOT"
    else
        local next_patch=$(( rel_patch + 1 ))
        next="${rel_major}.${rel_minor}.${next_patch}-SNAPSHOT"
    fi

    echo "${release}|${next}|${is_prerelease}"
}

# -----------------------------------------------------------------------------
# Test helpers
# -----------------------------------------------------------------------------
assert_eq() {
    local label="$1"
    local expected="$2"
    local actual="$3"
    if [[ "${expected}" == "${actual}" ]]; then
        echo "${GRN}PASS${RST}  ${label}"
        (( PASS++ )) || true
    else
        echo "${RED}FAIL${RST}  ${label}"
        echo "      expected: ${expected}"
        echo "      actual  : ${actual}"
        (( FAIL++ )) || true
    fi
}

assert_error() {
    local label="$1"
    local actual="$2"
    if echo "${actual}" | grep -q "^ERROR:"; then
        echo "${GRN}PASS${RST}  ${label} (got expected error)"
        (( PASS++ )) || true
    else
        echo "${RED}FAIL${RST}  ${label} (expected error, got: ${actual})"
        (( FAIL++ )) || true
    fi
}

# =============================================================================
# Tests — lts/1.0 branch
# =============================================================================
echo ""
echo "── lts/1.0 branch ──────────────────────────────────────────────────────"

assert_eq \
    "lts/1.0 + 1.0.1-SNAPSHOT => release 1.0.1 + next 1.0.2-SNAPSHOT" \
    "1.0.1|1.0.2-SNAPSHOT|false" \
    "$(compute_versions "lts/1.0" "1.0.1-SNAPSHOT" "")"

assert_eq \
    "lts/1.0 + 1.0.8-SNAPSHOT => release 1.0.8 + next 1.0.9-SNAPSHOT" \
    "1.0.8|1.0.9-SNAPSHOT|false" \
    "$(compute_versions "lts/1.0" "1.0.8-SNAPSHOT" "")"

assert_eq \
    "lts/1.0 + explicit 1.0.7 => release 1.0.7 + next 1.0.8-SNAPSHOT" \
    "1.0.7|1.0.8-SNAPSHOT|false" \
    "$(compute_versions "lts/1.0" "1.0.1-SNAPSHOT" "1.0.7")"

assert_error \
    "lts/1.0 + explicit 1.1.0 => FAIL (wrong minor)" \
    "$(compute_versions "lts/1.0" "1.0.1-SNAPSHOT" "1.1.0")"

assert_error \
    "lts/1.0 + explicit 1.2.0 => FAIL (wrong minor)" \
    "$(compute_versions "lts/1.0" "1.0.1-SNAPSHOT" "1.2.0")"

assert_error \
    "lts/1.0 + explicit 2.0.0 => FAIL (wrong major)" \
    "$(compute_versions "lts/1.0" "1.0.1-SNAPSHOT" "2.0.0")"

# =============================================================================
# Tests — main branch
# =============================================================================
echo ""
echo "── main branch ─────────────────────────────────────────────────────────"

assert_eq \
    "main + 1.0.0-SNAPSHOT => release 1.0.0 + next 1.1.0-SNAPSHOT (minor bump after .0)" \
    "1.0.0|1.1.0-SNAPSHOT|false" \
    "$(compute_versions "main" "1.0.0-SNAPSHOT" "")"

assert_eq \
    "main + 1.1.0-SNAPSHOT => release 1.1.0 + next 1.2.0-SNAPSHOT (minor bump after .0)" \
    "1.1.0|1.2.0-SNAPSHOT|false" \
    "$(compute_versions "main" "1.1.0-SNAPSHOT" "")"

assert_eq \
    "main + 1.1.1-SNAPSHOT => release 1.1.1 + next 1.1.2-SNAPSHOT (patch bump)" \
    "1.1.1|1.1.2-SNAPSHOT|false" \
    "$(compute_versions "main" "1.1.1-SNAPSHOT" "")"

assert_eq \
    "main + explicit 1.2.0 => release 1.2.0 + next 1.3.0-SNAPSHOT" \
    "1.2.0|1.3.0-SNAPSHOT|false" \
    "$(compute_versions "main" "1.1.1-SNAPSHOT" "1.2.0")"

assert_eq \
    "main + explicit 2.0.0 => release 2.0.0 + next 2.1.0-SNAPSHOT" \
    "2.0.0|2.1.0-SNAPSHOT|false" \
    "$(compute_versions "main" "1.1.1-SNAPSHOT" "2.0.0")"

assert_eq \
    "main + 2.0.0-SNAPSHOT (auto) => release 2.0.0 + next 2.1.0-SNAPSHOT" \
    "2.0.0|2.1.0-SNAPSHOT|false" \
    "$(compute_versions "main" "2.0.0-SNAPSHOT" "")"

# =============================================================================
# Tests — pre-release versions (RC, SNAPSHOT-numbered)
# =============================================================================
echo ""
echo "── pre-release versions ─────────────────────────────────────────────────"

assert_eq \
    "main + explicit 1.0.0-RC1 => publish RC1, no bump, is_prerelease=true" \
    "1.0.0-RC1|1.0.0-SNAPSHOT|true" \
    "$(compute_versions "main" "1.0.0-SNAPSHOT" "1.0.0-RC1")"

assert_eq \
    "main + explicit 1.0.0-RC2 => publish RC2, no bump, is_prerelease=true" \
    "1.0.0-RC2|1.0.0-SNAPSHOT|true" \
    "$(compute_versions "main" "1.0.0-SNAPSHOT" "1.0.0-RC2")"

assert_eq \
    "main + explicit 1.0.0-SNAPSHOT1 => publish SNAPSHOT1, no bump, is_prerelease=true" \
    "1.0.0-SNAPSHOT1|1.0.0-SNAPSHOT|true" \
    "$(compute_versions "main" "1.0.0-SNAPSHOT" "1.0.0-SNAPSHOT1")"

assert_eq \
    "lts/1.0 + explicit 1.0.1-RC1 => publish RC1, no bump, is_prerelease=true" \
    "1.0.1-RC1|1.0.1-SNAPSHOT|true" \
    "$(compute_versions "lts/1.0" "1.0.1-SNAPSHOT" "1.0.1-RC1")"

assert_error \
    "lts/1.0 + explicit 1.1.0-RC1 => FAIL (wrong minor, even for RC)" \
    "$(compute_versions "lts/1.0" "1.0.1-SNAPSHOT" "1.1.0-RC1")"

assert_error \
    "main + invalid pre-release format (digit-only qualifier) => FAIL" \
    "$(compute_versions "main" "1.0.0-SNAPSHOT" "1.0.0-1")"

# =============================================================================
# Tests — future lts/2.0 branch
# =============================================================================
echo ""
echo "── lts/2.0 branch (future) ─────────────────────────────────────────────"

assert_eq \
    "lts/2.0 + 2.0.3-SNAPSHOT => release 2.0.3 + next 2.0.4-SNAPSHOT" \
    "2.0.3|2.0.4-SNAPSHOT|false" \
    "$(compute_versions "lts/2.0" "2.0.3-SNAPSHOT" "")"

assert_error \
    "lts/2.0 + explicit 2.1.0 => FAIL" \
    "$(compute_versions "lts/2.0" "2.0.1-SNAPSHOT" "2.1.0")"

assert_error \
    "lts/2.0 + explicit 1.0.5 => FAIL (wrong major)" \
    "$(compute_versions "lts/2.0" "2.0.1-SNAPSHOT" "1.0.5")"

# =============================================================================
# Tests — invalid inputs
# =============================================================================
echo ""
echo "── invalid inputs ──────────────────────────────────────────────────────"

assert_error \
    "main + non-SNAPSHOT version (no override) => FAIL" \
    "$(compute_versions "main" "1.0.0" "")"

assert_error \
    "main + malformed snapshot => FAIL" \
    "$(compute_versions "main" "1.0.0-beta-SNAPSHOT" "")"

assert_error \
    "main + invalid override (missing patch) => FAIL" \
    "$(compute_versions "main" "1.0.0-SNAPSHOT" "1.0")"

assert_error \
    "main + invalid override (digit-only qualifier) => FAIL" \
    "$(compute_versions "main" "1.0.0-SNAPSHOT" "1.0.0-1")"

# =============================================================================
# Summary
# =============================================================================
echo ""
echo "──────────────────────────────────────────────────────────────────────────"
echo "Results: ${GRN}${PASS} passed${RST}, ${RED}${FAIL} failed${RST}"
echo ""

if [[ ${FAIL} -gt 0 ]]; then
    exit 1
fi


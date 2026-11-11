import json
import sys

from hash import ROOT_DIR, compute_proto_hash


package = json.loads((ROOT_DIR / "package.json").read_text(encoding="utf-8"))
hash_file = ROOT_DIR / "CONTRACT_HASH.json"

if not hash_file.exists():
    print('CONTRACT_HASH.json is missing. Run "npm run record-hash" to generate it.', file=sys.stderr)
    sys.exit(1)

recorded = json.loads(hash_file.read_text(encoding="utf-8"))
actual = compute_proto_hash()

if recorded["version"] != package["version"]:
    print(
        f"CONTRACT_HASH.json records version {recorded['version']}, but package.json is at "
        f"{package['version']}. Run \"npm run record-hash\" after bumping the version.",
        file=sys.stderr,
    )
    sys.exit(1)

if recorded["sha256"] != actual:
    print(
        "proto/*.proto content no longer matches the hash recorded for this version.\n"
        "If this change is intentional: bump the package version (npm version <bump>), then "
        'run "npm run record-hash".',
        file=sys.stderr,
    )
    sys.exit(1)

print(f"OK: proto/*.proto matches the recorded contract hash for v{package['version']}.")

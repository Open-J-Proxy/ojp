import json
from pathlib import Path

from hash import ROOT_DIR, compute_proto_hash


package = json.loads((ROOT_DIR / "package.json").read_text(encoding="utf-8"))
record = {"version": package["version"], "sha256": compute_proto_hash()}
hash_file = ROOT_DIR / "CONTRACT_HASH.json"
hash_file.write_text(f"{json.dumps(record, indent=2)}\n", encoding="utf-8")
print(f"Recorded contract hash for v{package['version']}: {record['sha256']}")

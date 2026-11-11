import hashlib
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parent.parent
PROTO_DIR = ROOT_DIR / "proto"


def compute_proto_hash():
    digest = hashlib.sha256()
    for proto_file in sorted(PROTO_DIR.glob("*.proto")):
        digest.update(proto_file.name.encode("utf-8"))
        digest.update(proto_file.read_bytes())
    return digest.hexdigest()

"""Reproducible protocol generation from the repository's canonical schema."""

import argparse
import importlib.metadata
from pathlib import Path
import shutil


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if committed bindings differ")
    parser.add_argument("--repo-root", type=Path, help="OJP checkout root (required outside checkout)")
    args = parser.parse_args()
    from grpc_tools import protoc
    import grpc_tools

    if importlib.metadata.version("grpcio-tools") != "1.78.0":
        parser.error("generation requires grpcio-tools==1.78.0 (install the dev extra)")
    package = Path(__file__).resolve().parent
    candidates = (package.parents[2], Path.cwd(), *Path.cwd().parents)
    root = args.repo_root or next(
        (candidate for candidate in candidates
         if (candidate / "ojp-grpc-commons/src/main/proto/StatementService.proto").is_file()),
        None,
    )
    if root is None:
        parser.error("canonical StatementService.proto not found; supply --repo-root")
    source = root / "ojp-grpc-commons/src/main/proto"
    if not (source / "StatementService.proto").is_file():
        parser.error("canonical StatementService.proto not found; supply --repo-root")
    # Common protos are namespace packages, so locate the installed distribution.
    common = Path(importlib.metadata.distribution("googleapis-common-protos").locate_file(""))
    destination = root / "ojp-client-python-dbapi/src/ojp/_proto"
    scratch = root / "ojp-client-python-dbapi/build/proto-check"
    output = scratch if args.check else destination
    output.mkdir(parents=True, exist_ok=True)
    try:
        result = protoc.main([
            "grpc_tools.protoc",
            f"-I{source}",
            f"-I{Path(grpc_tools.__file__).parent / '_proto'}",
            f"-I{common}",
            f"--python_out={output}",
            f"--grpc_python_out={output}",
            str(source / "StatementService.proto"),
        ])
        if result:
            raise SystemExit(result)
        grpc_file = output / "StatementService_pb2_grpc.py"
        text = grpc_file.read_text()
        old = "import StatementService_pb2 as StatementService__pb2"
        if old not in text:
            raise SystemExit("unexpected generated import; review generator output")
        grpc_file.write_text(text.replace(old, "from . import StatementService_pb2 as StatementService__pb2"))
        message_file = output / "StatementService_pb2.py"
        text = message_file.read_text()
        old = "_builder.BuildTopDescriptorsAndMessages(DESCRIPTOR, 'StatementService_pb2', _globals)"
        if old not in text:
            raise SystemExit("unexpected generated module name; review generator output")
        message_file.write_text(text.replace(old, "_builder.BuildTopDescriptorsAndMessages(DESCRIPTOR, __name__, _globals)"))
        if args.check:
            for name in ("StatementService_pb2.py", "StatementService_pb2_grpc.py"):
                if not (destination / name).is_file() or (output / name).read_bytes() != (destination / name).read_bytes():
                    raise SystemExit(f"stale generated binding: {name}")
    finally:
        if args.check:
            shutil.rmtree(scratch)


if __name__ == "__main__":
    main()

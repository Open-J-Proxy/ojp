#!/usr/bin/env python3
"""Check the published server JAR, then start it without an external classpath."""

import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import zipfile


def available_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def check_artifact(artifact):
    artifact = Path(artifact).resolve(strict=True)
    with zipfile.ZipFile(artifact) as jar:
        manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
        if "Main-Class: org.openjproxy.grpc.server.GrpcServer" not in manifest.splitlines():
            raise RuntimeError(f"{artifact}: missing server Main-Class")
        for entry in (
            "io/grpc/netty/NettyServerBuilder.class",
            "ch/qos/logback/classic/Logger.class",
            "com/zaxxer/hikari/HikariDataSource.class",
            "com/openjproxy/grpc/StatementServiceGrpc.class",
        ):
            if entry not in jar.namelist():
                raise RuntimeError(f"{artifact}: missing runtime dependency {entry}")
        for service, provider in (
            ("io.grpc.NameResolverProvider", "io.grpc.internal.DnsNameResolverProvider"),
            ("io.grpc.NameResolverProvider", "io.grpc.netty.UdsNameResolverProvider"),
            ("io.grpc.ManagedChannelProvider", "io.grpc.netty.NettyChannelProvider"),
            ("org.openjproxy.datasource.ConnectionPoolProvider",
             "org.openjproxy.datasource.hikari.HikariConnectionPoolProvider"),
            ("org.openjproxy.xa.pool.spi.XAConnectionPoolProvider",
             "org.openjproxy.xa.pool.commons.CommonsPool2XAProvider"),
        ):
            providers = jar.read(f"META-INF/services/{service}").decode("utf-8")
            if provider not in providers.splitlines():
                raise RuntimeError(f"{artifact}: missing service provider {provider}")

    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else shutil.which("java")
    if not java:
        raise RuntimeError("Java is not available")
    with tempfile.TemporaryDirectory(prefix="ojp-artifact-") as workdir:
        port = available_port()
        metrics_port = available_port()
        while metrics_port == port:
            metrics_port = available_port()
        log_path = Path(workdir) / "server.log"
        with log_path.open("w") as log:
            server = subprocess.Popen(
                [java, "-Duser.timezone=UTC", f"-Dojp.server.port={port}",
                 f"-Dojp.prometheus.port={metrics_port}", "-jar", str(artifact)],
                cwd=workdir, stdout=log, stderr=subprocess.STDOUT,
            )
            try:
                deadline = time.monotonic() + 45
                while time.monotonic() < deadline:
                    output = log_path.read_text()
                    if server.poll() is not None:
                        raise RuntimeError(f"{artifact}: server exited with {server.returncode}\n{output}")
                    if "OJP gRPC Server started successfully and awaiting termination" in output:
                        with socket.create_connection(("127.0.0.1", port), timeout=2):
                            pass
                        print(f"PASS: {artifact.name} starts with java -jar", flush=True)
                        break
                    time.sleep(0.25)
                else:
                    raise RuntimeError(f"{artifact}: startup timed out after 45 seconds\n{log_path.read_text()}")
            finally:
                server.terminate()
                try:
                    server.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait(timeout=5)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("Usage: python3 scripts/test-server-artifact.py <server.jar> [...]")
    for argument in sys.argv[1:]:
        check_artifact(argument)

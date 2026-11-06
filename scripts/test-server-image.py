#!/usr/bin/env python3
"""Start the local release image with its default entrypoint before publishing."""

import json
import socket
import subprocess
import sys
import time
import uuid


def docker(*arguments):
    try:
        result = subprocess.run(
            ["docker", *arguments], check=True, capture_output=True, text=True, timeout=15,
        )
    except subprocess.CalledProcessError as error:
        raise RuntimeError(f"Docker command failed: {error.cmd}\n{error.stdout}{error.stderr}") from error
    return (result.stdout + result.stderr if arguments[0] == "logs" else result.stdout).strip()


def check_image(image):
    name = f"ojp-release-smoke-{uuid.uuid4().hex}"
    created = False
    try:
        docker("run", "--detach", "--pull=never", "--name", name,
               "--publish", "127.0.0.1::1059", image)
        created = True
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            container = json.loads(docker("inspect", name))[0]
            output = docker("logs", name)
            if not container["State"]["Running"]:
                raise RuntimeError(f"{image}: container exited\n{output}")
            if "OJP gRPC Server started successfully and awaiting termination" in output:
                port = int(container["NetworkSettings"]["Ports"]["1059/tcp"][0]["HostPort"])
                with socket.create_connection(("127.0.0.1", port), timeout=2):
                    pass
                print(f"PASS: {image} starts with its default Docker entrypoint", flush=True)
                return
            time.sleep(0.5)
        raise RuntimeError(f"{image}: startup timed out after 60 seconds\n{docker('logs', name)}")
    finally:
        if created:
            docker("rm", "--force", name)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: python3 scripts/test-server-image.py <local-image:tag>")
    check_image(sys.argv[1])

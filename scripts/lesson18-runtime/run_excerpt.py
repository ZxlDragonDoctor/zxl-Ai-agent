"""Run the fixed lesson worker in Docker. The host only transports text."""

import json
from contextlib import suppress
import re
import subprocess
import sys
import threading
import uuid

from excerpt import EXCERPT_LIMIT, INPUT_LIMIT

IMAGE = "zxl-lesson18-excerpt:local"
TIMEOUT = 10
OUTPUT_LIMIT = 2048


def docker(*arguments, timeout=TIMEOUT):
    return subprocess.run(
        ["docker", *arguments], stdin=subprocess.DEVNULL, capture_output=True, timeout=timeout, check=False,
    )


def attach_worker(name, data):
    command = ["docker", "start", "--attach", "--interactive", name]
    with subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL) as process:
        output = bytearray()

        def send_input():
            try:
                with process.stdin:
                    process.stdin.write(data)
            except OSError:
                pass

        def read_output():
            output.extend(process.stdout.read(OUTPUT_LIMIT + 1))
            if len(output) > OUTPUT_LIMIT:
                process.kill()

        sender = threading.Thread(target=send_input)
        receiver = threading.Thread(target=read_output)
        sender.start()
        receiver.start()
        try:
            status = process.wait(timeout=TIMEOUT)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            sender.join()
            receiver.join()
        if status or len(output) > OUTPUT_LIMIT:
            raise RuntimeError("The worker returned an invalid response.")
        return bytes(output)


def run_excerpt(data):
    if len(data) > INPUT_LIMIT or not data.decode("utf-8").strip():
        raise ValueError("A nonempty UTF-8 input of at most 8192 bytes is required.")
    inspected = docker("image", "inspect", "--format", "{{.Id}}", IMAGE)
    image_id = inspected.stdout.decode("ascii").strip()
    if inspected.returncode or not re.fullmatch(r"sha256:[0-9a-f]{64}", image_id):
        raise RuntimeError("The fixed local worker image is unavailable.")
    name = "zxl-lesson18-excerpt-" + uuid.uuid4().hex
    try:
        created = docker(
            "create", "--name", name, "--pull", "never", "--interactive",
            "--network", "none", "--read-only", "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges", "--user", "65534:65534",
            "--memory", "128m", "--memory-swap", "128m", "--cpus", "1",
            "--pids-limit", "32", "--log-driver", "none", image_id,
        )
        if created.returncode:
            raise RuntimeError("The restricted worker could not be created.")
        payload = json.loads(attach_worker(name, data))
        if (not isinstance(payload, dict) or set(payload) != {"excerpt", "truncated"}
                or not isinstance(payload["excerpt"], str) or len(payload["excerpt"]) > EXCERPT_LIMIT
                or type(payload["truncated"]) is not bool):
            raise RuntimeError("The worker returned an invalid response.")
    except BaseException:
        with suppress(OSError, subprocess.TimeoutExpired):
            docker("rm", "--force", name, timeout=5)
        raise
    if docker("rm", "--force", name, timeout=5).returncode:
        raise RuntimeError("The restricted worker could not be removed.")
    return payload


def main():
    if len(sys.argv) != 1:
        sys.stderr.write("This lab reads text from stdin and accepts no arguments.\n")
        return 2
    try:
        payload = run_excerpt(sys.stdin.buffer.read(INPUT_LIMIT + 1))
    except (ValueError, OSError, RuntimeError, subprocess.TimeoutExpired):
        sys.stderr.write("Restricted excerpt worker failed.\n")
        return 1
    sys.stdout.buffer.write(json.dumps(payload, ensure_ascii=False).encode("utf-8") + b"\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

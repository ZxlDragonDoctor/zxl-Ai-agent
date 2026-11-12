"""Run the fixed launcher against a real Linux Docker engine."""

import json
from pathlib import Path
import subprocess
import sys
import time
from unittest.mock import patch

import run_excerpt


PROBE = """
import errno
import json
from pathlib import Path
import socket

interfaces = sorted(name for _, name in socket.if_nameindex())
try:
    Path('/tmp/lesson18-write-probe').write_bytes(b'fixture')
except OSError as failure:
    if failure.errno != errno.EROFS:
        raise
else:
    raise RuntimeError('The container root filesystem accepted a write.')

cgroup = Path('/sys/fs/cgroup')
print(json.dumps({
    'interfaces': interfaces,
    'rootWrite': 'EROFS',
    'memoryMax': int((cgroup / 'memory.max').read_text()),
    'pidsMax': int((cgroup / 'pids.max').read_text()),
    'cpuMax': (cgroup / 'cpu.max').read_text().split(),
}))
"""


def run(*args, data=None):
    return subprocess.run(args, input=data, capture_output=True, check=True, timeout=45).stdout


def lesson_containers():
    output = run("docker", "ps", "-aq", "--no-trunc", "--filter", "name=^/zxl-lesson18-excerpt-")
    return set(output.splitlines())

def inspect_running_worker(name, data):
    # The fixed worker waits for EOF. Inspect that same container while its input is open.
    with subprocess.Popen(
        ["docker", "start", "--attach", "--interactive", name],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    ) as worker:
        try:
            deadline = time.monotonic() + 5
            while run("docker", "inspect", "--format", "{{.State.Running}}", name).strip() != b"true":
                if worker.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError("The worker did not become ready for the probe.")
                time.sleep(0.05)
            observation = json.loads(run("docker", "exec", name, "python", "-I", "-B", "-c", PROBE))
            expected = {"interfaces": ["lo"], "rootWrite": "EROFS", "memoryMax": 134217728, "pidsMax": 32}
            if any(observation.get(key) != value for key, value in expected.items()):
                raise RuntimeError(f"Unexpected container observations: {observation}")
            quota, period = map(int, observation["cpuMax"])
            if quota != period or quota <= 0:
                raise RuntimeError(f"Expected one CPU, observed {observation['cpuMax']}.")
            print("Container observations:", json.dumps(observation, sort_keys=True))
            output, _ = worker.communicate(data, timeout=10)
            if worker.returncode:
                raise RuntimeError("The inspected worker did not finish.")
            return output
        finally:
            if worker.poll() is None:
                worker.kill()
                worker.wait(timeout=5)


def main():
    before = lesson_containers()
    launcher = str(Path(__file__).with_name("run_excerpt.py"))
    text = "课程摘录" * 70
    output = run(sys.executable, "-B", launcher, data=text.encode("utf-8"))
    if json.loads(output) != {"excerpt": text[:240], "truncated": True}:
        raise RuntimeError("Unexpected excerpt from the real container.")
    with patch.object(run_excerpt, "attach_worker", inspect_running_worker):
        if run_excerpt.run_excerpt(b"inspect this worker") != {"excerpt": "inspect this worker", "truncated": False}:
            raise RuntimeError("Unexpected excerpt from the inspected container.")
    if lesson_containers() - before:
        raise RuntimeError("The launcher left a lesson container behind.")
    print("Real Docker launcher: Unicode excerpt, pre-start configuration check and cleanup passed.")


if __name__ == "__main__":
    main()

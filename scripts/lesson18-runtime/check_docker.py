"""Run the fixed launcher against a real Linux Docker engine."""

import json
import errno
from pathlib import Path
import socket
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
import sys

interfaces = sorted(name for _, name in socket.if_nameindex())
try:
    Path('/tmp/lesson18-write-probe').write_bytes(b'fixture')
except OSError as failure:
    if failure.errno != errno.EROFS:
        raise
else:
    raise RuntimeError('The container root filesystem accepted a write.')

cgroup = Path('/sys/fs/cgroup')
with socket.socket() as outbound:
    outbound.settimeout(2)
    network_error = outbound.connect_ex((sys.argv[1], int(sys.argv[2])))
print(json.dumps({
    'interfaces': interfaces,
    'rootWrite': 'EROFS',
    'memoryMax': int((cgroup / 'memory.max').read_text()),
    'pidsMax': int((cgroup / 'pids.max').read_text()),
    'cpuMax': (cgroup / 'cpu.max').read_text().split(),
    'outboundErrno': network_error,
}))
"""

CONNECT_CONTROL = """
import socket
import sys
with socket.create_connection((sys.argv[1], int(sys.argv[2])), timeout=2):
    pass
"""

MEMORY_PROBE = """
allocation = bytearray(160 * 1024 * 1024)
for index in range(0, len(allocation), 4096):
    allocation[index] = 1
"""


def run(*args, data=None):
    return subprocess.run(args, input=data, capture_output=True, check=True, timeout=45).stdout


def lesson_containers():
    output = run("docker", "ps", "-aq", "--no-trunc", "--filter", "name=^/zxl-lesson18-excerpt-")
    return set(output.splitlines())

def check_memory_limit(name):
    command = ["docker", "exec", name, "python", "-I", "-B"]
    read_events = "print(open('/sys/fs/cgroup/memory.events').read())"
    before = dict(line.split() for line in run(*command, "-c", read_events).decode().splitlines() if line)
    probe = subprocess.run([*command, "-c", MEMORY_PROBE], capture_output=True, timeout=10)
    after = dict(line.split() for line in run(*command, "-c", read_events).decode().splitlines() if line)
    kills = int(after["oom_kill"]) - int(before["oom_kill"])
    if probe.returncode != 137 or kills != 1:
        raise RuntimeError(f"Expected cgroup OOM kill, observed exit={probe.returncode}, oom_kill delta={kills}.")
    print("Memory probe: exit=137, oom_kill delta=1.")


def inspect_running_worker(name, data, endpoint):
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
            observation = json.loads(run("docker", "exec", name, "python", "-I", "-B", "-c", PROBE, *endpoint))
            expected = {"interfaces": ["lo"], "rootWrite": "EROFS", "memoryMax": 134217728, "pidsMax": 32}
            if any(observation.get(key) != value for key, value in expected.items()):
                raise RuntimeError(f"Unexpected container observations: {observation}")
            quota, period = map(int, observation["cpuMax"])
            if quota != period or quota <= 0:
                raise RuntimeError(f"Expected one CPU, observed {observation['cpuMax']}.")
            if observation["outboundErrno"] not in (errno.ENETUNREACH, errno.EHOSTUNREACH):
                raise RuntimeError(f"Expected network unreachable, observed errno={observation['outboundErrno']}.")
            print("Container observations:", json.dumps(observation, sort_keys=True))
            check_memory_limit(name)
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
    gateway = run("docker", "network", "inspect", "--format", "{{(index .IPAM.Config 0).Gateway}}", "bridge").decode().strip()
    with socket.socket() as listener:
        listener.bind((gateway, 0))
        listener.listen(2)
        listener.settimeout(2)
        endpoint = (gateway, str(listener.getsockname()[1]))
        run("docker", "run", "--rm", "--pull", "never", "--network", "bridge", "--entrypoint", "python",
            run_excerpt.IMAGE, "-I", "-B", "-c", CONNECT_CONTROL, *endpoint)
        connection, _ = listener.accept()
        connection.close()
        with patch.object(run_excerpt, "attach_worker", lambda name, data: inspect_running_worker(name, data, endpoint)):
            if run_excerpt.run_excerpt(b"inspect this worker") != {"excerpt": "inspect this worker", "truncated": False}:
                raise RuntimeError("Unexpected excerpt from the inspected container.")
        listener.settimeout(0.25)
        try:
            connection, _ = listener.accept()
        except TimeoutError:
            print("Network probe: bridge control reached the listener; isolated worker did not.")
        else:
            connection.close()
            raise RuntimeError("The isolated worker reached the test listener.")
    if lesson_containers() - before:
        raise RuntimeError("The launcher left a lesson container behind.")
    print("Real Docker launcher: Unicode excerpt, pre-start configuration check and cleanup passed.")


if __name__ == "__main__":
    main()

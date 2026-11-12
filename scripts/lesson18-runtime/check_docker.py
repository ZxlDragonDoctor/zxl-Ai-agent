"""Run the fixed launcher against a real Linux Docker engine."""

import json
from pathlib import Path
import subprocess
import sys


def run(*args, data=None):
    return subprocess.run(args, input=data, capture_output=True, check=True, timeout=45).stdout


def lesson_containers():
    output = run("docker", "ps", "-aq", "--no-trunc", "--filter", "name=^/zxl-lesson18-excerpt-")
    return set(output.splitlines())


def main():
    before = lesson_containers()
    launcher = str(Path(__file__).with_name("run_excerpt.py"))
    text = "课程摘录" * 70
    output = run(sys.executable, "-B", launcher, data=text.encode("utf-8"))
    if json.loads(output) != {"excerpt": text[:240], "truncated": True}:
        raise RuntimeError("Unexpected excerpt from the real container.")
    if lesson_containers() - before:
        raise RuntimeError("The launcher left a lesson container behind.")
    print("Real Docker launcher: Unicode excerpt, pre-start configuration check and cleanup passed.")


if __name__ == "__main__":
    main()

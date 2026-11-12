import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

import run_excerpt


def result(stdout=b"", status=0):
    return subprocess.CompletedProcess([], status, stdout)


class ExcerptTest(unittest.TestCase):
    def test_unicode_prefix_and_input_limit(self):
        command = [sys.executable, str(Path(__file__).with_name("excerpt.py"))]
        text = "甲" * 241
        process = subprocess.run(command, input=text.encode(), capture_output=True, check=True)
        self.assertEqual(json.loads(process.stdout), {"excerpt": text[:240], "truncated": True})
        for invalid in (b"x" * 8193, b"\xff", b"\n"):
            process = subprocess.run(command, input=invalid, capture_output=True)
            self.assertEqual(process.returncode, 2)
            self.assertEqual(process.stdout, b"")

    def test_fixed_isolation_and_cleanup_after_timeout(self):
        image = "sha256:" + "a" * 64
        with patch.object(run_excerpt, "docker", side_effect=[result(image.encode()), result(), result()]) as cli:
            with patch.object(run_excerpt, "attach_worker", side_effect=subprocess.TimeoutExpired("docker", 10)):
                with self.assertRaises(subprocess.TimeoutExpired):
                    run_excerpt.run_excerpt(b"lesson input")
        create = cli.call_args_list[1].args
        self.assertEqual(create[-1], image)
        self.assertIn("--read-only", create)
        self.assertIn("--network", create)
        self.assertIn("none", create)
        self.assertNotIn("--mount", create)
        self.assertNotIn("--volume", create)
        self.assertEqual(cli.call_args_list[2].args, ("rm", "--force", create[2]))

    def test_missing_image_never_creates_a_worker(self):
        with patch.object(run_excerpt, "docker", return_value=result(status=1)) as cli:
            with self.assertRaises(RuntimeError):
                run_excerpt.run_excerpt(b"lesson input")
        cli.assert_called_once()

    def test_cleanup_failure_suppresses_successful_excerpt(self):
        image = "sha256:" + "a" * 64
        with patch.object(run_excerpt, "docker", side_effect=[result(image.encode()), result(), result(status=1)]):
            with patch.object(run_excerpt, "attach_worker", return_value=b'{"excerpt":"sample","truncated":false}'):
                with self.assertRaisesRegex(RuntimeError, "could not be removed"):
                    run_excerpt.run_excerpt(b"sample")


if __name__ == "__main__":
    unittest.main()

"""Extract a fixed prefix from bounded UTF-8 text supplied on stdin."""

import json
import sys

INPUT_LIMIT = 8192
EXCERPT_LIMIT = 240


def main():
    raw = sys.stdin.buffer.read(INPUT_LIMIT + 1)
    if len(raw) > INPUT_LIMIT:
        return 2
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        return 2
    if not text.strip():
        return 2
    result = {"excerpt": text[:EXCERPT_LIMIT], "truncated": len(text) > EXCERPT_LIMIT}
    sys.stdout.buffer.write(json.dumps(result, ensure_ascii=False).encode("utf-8") + b"\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

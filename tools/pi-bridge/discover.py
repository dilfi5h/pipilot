#!/usr/bin/env python3
"""List live pipilot occupancy records (pid -> session jsonl).

Stale files are expected: kill -9 never runs session_shutdown. A leftover
JSON is harmless as long as we do not treat a dead (or reused) pid as a
live pi. This script skips those and deletes the file.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path


def occupancy_dir() -> Path:
    xdg = os.environ.get("XDG_RUNTIME_DIR", "").strip()
    base = xdg or f"/tmp/pipilot-{os.getuid()}"
    return Path(base) / "pipilot"


def alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def cmdline(pid: int) -> str:
    proc = Path(f"/proc/{pid}/cmdline")
    if proc.exists():
        try:
            return proc.read_bytes().replace(b"\0", b" ").decode("utf-8", "replace").strip()
        except OSError:
            return ""
    try:
        return subprocess.check_output(
            ["ps", "-p", str(pid), "-o", "args="],
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


def looks_like_pi(cmd: str) -> bool:
    if not cmd:
        return False
    if "pi-coding-agent" in cmd:
        return True
    parts = cmd.split()
    return any(Path(p).name == "pi" for p in parts)


def main() -> int:
    d = occupancy_dir()
    if not d.is_dir():
        print("[]")
        return 0
    out = []
    for path in sorted(d.glob("*.json")):
        try:
            rec = json.loads(path.read_text())
        except (OSError, json.JSONDecodeError):
            try:
                path.unlink()
            except OSError:
                pass
            continue
        pid = rec.get("pid")
        if not isinstance(pid, int) or not alive(pid) or not looks_like_pi(cmdline(pid)):
            try:
                path.unlink()
            except OSError:
                pass
            inbox = rec.get("inbox")
            if isinstance(inbox, str):
                try:
                    Path(inbox).unlink()
                except OSError:
                    pass
            continue
        rec["_file"] = str(path)
        out.append(rec)
    json.dump(out, sys.stdout, indent=2, ensure_ascii=False)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

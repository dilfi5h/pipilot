#!/usr/bin/env python3
"""Local RPC smoke test for pi-bridge (occupancy + inbox). Does not install into ~/.pi."""
from __future__ import annotations

import json
import os
import select
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
EXT = ROOT / "index.ts"
SESS = Path("/tmp/pipilot-test-sessions")
DISCOVER = ROOT / "discover.py"


def jdump(obj):
    return json.dumps(obj, ensure_ascii=False)


class Rpc:
    def __init__(self, proc: subprocess.Popen):
        self.proc = proc
        self.buf = ""
        self.n = 0

    def send(self, obj: dict, timeout: float = 15.0) -> dict | None:
        self.n += 1
        req_id = obj.get("id") or f"t{self.n}"
        obj = {**obj, "id": req_id}
        assert self.proc.stdin is not None
        self.proc.stdin.write(jdump(obj) + "\n")
        self.proc.stdin.flush()
        deadline = time.time() + timeout
        while time.time() < deadline:
            for ev in self.drain(deadline - time.time()):
                if ev.get("type") == "response" and ev.get("id") == req_id:
                    return ev
        return None

    def drain(self, timeout: float) -> list[dict]:
        got = []
        end = time.time() + max(timeout, 0)
        stdout = self.proc.stdout
        assert stdout is not None
        while True:
            remaining = end - time.time()
            if remaining <= 0:
                break
            r, _, _ = select.select([stdout], [], [], min(remaining, 0.2))
            if not r:
                break
            chunk = os.read(stdout.fileno(), 65536)
            if not chunk:
                break
            self.buf += chunk.decode("utf-8", "replace")
            while "\n" in self.buf:
                line, self.buf = self.buf.split("\n", 1)
                line = line.rstrip("\r")
                if not line.strip():
                    continue
                try:
                    got.append(json.loads(line))
                except json.JSONDecodeError:
                    got.append({"type": "parse_error", "raw": line[:300]})
        return got


def discover() -> list:
    out = subprocess.check_output(["python3", str(DISCOVER)], text=True)
    return json.loads(out)


def main() -> int:
    SESS.mkdir(parents=True, exist_ok=True)
    cmd = [
        "pi",
        "--mode",
        "rpc",
        "--no-extensions",
        "--extension",
        str(EXT),
        "--session-dir",
        str(SESS),
        "--thinking",
        "off",
    ]
    print("SPAWN", " ".join(cmd), flush=True)
    proc = subprocess.Popen(
        cmd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=0,
    )
    rpc = Rpc(proc)
    inbox = None
    try:
        time.sleep(1.0)
        if proc.poll() is not None:
            err = proc.stderr.read() if proc.stderr else ""
            print("EXITED EARLY", proc.returncode, err)
            return 1
        st = rpc.send({"type": "get_state"}, timeout=12)
        print("STATE", jdump(st)[:700] if st else None, flush=True)
        recs = discover()
        print("DISCOVER1", jdump(recs), flush=True)
        ours = [r for r in recs if r.get("pid") == proc.pid]
        if not ours:
            print("FAIL: occupancy not found for pid", proc.pid)
            return 1
        rec = ours[0]
        state_file = ((st or {}).get("data") or {}).get("sessionFile")
        print("MATCH sessionFile", rec.get("sessionFile"), "vs", state_file, flush=True)
        if rec.get("sessionFile") != state_file:
            print("FAIL: occupancy sessionFile != get_state")
            return 1
        if rec.get("mode") != "rpc":
            print("FAIL: expected mode=rpc, got", rec.get("mode"))
            return 1
        inbox = rec.get("inbox")
        if not inbox or not Path(inbox).exists():
            print("FAIL: inbox socket missing", inbox)
            return 1
        print("INBOX", inbox, flush=True)

        import socket as sk
        payload = json.dumps({"source": "pipilot", "text": "bridge test ping"}).encode()
        s = sk.socket(sk.AF_UNIX)
        s.settimeout(5)
        s.connect(inbox)
        s.sendall(payload)
        s.shutdown(sk.SHUT_WR)
        s.close()
        print("PUSHED", flush=True)

        saw_custom = False
        deadline = time.time() + 6
        while time.time() < deadline:
            for ev in rpc.drain(0.4):
                t = ev.get("type")
                msg = ev.get("message") or {}
                if t in ("message_start", "message_end") and msg.get("customType") == "inbox":
                    print("CUSTOM", jdump(msg)[:400], flush=True)
                    saw_custom = True
                if t == "agent_start":
                    ab = rpc.send({"type": "abort"}, timeout=10)
                    print("ABORT", jdump(ab)[:200] if ab else None, flush=True)
            if saw_custom:
                break
        msgs = rpc.send({"type": "get_messages"}, timeout=10)
        mlist = ((msgs or {}).get("data") or {}).get("messages") or []
        custom = [m for m in mlist if m.get("role") == "custom" and m.get("customType") == "inbox"]
        print("CUSTOM_MSGS", jdump(custom)[:800], flush=True)
        if not custom:
            print("FAIL: inbox message not in get_messages")
            return 1
        rpc.send({"type": "abort"}, timeout=8)
        rpc.drain(0.8)

        ns = rpc.send({"type": "new_session"}, timeout=12)
        print("NEW_SESSION", jdump(ns)[:400] if ns else None, flush=True)
        time.sleep(0.4)
        st2 = rpc.send({"type": "get_state"}, timeout=12)
        recs2 = discover()
        ours2 = [r for r in recs2 if r.get("pid") == proc.pid]
        print("DISCOVER2", jdump(ours2), flush=True)
        file2 = ((st2 or {}).get("data") or {}).get("sessionFile")
        if not ours2 or ours2[0].get("sessionFile") != file2:
            print("FAIL: occupancy not updated after new_session")
            return 1
        if ours2[0].get("sessionFile") == rec.get("sessionFile"):
            print("FAIL: sessionFile did not change after new_session")
            return 1
        if ours2[0].get("inbox") != inbox:
            print("FAIL: inbox socket path changed after new_session", ours2[0].get("inbox"))
            return 1
        print("OK occupancy + inbox", flush=True)
        return 0
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
        err = ""
        if proc.stderr:
            try:
                err = proc.stderr.read()
            except Exception:
                err = ""
        if err:
            print("STDERR", err[-1500:], flush=True)
        time.sleep(0.3)
        leftover = [r for r in discover() if r.get("pid") == proc.pid]
        print("AFTER_EXIT", leftover, flush=True)
        if leftover:
            print("WARN: occupancy still listed after exit (stale pid should be filtered)")
        if inbox:
            print("SOCK_AFTER", Path(inbox).exists(), flush=True)


if __name__ == "__main__":
    raise SystemExit(main())

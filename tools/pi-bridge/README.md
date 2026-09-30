# pi-bridge (host extension)

One pi extension: **occupancy + inbox**. PiPilot lists which live pi pid owns
which session jsonl, then writes that process's Unix socket. The app does not
open a second RPC on the TUI's jsonl.

## index.ts

On `session_start`:

- listen on `$XDG_RUNTIME_DIR/pipilot/<pid>.sock` (fallback `/tmp/pipilot-<uid>/pipilot/`)
- export `$PI_INBOX` and `$PI_SESSION_ID` to tool subprocesses
- write occupancy JSON next to the socket:

```json
{
  "pid": 12345,
  "sessionFile": "/Users/you/.pi/agent/sessions/--root--/2026-09-30T....jsonl",
  "sessionId": "2026-09-30T...._01a0....",
  "inbox": "/tmp/pipilot-501/pipilot/12345.sock",
  "cwd": "/Users/you/proj",
  "mode": "tui",
  "startedAt": 1750000000000
}
```

Socket follows the **process**. Occupancy file follows the **current jsonl**
(`new` / `resume` / `fork` rewrite the same `<pid>.json`). `quit` / `reload`
close the socket and delete occupancy.

Write a message:

```bash
python3 -c 'import json,os,socket,sys; p=sys.argv[1]; b=json.dumps({"source":"pipilot","text":"stop and look at this"}).encode(); s=socket.socket(socket.AF_UNIX); s.connect(p); s.sendall(b); s.shutdown(socket.SHUT_WR); s.close()' "$INBOX"
# OpenBSD nc: echo '{"source":"pipilot","text":"hi"}' | nc -U -N "$INBOX"
```

JSON `{source?, pid?, text}` or plain text, 16 KB cap. Idle → immediately starts
a turn. Streaming → **steer** (after the current tool, before the next LLM call).

`kill -9` can leave JSON + a dead socket. `discover.py` skips a record unless
that pid is still alive **and** cmdline still looks like pi, then deletes the
stale files. Leftover files are not locks.

## Test (does not install into ~/.pi)

```bash
python3 tools/pi-bridge/test_occupancy.py
python3 tools/pi-bridge/discover.py
```

## Install (optional)

```bash
cp tools/pi-bridge/index.ts ~/.pi/agent/extensions/pipilot-bridge.ts
```

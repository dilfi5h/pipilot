# PiPilot design notes

> PiPilot is a native Android client that remote-controls the pi.dev coding agent
> via RPC mode, so you can drive the pi TUI that runs on your development
> machine/server from a phone. This document records design decisions, key
> implementation notes, and protocol pitfalls. Protocol reference:
> [docs/rpc.md](rpc.md) (synced from the upstream pi repo).

## 1. Goals and constraints

**Goal**: bring pi's core experience — conversation, tool execution, model
switching, session management — onto the phone, without interfering with the
desktop TUI (not a TUI clone; another "screen").

**Constraints**:

- pi is a Node process whose TUI runs in a terminal; Android has no native Node
  runtime (Termux is possible but the install path is fragile and background
  keep-alive is poor, so it was rejected).
- Upstream offers four integration modes: interactive TUI / print+JSON /
  **RPC (stdio JSONL)** / SDK. For a non-Node client, RPC is the only stable
  process-level interface; the SDK needs Node, and print mode has no streaming
  interactivity.
- The phone must not own agent compute/network (LLM API) duties; pi should run
  on a machine with good networking and a full toolchain (bash/git/editors).

## 2. Architecture decisions

### 2.1 Remote machine + SSH exec (not local Termux / WebSocket bridge)

Three candidates:

| Approach | Pros | Cons |
|---|---|---|
| **SSH exec remote pi** | No server component; reuses existing SSH; pi runs natively on Linux | Needs an SSH library; reconnect is DIY |
| Local Termux | Fully offline | Fragile Node/Android path; Android background limits; large engineering cost |
| WebSocket bridge service | Multi-device sharing, push-friendly | Extra always-on bridge to write and deploy |

SSH was chosen for **zero server deploy** — any SSH-able machine with pi
installed is enough. An SSH exec channel is already a full-duplex stdin/stdout
pipe and lines up naturally with RPC's stdio interface:

```
┌─────────────┐  SSH (sshj, exec channel)  ┌─────────────────────────┐
│  PiPilot    │ ──── stdin (JSONL) ──────▶ │ pi --mode rpc           │
│  (Android)  │ ◀─── stdout (JSONL) ────── │ (JSON event/response)   │
└─────────────┘                            └─────────────────────────┘
```

### 2.2 Stack: Kotlin + Jetpack Compose (native)

- JSONL streaming, SSH (sshj), and coroutine Flow are mature on the JVM; no
  platform-channel bridge cost like Flutter/RN.
- Chat UI is a Compose comfort zone: LazyColumn + streaming text + local updates.
  Follow-bottom uses remaining pixels under the last item, not
  `scrollToItem(lastIndex)` at offset 0, so a tall live bubble does not snap
  its start to the top of the viewport on every token. User drags pause
  follow; the jump-to-bottom FAB resumes it. A full history rebuild
  (reconnect after a long background, session switch) re-pins to the
  latest output; a short resume that did not rebuild history leaves the
  scroll place alone.
  Copy is split from system selection: each finished bubble has its own
  `SelectionContainer` for highlights; a Copy icon lives in `DisableSelection`
  chrome (timestamp row / code-block header) so a tap never starts a selection.
  Live streaming bubbles stay unselectable because tokens rebuild the item.
- Small package (minSdk 26, no WebView/JS engine).

### 2.3 Layers

```
ui/        Compose UI (PiScreen) + PiViewModel (single state outlet)
chat/      StreamReducer: RPC event stream → renderable ChatItem reduction
rpc/       PiProtocol (models + command builders), PiRpcClient (JSONL I/O, request/response correlation)
ssh/       SshConnector: sshj connect, auth (password/PEM), exec channel
settings/  SettingsStore (DataStore persistence for connection config)
```

Dependency direction is one-way: `ui → chat → rpc ← ssh`. `PiViewModel`
assembles the SSH channel and RPC client (`connect()`); the UI layer never
touches I/O.

## 3. Protocol implementation notes

### 3.1 Strict JSONL framing (the pitfall the pi docs call out)

pi RPC uses **strict JSONL**: only `\n` is a record delimiter, though a trailing
`\r` is accepted. The classic bug is `BufferedReader.readLine()` — it splits on
a set of line terminators, and some implementations treat `U+2028`/`U+2029`
(legal inside JSON strings) as EOL, so LLM output containing those characters
truncates messages and breaks JSON parsing.

Implementation (`PiRpcClient.readLoop`): frame by hand on `\n` — block reads
(8K char buffer) + char scan, cut on `\n`, `removeSuffix("\r")`, then parse.
Writes likewise: every command ends with an explicit `\n`; never call
`newLine()` (Windows would write `\r\n`; pi tolerates a trailing `\r`, but do not
bet on every version).

### 3.2 Request/response correlation

All commands support an optional `id`; a command with an `id` gets a
`{"type":"response",...}` with the same `id`. Events and responses share stdout, so:

- `PiRpcClient` keeps a `ConcurrentHashMap<String, CompletableDeferred<PiResponse>>`;
- `request(line, id, timeout)` registers the deferred before send and completes it on matching response;
- commands without an id (e.g. `steer`/`abort` historically) are fire-and-forget and show up via events;
- stdout close or timeout fails all pending deferreds so nothing hangs.

### 3.3 Streaming assembly (no snapshot, only deltas)

`message_update` events **do not carry a cumulative message snapshot**, only
delta sub-events (`text_delta`/`thinking_delta`/`toolcall_start|delta|end`).
Clients must assemble by `contentIndex`. `StreamReducer` state machine:

- `agent_start` resets the live buffer (body + thinking + toolcall args map);
- `text_delta` appends; `text_end` overwrites with the authoritative full text (avoids drift after dropped deltas);
- `message_end.message` is final; commit an `AssistantText(final)` from it;
- on `tool_execution_start`, commit current live text and clear it — later body
  belongs to the next segment, and clearing prevents duplicate rendering;
- **`tool_execution_update.partialResult` is cumulative output** (not a delta);
  replace the card wholesale, which is also the docs' recommended display.

### 3.4 UI item reduction (merge strategy)

`StreamReducer` emits "delta ops"; `PiViewModel.mergeItems` folds them into the
chat list: replace bubbles with `key == "live"` in place; update `ToolCard`s by
`toolCallId` in place (keep first args; update events omit args); append
`BashOutput` deltas; append everything else. That keeps LazyColumn `key` diffs
from rebuilding the whole table.

### 3.5 Sending while streaming

RPC requires: while the agent is streaming, `prompt` must declare
`streamingBehavior` (otherwise the command errors). PiPilot when idle sends a
bare `prompt`; while streaming the composer splits into two actions:

- **Steer** (`steer`): delivered after current tools finish, before the next LLM call — used to correct a wrong turn
- **Queue** (`follow_up`): processed only after the agent fully settles

The stop button matches TUI Esc: `clear_queue` first to recover unsent text into
the composer, then `abort`. Abort alone does not clear the queue; remaining
queued messages still run after abort. `queue_update` drives the "Pending"
banner at the bottom of the list; the banner's "Recall" clears the queue without
aborting the current turn.

### 3.6 Extension UI sub-protocol

pi extensions can pop `ctx.ui.select()/confirm()/input()`. In RPC mode that
becomes an `extension_ui_request` event that **blocks** until an
`extension_ui_response` arrives on stdin. PiPilot maps that to a native
`AlertDialog` (`PiViewModel` turns the event into `UiDialog` state). The three
reply shapes follow the docs strictly: `value` (select/input), `confirmed`
(confirm), `cancelled` (any). Fire-and-forget methods (notify/setStatus/setWidget,
etc.) only surface extension errors in the MVP and are not modeled one-by-one.

## 4. SSH layer

- sshj 0.40 + an explicitly registered full BouncyCastle (Android's built-in BC
  is trimmed; Ed25519/PKCS8 parsing would miss classes).
  Dependency note: sshj transitively pulls both `bcprov-jdk15on` and
  `bcprov-jdk18on`; the old module must be excluded or duplicate classes fail the build.
- Auth supports password and PEM private key (`PKCS8KeyFile`, optional passphrase).
- The `exec` channel starts `(cd <workdir> &&) <piCommand>`, default
  `pi --mode rpc`; stderr is drained continuously and logged (so a full buffer
  cannot block pi).
- Host-key verification is currently `PromiscuousVerifier` (trust all). Acceptable
  on a personal LAN; public internet use should pin a fingerprint (see Roadmap).
- Session listing does not go through RPC; a portable `find`+`stat` script runs
  via `runQuick`, sorts by mtime, takes the latest 30, then loads with
  `switch_session`.

## 5. State and lifecycle

- A single `UiState` (StateFlow) drives the whole UI; `PiViewModel` owns the SSH
  session and RPC client and tears the channel down when the ViewModel is cleared.
- Disconnect (stdout EOF / IO error / heartbeat timeout / network loss) → tear
  down the old SSH immediately, then exponential-backoff reconnect (2s→30s).
  During reconnect the UI keeps the chat list + banner; on success resume with
  `pi --mode rpc --session <path>` and rebuild history fully via `get_entries`.
- Background: ON_PAUSE starts a 10-minute FGS (WakeLock+WifiLock) and tightens
  the heartbeat to 20s; if the process is killed the FGS is not sticky-restarted
  (the connection lives in the ViewModel; an empty service has no SSH).
- The RPC process lives and dies with the SSH channel: disconnect exits remote
  pi. Session files persist remotely, so reconnect can restore without loss —
  that is why the design is "stateless connection + remote session files".

## 6. Security considerations

- Credentials (password/PEM/passphrase) live in the app-private DataStore with
  `allowBackup=false` and never leave the device.
- Password fields are not printed in RPC logs (commands go through
  `AppLog.redactCommand`); connection logs still include `user@host:port`
  (needed for debugging) but not password/PEM.
- Known compromise: PromiscuousVerifier (see §4). Improvement path: paste
  `sha256:<fingerprint>` in settings and swap in sshj's `FingerprintVerifier`.

## 7. Packaging and release

A local build takes about 1–4 minutes (cold cache on Windows). Official releases
go through GitHub Actions (`.github/workflows/release.yml`): pushing a `v*` tag
builds a signed release APK (`assembleRelease`, minify/shrink on) with JDK17 +
Gradle 8.9 + android-actions/setup-android, uploads a workflow artifact, and
attaches it to a GitHub Release via softprops/action-gh-release with
auto-generated notes. `gradle-wrapper` is intentionally not in the repo (local
Gradle is preinstalled; CI gets a pinned version from setup-gradle), which keeps
the repo cleaner and avoids reviewing a wrapper jar binary.

## 8. Roadmap

- [x] Auto reconnect with exponential backoff
- [x] `get_entries` history sync (full rebuild on reconnect)
- [x] Image attachments (`prompt.images`, ImageContent base64)
- [x] Short notification keep-alive (10-minute FGS) / multi-host profiles
- [ ] Host-key fingerprint pinning (FingerprintVerifier + settings)
- [ ] Session tree: `get_tree` / `get_fork_messages` / `fork` / `clone` (TUI `/tree`)
- [ ] `export_html` + on-phone preview
- [x] Signed release builds in CI (`assembleRelease`)
- [ ] Encrypted credential storage

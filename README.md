# PiPilot — Android client for remote-controlling pi.dev

PiPilot is a native Android (Kotlin + Jetpack Compose) app that SSHs into your
dev machine/server, starts [pi coding agent](https://pi.dev) in RPC mode
(`pi --mode rpc`), and brings the TUI conversation, tool runs, model switching,
and session management onto your phone.

## Download APK

**Option 1 (recommended): GitHub Releases** — pushing a `v*` tag makes CI build and publish:

- Latest release: https://github.com/dilfi5h/pipilot/releases/latest
- Or download `PiPilot-vX.Y.Z-release.apk` from this repo's Releases page

**Option 2: Actions artifact** — any commit can provide an APK under
[Actions → Build & Release APK](https://github.com/dilfi5h/pipilot/actions/workflows/release.yml).

**Option 3: Local build** (JDK 17 + Android SDK 35 + Gradle 8.9):

```bash
gradle assembleRelease   # or ./gradlew if the repo has a wrapper
# Output: app/build/outputs/apk/release/app-release.apk
```

## How it works

```
┌─────────────┐   SSH (sshj)    ┌──────────────────────────┐
│  PiPilot    │ ── exec ──────▶ │  pi --mode rpc           │
│  (Android)  │ ◀─ stdout ───── │  (JSONL event / response)│
└─────────────┘   stdin ──────▶ └──────────────────────────┘
```

- The app opens an SSH connection with [sshj](https://github.com/hierynomus/sshj) and `exec`s `pi --mode rpc`
- RPC protocol: strict JSONL (frame on `\n` only); commands on stdin; `response` + events on stdout
- Protocol docs: [`docs/rpc.md`](docs/rpc.md) (synced from the pi repo)

## Docs

- **[Design notes](docs/DESIGN.md)** — architecture decisions, protocol implementation notes (JSONL framing, streaming assembly, request/response correlation), SSH layer, security, and roadmap
- **[RPC protocol reference](docs/rpc.md)** — full upstream pi RPC-mode docs (commands / events / extension UI sub-protocol)
- **[Chat UI preview](tools/chat-preview/README.md)** — HTML mock of real pi sessions; settle layout here before Compose

## Chat UI preview (HTML before Android)

For **pure UI** work (bubbles, collapse, markdown, spacing, density) do **not** start in Compose. Pull real pi session files, iterate in a browser, then port the settled layout to Android. That avoids a release APK for every visual tweak.

1. Copy session jsonl from the machine that runs pi. Files live under `~/.pi/agent/sessions/` in cwd-named subfolders (e.g. `--root--`):

   ```bash
   mkdir -p tools/chat-preview/sessions
   scp 'host:~/.pi/agent/sessions/*/*.jsonl' tools/chat-preview/sessions/
   ```

2. Turn dumps into chat fixtures (same item shapes the app renders: user / assistant / tool / bash):

   ```bash
   python3 tools/chat-preview/parse_sessions.py
   ```

3. Open [`tools/chat-preview/index.html`](tools/chat-preview/index.html) in a browser. Switch sessions, expand/collapse thinking · tool · bash, then change CSS/markup until it looks right.

4. Only after that, implement the same behavior in Compose (`PiScreen` / `MarkdownText` / `ChatCollapse`).

Session dumps and generated `fixtures.js` are gitignored. Commands and current mock defaults: [`tools/chat-preview/README.md`](tools/chat-preview/README.md).

## Features (MVP)

- **Core chat**: send prompts, stream replies (`text_delta` assembly), abort
  (clear_queue then abort; queue text is restored into the composer), while streaming
  you can steer (after current tools) or queue a follow-up (after the agent settles);
  `queue_update` shows pending deliveries
- **Tool visualization**: `tool_execution_start/update/end` as tool cards with live
  args and cumulative output for bash/read/write; direct `bash` command output also
  gets a terminal-style card
- **Model switching**: `get_available_models` list + `set_model`; thinking level via `set_thinking_level`
- **Sessions**: `new_session`, list remote `~/.pi/agent/sessions/*.jsonl` and switch
  (`switch_session`), load history (`get_entries`), session stats
  (`get_session_stats`: tokens / context usage)
- **Image attachments**: pick images in the composer, compress to JPEG, send with `prompt.images`
- **Multi-host profiles**: save/switch named hosts in Settings
- **Background keep-alive**: short FGS + WakeLock/WifiLock (~10 min) when backgrounded;
  app-level `get_state` heartbeat (20s background / 60s foreground); exponential-backoff
  reconnect that resumes the session with `--session`
- **Extension UI bridge**: pi extension `select/confirm/input` dialogs
  (`extension_ui_request`) map to native Android `AlertDialog`; answers return via
  `extension_ui_response`

## Usage

1. Install the APK and open ⚙ Settings:
   - **Host / port / username**: SSH details for your machine
   - **Auth**: password, or PEM private key (passphrase supported)
   - **Launch command**: default `pi --mode rpc`; you can add `--provider` / `--model` / `--no-session`, etc.
   - **Working directory**: directory to `cd` into before starting pi (project root)
2. Tap Connect. History loads automatically after a successful connect.
3. Type in the composer. While the agent is streaming, send splits into **Steer**
   (takes effect after current tools) and **Queue** (after the whole run finishes);
   ⏹ recalls the queue then aborts. Use the top menu for model / thinking level / sessions.

## Remote machine requirements

```bash
# pi installed and on PATH (npm i -g @earendil-works/pi-coding-agent)
pi --version
# Session directory exists (created after using pi once)
ls ~/.pi/agent/sessions/
```

Note: RPC mode does not inherit TUI terminal rendering, but extensions, skills, and
prompt templates all work. TUI-only commands (e.g. `/settings`) do not run in RPC mode.

## Security

- This build verifies SSH host keys with `PromiscuousVerifier` (trust all hosts). Fine
  for a personal LAN; for public internet use, pin a host-key fingerprint in settings later
- Password / private key live in the app's private DataStore and are never uploaded;
  APK backup is disabled

## Known limits / Roadmap

- [x] Image attachments (`prompt.images`)
- [x] Multi-host profiles
- [x] Short notification keep-alive + automatic reconnect
- [x] `get_entries` history sync (full rebuild on reconnect, avoid live-message duplicates)
- [ ] Fine-tune live vs final bubble dedup after a streaming turn
- [ ] Session tree (`/tree`), fork/clone
- [ ] `export_html` with on-phone preview
- [ ] Host-key pinning (`FingerprintVerifier`), SSH agent forwarding
- [ ] Encrypted credential storage (DataStore is plaintext today; only `allowBackup=false`)

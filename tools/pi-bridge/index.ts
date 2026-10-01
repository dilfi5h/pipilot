/**
 * pipilot-bridge — occupancy + inbox in one extension.
 *
 * Occupancy: $XDG_RUNTIME_DIR/pipilot/<pid>.json (fallback /tmp/pipilot-<uid>/)
 *   pid → current session jsonl + inbox socket path.
 * Inbox: same dir, <pid>.sock. Tool subprocesses get $PI_INBOX / $PI_SESSION_ID.
 *   One Unix connection = one message (JSON {source?, pid?, text} or plain text,
 *   16 KB cap), delivered immediately if idle, otherwise as steer
 *   (after the current tool, before the next LLM call).
 *
 * Control: JSON {source?, command:"quit"} asks the TUI to exit so a remote
 *   client can take over the session. Like a steer, it is not abrupt: idle →
 *   ctx.shutdown() now; busy → queued, shutdown at the next tool_result
 *   (after the current tool, before the next LLM call), agent_settled as
 *   fallback. Replies "OK:quit" / "OK:queued" / "ERR:no-session" on the
 *   connection before closing it.
 *
 * Socket follows the process. Occupancy file follows the current jsonl.
 * Do not start the socket in the factory (pi may load extensions without a session).
 *
 * Install (optional): cp tools/pi-bridge/index.ts ~/.pi/agent/extensions/pipilot-bridge.ts
 */

import { spawn } from "node:child_process";
import { mkdirSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { createServer, type Server, type Socket } from "node:net";
import { basename, join } from "node:path";
import { text } from "node:stream/consumers";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Text } from "@earendil-works/pi-tui";

const MAX_BYTES = 16 * 1024;
const TAIL_LINES = 12;

type InboxEvent = { source?: string; pid?: number; text: string; command?: string };

export type Occupancy = {
  pid: number;
  sessionFile: string | null;
  sessionId: string | null;
  inbox: string | null;
  cwd: string;
  mode: string;
  startedAt: number;
};

export function occupancyDir(): string {
  const base = process.env.XDG_RUNTIME_DIR?.trim() || `/tmp/pipilot-${process.getuid?.() ?? process.env.USER ?? "user"}`;
  return join(base, "pipilot");
}

export function occupancyPath(pid = process.pid): string {
  return join(occupancyDir(), `${pid}.json`);
}

function writeOccupancy(record: Occupancy): void {
  const dir = occupancyDir();
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  const dest = occupancyPath(record.pid);
  const tmp = `${dest}.${process.pid}.tmp`;
  writeFileSync(tmp, JSON.stringify(record, null, 2) + "\n", { mode: 0o600 });
  renameSync(tmp, dest);
}

function removeOccupancy(): void {
  rmSync(occupancyPath(), { force: true });
}

function snapshot(
  ctx: { cwd: string; mode: string; sessionManager: { getSessionFile(): string | undefined; getSessionId(): string } },
  inbox: string | null,
): Occupancy {
  const sessionFile = ctx.sessionManager.getSessionFile() ?? null;
  const sessionId = sessionFile
    ? basename(sessionFile, ".jsonl")
    : ctx.sessionManager.getSessionId() || null;
  return {
    pid: process.pid,
    sessionFile,
    sessionId,
    inbox,
    cwd: ctx.cwd,
    mode: ctx.mode,
    startedAt: Date.now(),
  };
}

// Node has no SO_PEERCRED / LOCAL_PEERPID binding; pass the connection as a
// child's stdin and let python3 query fd 0.
const PEER_PID = `
import socket, struct, sys
s = socket.socket(fileno=0)
if sys.platform == "darwin":
    pid = struct.unpack("i", s.getsockopt(0, 2, 4))[0]  # SOL_LOCAL, LOCAL_PEERPID
else:
    pid = struct.unpack("3i", s.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))[0]
print(pid)
`;

function peerPid(conn: Socket): Promise<number | undefined> {
  return new Promise((resolve) => {
    let out = "";
    const child = spawn("python3", ["-c", PEER_PID], { stdio: [conn, "pipe", "ignore"] });
    child.stdout!.on("data", (d) => (out += d));
    child.on("error", () => resolve(undefined));
    child.on("close", () => resolve(Number.parseInt(out, 10) || undefined));
  });
}

function parse(raw: string): InboxEvent {
  try {
    const j = JSON.parse(raw);
    if (typeof j?.text === "string")
      return { source: j.source, pid: j.pid, text: j.text, command: typeof j.command === "string" ? j.command : undefined };
    if (typeof j?.command === "string")
      return { source: j.source, pid: j.pid, text: "", command: j.command };
  } catch {}
  return { text: raw.trim() };
}

export default function (pi: ExtensionAPI) {
  let server: Server | undefined;
  let inboxPath: string | null = null;
  let last: Occupancy | undefined;

  // Takeover state: quit behaves like a steer — it lands after the current
  // tool, before the next LLM call, never mid-tool.
  let agentBusy = false;
  let pendingQuit = false;
  let requestShutdown: (() => unknown) | undefined;

  pi.registerMessageRenderer("inbox", (message, _options, theme) => {
    const ev = message.details as InboxEvent;
    let lines = (ev?.text ?? "").split("\n");
    if (lines.length > TAIL_LINES) {
      lines = [`… ${lines.length - TAIL_LINES} lines`, ...lines.slice(-TAIL_LINES)];
    }
    const tag = [ev?.source, ev?.pid && `pid ${ev.pid}`].filter(Boolean).join(" ");
    const title = theme.fg("accent", theme.bold(`▌inbox ${tag}`.trimEnd()));
    return new Text(`${title}\n${theme.fg("muted", lines.join("\n"))}`, 0, 0);
  });

  const ensureInbox = () => {
    if (server && inboxPath) return inboxPath;
    const dir = occupancyDir();
    mkdirSync(dir, { recursive: true, mode: 0o700 });
    const path = join(dir, `${process.pid}.sock`);
    rmSync(path, { force: true });
    server = createServer(async (conn) => {
      const [pid, raw] = await Promise.all([peerPid(conn), text(conn).catch(() => "")]);
      const ev = parse(raw.slice(0, MAX_BYTES));
      if (ev.command === "quit") {
        handleQuit(conn);
        return;
      }
      if (!ev.text) return;
      ev.pid ??= pid;
      const tag = [ev.source, ev.pid && `pid ${ev.pid}`].filter(Boolean).join(" ");
      const header = tag ? `[inbox: ${tag}]` : "[inbox]";
      pi.sendMessage(
        { customType: "inbox", content: `${header}\n${ev.text}`, display: true, details: ev },
        { deliverAs: "steer", triggerTurn: true },
      );
    });
    server.listen(path);
    inboxPath = path;
    process.env.PI_INBOX = path;
    return path;
  };

  const stopInbox = () => {
    try {
      server?.close();
    } catch {}
    server = undefined;
    if (inboxPath) rmSync(inboxPath, { force: true });
    inboxPath = null;
    delete process.env.PI_INBOX;
  };

  /**
   * Takeover quit: same timing as a steer. Idle → shut down now. Busy →
   * queue and shut down at the next tool_result (after the current tool,
   * before the next LLM call); agent_settled covers turns that end without
   * another tool. Replies on the connection before closing it.
   */
  const handleQuit = (conn: Socket) => {
    let reply = "ERR:no-session";
    let shutdownNow = false;
    if (pendingQuit) {
      reply = "OK:queued";
    } else if (requestShutdown) {
      if (agentBusy) {
        pendingQuit = true;
        reply = "OK:queued";
      } else {
        reply = "OK:quit";
        shutdownNow = true;
      }
    }
    conn.end(reply + "\n", () => {
      if (shutdownNow) {
        try {
          void requestShutdown?.();
        } catch {}
      }
    });
  };

  const publish = (ctx: Parameters<typeof snapshot>[0]) => {
    last = snapshot(ctx, inboxPath);
    writeOccupancy(last);
  };

  pi.on("session_start", async (_event, ctx) => {
    ensureInbox();
    requestShutdown = () => ctx.shutdown();
    pendingQuit = false;
    const file = ctx.sessionManager.getSessionFile();
    process.env.PI_SESSION_ID = file ? basename(file, ".jsonl") : `pid-${process.pid}`;
    publish(ctx);
  });

  pi.on("before_agent_start", async () => {
    agentBusy = true;
  });

  pi.on("agent_settled", async (_event, ctx) => {
    agentBusy = false;
    if (pendingQuit) {
      pendingQuit = false;
      await ctx.shutdown();
    }
  });

  pi.on("tool_result", async (_event, ctx) => {
    if (pendingQuit) {
      pendingQuit = false;
      await ctx.shutdown();
    }
  });

  pi.on("session_shutdown", async (event, ctx) => {
    if (event.reason === "quit" || event.reason === "reload") {
      stopInbox();
      removeOccupancy();
      last = undefined;
      delete process.env.PI_SESSION_ID;
      return;
    }
    if (event.targetSessionFile) {
      process.env.PI_SESSION_ID = basename(event.targetSessionFile, ".jsonl");
      last = {
        ...snapshot(ctx, inboxPath),
        sessionFile: event.targetSessionFile,
        sessionId: basename(event.targetSessionFile, ".jsonl"),
      };
      writeOccupancy(last);
    }
  });

  process.once("exit", () => {
    try {
      stopInbox();
      removeOccupancy();
    } catch {}
  });
}

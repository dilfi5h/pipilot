package dev.pipilot.app.occupancy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class SessionOccupancy(
    val pid: Int,
    val sessionFile: String?,
    val inbox: String?,
    val mode: String?,
)

/**
 * Host occupancy records from pipilot-bridge (`<runtime>/pipilot/<pid>.json`).
 * A session is TUI-occupied when a live record has mode=tui and an inbox socket.
 */
object Occupancy {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Remote dump: one JSON object per line. Uses a quoted python -c body
     * (same quoting as other SSH snippets). Missing python3 → empty.
     */
    val LIST_COMMAND: String = run {
        val py =
            """
            import json, os, subprocess, sys
            from pathlib import Path
            xdg = os.environ.get("XDG_RUNTIME_DIR", "").strip()
            base = xdg or ("/tmp/pipilot-%s" % os.getuid())
            d = Path(base) / "pipilot"
            if not d.is_dir():
                sys.exit(0)

            def alive(pid):
                try:
                    os.kill(pid, 0)
                    return True
                except OSError:
                    return False

            def cmdline(pid):
                p = Path("/proc/%s/cmdline" % pid)
                if p.exists():
                    try:
                        return p.read_bytes().replace(b"\0", b" ").decode("utf-8", "replace").strip()
                    except OSError:
                        return ""
                try:
                    return subprocess.check_output(["ps", "-p", str(pid), "-o", "args="], text=True, stderr=subprocess.DEVNULL).strip()
                except Exception:
                    return ""

            def looks_like_pi(cmd):
                if not cmd:
                    return False
                if "pi-coding-agent" in cmd:
                    return True
                return any(Path(part).name == "pi" for part in cmd.split())

            for path in sorted(d.glob("*.json")):
                try:
                    rec = json.loads(path.read_text())
                except Exception:
                    continue
                pid = rec.get("pid")
                if not isinstance(pid, int) or not alive(pid) or not looks_like_pi(cmdline(pid)):
                    continue
                print(json.dumps({"pid": pid, "sessionFile": rec.get("sessionFile"), "inbox": rec.get("inbox"), "mode": rec.get("mode")}, separators=(",", ":")))
            """.trimIndent()
        "python3 -c ${shellQuote(py)} 2>/dev/null || true"
    }

    fun parseDump(raw: String): List<SessionOccupancy> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) return@mapNotNull null
            try {
                val obj = json.parseToJsonElement(trimmed) as? JsonObject ?: return@mapNotNull null
                val pid = obj["pid"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                SessionOccupancy(
                    pid = pid,
                    sessionFile = obj["sessionFile"]?.jsonPrimitive?.contentOrNull,
                    inbox = obj["inbox"]?.jsonPrimitive?.contentOrNull,
                    mode = obj["mode"]?.jsonPrimitive?.contentOrNull,
                )
            } catch (_: Exception) {
                null
            }
        }.toList()
    }

    fun pathsLookSame(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        if (a == b) return true
        fun norm(p: String) = p.removePrefix("~/").trimEnd('/')
        return norm(a) == norm(b)
    }

    fun tuiOccupancyFor(sessionFile: String?, records: List<SessionOccupancy>): SessionOccupancy? {
        if (sessionFile.isNullOrBlank()) return null
        val matches = records.filter { pathsLookSame(it.sessionFile, sessionFile) }
        return matches.firstOrNull { it.mode == "tui" && !it.inbox.isNullOrBlank() }
            ?: matches.firstOrNull { it.mode == "tui" }
    }

    fun isTuiOccupied(sessionFile: String?, records: List<SessionOccupancy>): Boolean =
        tuiOccupancyFor(sessionFile, records)?.inbox?.isNotBlank() == true

    fun statusLabel(sessionFile: String?, records: List<SessionOccupancy>): String {
        val occ = tuiOccupancyFor(sessionFile, records)
        return if (occ != null) "Occupied · TUI pid ${occ.pid}" else "Idle"
    }

    fun inboxSteerCommand(inboxPath: String, text: String): String {
        val payload = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        val py =
            "import json,socket,base64,sys; p=sys.argv[1]; t=base64.b64decode(sys.argv[2]).decode(); " +
                "b=json.dumps({\"source\":\"pipilot\",\"text\":t}).encode(); " +
                "s=socket.socket(socket.AF_UNIX); s.connect(p); s.sendall(b); s.shutdown(socket.SHUT_WR); s.close(); print(\"OK\")"
        return "python3 -c ${shellQuote(py)} ${shellQuote(inboxPath)} ${shellQuote(payload)}"
    }

    /** Ask the TUI owning [inboxPath] to quit so the app can take over its session. */
    enum class TakeoverReply {
        /** TUI was idle and is shutting down now. */
        QUIT_NOW,
        /** TUI is busy; the quit is queued and fires after the current tool. */
        QUEUED,
        /** No usable reply (socket error, bridge too old, noise). */
        FAILED,
    }

    fun parseTakeoverReply(raw: String): TakeoverReply = when {
        raw.contains("OK:quit") -> TakeoverReply.QUIT_NOW
        raw.contains("OK:queued") -> TakeoverReply.QUEUED
        else -> TakeoverReply.FAILED
    }

    /**
     * Remote quit: send {"source":"pipilot","command":"quit"} to the inbox socket,
     * then read the bridge's one-line reply ("OK:quit" / "OK:queued").
     *
     * The client deliberately does NOT half-close (SHUT_WR) before reading:
     * the bridge replies on the still-open socket, which works on every
     * runtime (half-close + allowHalfOpen reply proved unreliable — some
     * runtimes destroy the socket on FIN, so the reply was silently lost
     * while the TUI still exited). The socket timeout guards against old
     * bridges that never reply (runQuick has its own 15s timeout as the
     * outer bound).
     */
    fun takeoverCommand(inboxPath: String): String {
        val py =
            "import json,socket,sys; p=sys.argv[1]; " +
                "b=json.dumps({\"source\":\"pipilot\",\"command\":\"quit\"}).encode(); " +
                "s=socket.socket(socket.AF_UNIX); s.settimeout(10); s.connect(p); s.sendall(b)\n" +
                "try:\n print(s.makefile().readline().strip())\nexcept Exception:\n print(\"ERR:timeout\")\n" +
                "s.close()"
        return "python3 -c ${shellQuote(py)} ${shellQuote(inboxPath)}"
    }

    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Derive a screen session name for handing a session back to a TUI.
     * e.g. ".../a1b2c3d4e5f6.jsonl" -> "pipilot-back-a1b2c3d4e5f6".
     */
    fun handbackScreenName(sessionFile: String): String {
        val base = sessionFile.substringAfterLast('/').removeSuffix(".jsonl")
            .filter { it.isLetterOrDigit() }.take(12)
        return "pipilot-back-" + base.ifBlank { "x" }
    }

    /**
     * Spawn a detached screen running `pi --session <file>` so the user can
     * reattach a TUI to this session (`pi --session` accepts a file path
     * directly). Any stale screen with the same name is replaced.
     * The caller polls occupancy for the new TUI record to confirm takeover.
     * The screen self-destructs when pi exits (`screen -X quit` targets the
     * current session), so a later takeover does not leave a stale screen.
     */
    fun handbackCommand(
        sessionFile: String,
        screenName: String = handbackScreenName(sessionFile),
    ): String {
        val q = shellQuote(screenName)
        val inner = "pi --session ${shellQuote(sessionFile)}; screen -X quit"
        return "screen -S $q -X quit 2>/dev/null; screen -dmS $q bash -c ${shellQuote(inner)}"
    }

    /**
     * Best-effort cleanup of a hand-back screen (e.g. after takeover shut the
     * TUI down). Harmless if the screen is already gone.
     */
    fun killHandbackScreenCommand(screenName: String): String {
        return "screen -S ${shellQuote(screenName)} -X quit 2>/dev/null; true"
    }
}

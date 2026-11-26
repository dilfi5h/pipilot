#!/usr/bin/env python3
"""Turn pi session jsonl dumps into ChatItem-shaped fixtures for the HTML mock."""

from __future__ import annotations

import json
import re
from datetime import datetime, timezone, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SESSIONS = ROOT / "sessions"
SHANGHAI = timezone(timedelta(hours=8))

PREVIEW_MAX = 160
EXPANDED_MAX = 32_000


def first_non_blank_line(text: str) -> str:
    for line in text.splitlines():
        if line.strip():
            return line.strip()
    return ""


def last_non_blank_line(text: str) -> str:
    for line in reversed(text.splitlines()):
        if line.strip():
            return line.strip()
    return ""


def clip_preview(line: str, from_end: bool, max_chars: int = PREVIEW_MAX) -> str:
    if len(line) <= max_chars:
        return line
    return ("…" + line[-(max_chars - 1) :]) if from_end else (line[: max_chars - 1] + "…")


def extract_args(args) -> str | None:
    if args is None:
        return None
    if isinstance(args, dict):
        for key in ("command", "path", "file_path"):
            val = args.get(key)
            if isinstance(val, str) and val:
                return val
        dumped = json.dumps(args, ensure_ascii=False)
        return dumped[:300]
    if isinstance(args, str):
        return args[:300]
    return str(args)[:300]


def block_text(block: dict) -> str:
    return block.get("text") or block.get("thinking") or ""


def content_text(content) -> str:
    if content is None:
        return ""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for b in content:
            if isinstance(b, dict):
                parts.append(block_text(b))
            elif isinstance(b, str):
                parts.append(b)
        return "".join(parts)
    return str(content)


def format_shanghai(ms: int | None) -> str:
    if not ms:
        return ""
    seconds = ms / 1000 if ms > 10**12 else ms
    return datetime.fromtimestamp(seconds, SHANGHAI).strftime("%H:%M:%S")


def parse_file(path: Path) -> dict:
    items: list[dict] = []
    meta = {"file": path.name, "cwd": "", "model": "", "thinkingLevel": "", "title": path.stem}
    # Session-cumulative token totals, mirroring the TUI cache-hit footer extension:
    # assistant + toolResult + compaction/branch-summary usage, plus standalone `usage` entries.
    usage = {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0, "cost": 0.0, "requests": 0}

    def add_usage(u) -> None:
        if not isinstance(u, dict):
            return
        for key in ("input", "output", "cacheRead", "cacheWrite"):
            value = u.get(key)
            if isinstance(value, (int, float)):
                usage[key] += int(value)
        cost = (u.get("cost") or {}).get("total") if isinstance(u.get("cost"), dict) else None
        if isinstance(cost, (int, float)):
            usage["cost"] += float(cost)

    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw.strip():
            continue
        obj = json.loads(raw)
        kind = obj.get("type")
        if kind == "session":
            meta["cwd"] = obj.get("cwd") or ""
            meta["id"] = obj.get("id") or ""
            continue
        if kind == "model_change":
            meta["model"] = f"{obj.get('provider') or ''}/{obj.get('modelId') or ''}".strip("/")
            continue
        if kind == "thinking_level_change":
            meta["thinkingLevel"] = obj.get("thinkingLevel") or ""
            continue
        if kind == "usage":
            add_usage(obj.get("usage"))
            continue
        if kind in ("compaction", "branch_summary"):
            add_usage(obj.get("usage"))
            continue
        if kind != "message":
            continue
        msg = obj.get("message") or {}
        role = msg.get("role")
        ts = msg.get("timestamp") or 0
        entry_id = obj.get("id") or str(len(items))
        if role == "system":
            continue
        if role == "user":
            text = content_text(msg.get("content"))
            image_count = 0
            if isinstance(msg.get("content"), list):
                image_count = sum(1 for b in msg["content"] if isinstance(b, dict) and b.get("type") == "image")
            if text.strip() or image_count:
                if not meta.get("firstUser"):
                    meta["firstUser"] = text.strip()[:48] or f"{image_count} image(s)"
                items.append(
                    {
                        "kind": "user",
                        "key": f"hist-u-{entry_id}",
                        "text": text if text.strip() else f"({image_count} image(s))",
                        "imageCount": image_count,
                        "time": format_shanghai(ts),
                    }
                )
            continue
        if role == "assistant":
            add_usage(msg.get("usage"))
            usage["requests"] += 1
            content = msg.get("content") or []
            texts: list[str] = []
            thinkings: list[str] = []
            tool_calls: list[dict] = []
            if isinstance(content, str):
                texts.append(content)
            elif isinstance(content, list):
                for b in content:
                    if not isinstance(b, dict):
                        continue
                    bt = b.get("type")
                    if bt == "text":
                        texts.append(b.get("text") or "")
                    elif bt == "thinking":
                        thinkings.append(b.get("thinking") or b.get("text") or "")
                    elif bt == "toolCall":
                        tool_calls.append(b)
            body = "".join(texts)
            thinking = "".join(thinkings)
            if body or thinking:
                items.append(
                    {
                        "kind": "assistant",
                        "key": f"hist-a-{entry_id}",
                        "text": body,
                        "thinking": thinking or None,
                        "streaming": False,
                        "time": format_shanghai(ts),
                    }
                )
            for tc in tool_calls:
                raw_args = tc.get("arguments")
                args = extract_args(raw_args)
                args_json = (
                    json.dumps(raw_args, ensure_ascii=False)
                    if isinstance(raw_args, (dict, list))
                    else None
                )
                items.append(
                    {
                        "kind": "tool",
                        "key": f"tool-{tc.get('id') or len(items)}",
                        "toolCallId": tc.get("id") or "",
                        "toolName": tc.get("name") or "?",
                        "args": args,
                        "argsJson": args_json,
                        "output": None,
                        "running": False,
                        "isError": False,
                    }
                )
            continue
        if role == "toolResult":
            add_usage(msg.get("usage"))
            out = content_text(msg.get("content"))
            tool_call_id = msg.get("toolCallId")
            is_error = bool(msg.get("isError"))
            for item in reversed(items):
                if item.get("kind") == "tool" and item.get("toolCallId") == tool_call_id:
                    item["output"] = out
                    item["running"] = False
                    item["isError"] = is_error
                    break
            continue
        if role == "bashExecution":
            items.append(
                {
                    "kind": "bash",
                    "key": f"hist-bash-{entry_id}",
                    "command": msg.get("command"),
                    "output": msg.get("output") or content_text(msg.get("content")),
                    "running": False,
                }
            )
    meta["title"] = meta.get("firstUser") or meta["file"]
    meta["count"] = len(items)
    prompt_tokens = usage["input"] + usage["cacheRead"] + usage["cacheWrite"]
    if prompt_tokens or usage["output"] or usage["requests"]:
        meta["usage"] = {
            **usage,
            "cost": round(usage["cost"], 6),
            "total": usage["input"] + usage["output"] + usage["cacheRead"] + usage["cacheWrite"],
            "cht": (usage["cacheRead"] / prompt_tokens * 100) if prompt_tokens else None,
        }
    return {"meta": meta, "items": items}


def main() -> None:
    sessions = []
    for path in sorted(SESSIONS.glob("*.jsonl")):
        sessions.append(parse_file(path))
    if not sessions:
        raise SystemExit(f"no jsonl files in {SESSIONS}")
    payload = "window.SESSIONS = " + json.dumps(sessions, ensure_ascii=False, indent=2) + ";\n"
    (ROOT / "fixtures.js").write_text(payload, encoding="utf-8")
    print(f"wrote {ROOT / 'fixtures.js'} ({len(sessions)} sessions, {sum(s['meta']['count'] for s in sessions)} items)")
    for s in sessions:
        m = s["meta"]
        kinds = {}
        for it in s["items"]:
            kinds[it["kind"]] = kinds.get(it["kind"], 0) + 1
        u = m.get("usage")
        cht = "?" if u and u["cht"] is None else (round(u["cht"], 1) if u else None)
        tokens = (
            f"in={u['input']} cacheR={u['cacheRead']} cacheW={u['cacheWrite']} out={u['output']} "
            f"total={u['total']} cht={cht}%"
            if u
            else "usage=none"
        )
        print(f"  {m['title']!r}  {m['model']}  items={m['count']}  {kinds}  {tokens}")


if __name__ == "__main__":
    main()

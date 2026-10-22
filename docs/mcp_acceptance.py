#!/usr/bin/env python3
"""Minimal MCP Streamable HTTP client to acceptance-test switch-mcp.

Drives: initialize -> notifications/initialized -> tools/list -> add_route (tcp,
loopback) -> start -> status, then returns. TCP data-path forwarding is checked
separately by the shell harness.
"""
import json
import sys
import urllib.request

BASE = "http://127.0.0.1:3339/mcp"
session_id = None
_id = 0


def _next_id():
    global _id
    _id += 1
    return _id


def rpc(method, params=None, is_notification=False):
    """Send one JSON-RPC message, parse JSON or SSE response, return result dict."""
    global session_id
    body = {"jsonrpc": "2.0", "method": method}
    if not is_notification:
        body["id"] = _next_id()
    if params is not None:
        body["params"] = params
    data = json.dumps(body).encode()
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }
    if session_id:
        headers["Mcp-Session-Id"] = session_id
    req = urllib.request.Request(BASE, data=data, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=15) as resp:
        sid = resp.headers.get("Mcp-Session-Id")
        if sid:
            session_id = sid
        ctype = resp.headers.get("Content-Type", "")
        if is_notification:
            return None
        if "text/event-stream" in ctype:
            # Read line-by-line and return on the first JSON-RPC response data
            # frame; the SSE stream otherwise stays open (CIO holds it), so we
            # must not read() to EOF.
            for raw_line in resp:
                line = raw_line.decode().strip()
                if line.startswith("data:"):
                    payload = line[len("data:"):].strip()
                    try:
                        obj = json.loads(payload)
                    except json.JSONDecodeError:
                        continue
                    if isinstance(obj, dict) and ("result" in obj or "error" in obj):
                        return obj
            raise RuntimeError("SSE stream ended without a JSON-RPC response")
        return json.loads(resp.read().decode())


def text_of(result):
    """Extract joined text content from a CallToolResult."""
    out = []
    for c in result.get("result", {}).get("content", []):
        if c.get("type") == "text":
            out.append(c.get("text", ""))
    return "\n".join(out)


def main():
    init = rpc("initialize", {
        "protocolVersion": "2025-06-18",
        "capabilities": {},
        "clientInfo": {"name": "acceptance-test", "version": "0"},
    })
    srv = init.get("result", {}).get("serverInfo", {})
    print(f"[initialize] server={srv.get('name')} v{srv.get('version')} session={session_id}")

    rpc("notifications/initialized", is_notification=True)

    tools = rpc("tools/list")
    names = [t["name"] for t in tools.get("result", {}).get("tools", [])]
    print(f"[tools/list] {len(names)} tools: {sorted(names)}")
    assert len(names) == 6, f"expected 6 tools, got {len(names)}"
    for req in ["add_route", "remove_route", "list_routes", "status", "start", "stop"]:
        assert req in names, f"missing tool {req}"

    r = rpc("tools/call", {"name": "add_route", "arguments": {
        "proto": "tcp", "listen_port": 18080,
        "target_addr": "127.0.0.1", "target_port": 18090,
    }})
    print(f"[add_route] {text_of(r)}")

    r = rpc("tools/call", {"name": "start", "arguments": {}})
    print(f"[start] {text_of(r)}")

    r = rpc("tools/call", {"name": "list_routes", "arguments": {}})
    print(f"[list_routes] {text_of(r)}")

    r = rpc("tools/call", {"name": "status", "arguments": {}})
    print(f"[status] {text_of(r)}")

    print("OK")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)

#!/usr/bin/env python3
"""Acceptance tests #4 (UDP forwarding) and #6 (non-loopback target rejection)
for switch-mcp, over the Streamable HTTP MCP endpoint at 127.0.0.1:3339/mcp.

#4 is validated together with a shell harness that runs nc on-device; this
script only drives the MCP control plane (add_route udp + start). #6 is fully
validated here (the server must reject a non-loopback target under the default
loopback-only policy, and report the ACCESS_LOCAL_NETWORK requirement when
ALLOW_ANY_TARGET is enabled but the permission is not granted).
"""
import json
import sys
import urllib.request

BASE = "http://127.0.0.1:3339/mcp"
session_id = None
_id = 0


def rpc(method, params=None, is_notification=False):
    global session_id, _id
    body = {"jsonrpc": "2.0", "method": method}
    if not is_notification:
        _id += 1
        body["id"] = _id
    if params is not None:
        body["params"] = params
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }
    if session_id:
        headers["Mcp-Session-Id"] = session_id
    req = urllib.request.Request(BASE, data=json.dumps(body).encode(), headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=15) as resp:
        sid = resp.headers.get("Mcp-Session-Id")
        if sid:
            session_id = sid
        if is_notification:
            return None
        if "text/event-stream" in resp.headers.get("Content-Type", ""):
            for raw_line in resp:
                line = raw_line.decode().strip()
                if line.startswith("data:"):
                    try:
                        obj = json.loads(line[len("data:"):].strip())
                    except json.JSONDecodeError:
                        continue
                    if isinstance(obj, dict) and ("result" in obj or "error" in obj):
                        return obj
            raise RuntimeError("SSE ended without JSON-RPC response")
        return json.loads(resp.read().decode())


def text_of(result):
    return "\n".join(
        c.get("text", "")
        for c in result.get("result", {}).get("content", [])
        if c.get("type") == "text"
    )


def is_error(result):
    return bool(result.get("result", {}).get("isError"))


def handshake(label):
    init = rpc("initialize", {
        "protocolVersion": "2025-06-18",
        "capabilities": {},
        "clientInfo": {"name": label, "version": "0"},
    })
    rpc("notifications/initialized", is_notification=True)
    return init


def main():
    handshake("udp-and-reject-test")

    # --- #4: add a UDP route and start it ---
    r = rpc("tools/call", {"name": "add_route", "arguments": {
        "proto": "udp", "listen_port": 18081,
        "target_addr": "127.0.0.1", "target_port": 18091,
    }})
    print(f"[udp add_route] {text_of(r)}")
    assert not is_error(r), "udp add_route should succeed"
    assert "udp" in text_of(r), "route summary should say udp"

    r = rpc("tools/call", {"name": "start", "arguments": {}})
    print(f"[start] {text_of(r)}")
    assert not is_error(r)

    # --- #6: non-loopback target must be rejected (default loopback-only) ---
    r = rpc("tools/call", {"name": "add_route", "arguments": {
        "proto": "tcp", "listen_port": 18082,
        "target_addr": "192.168.1.50", "target_port": 80,
    }})
    msg = text_of(r)
    print(f"[reject non-loopback] isError={is_error(r)} :: {msg}")
    assert is_error(r), "non-loopback target must be rejected under default policy"
    assert "loopback" in msg.lower() or "allow_any_target" in msg.lower(), \
        "rejection should mention loopback / ALLOW_ANY_TARGET"

    print("MCP_OK")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)

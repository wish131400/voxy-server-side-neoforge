#!/usr/bin/env python3
"""Read-only MCP stdio bridge for VSS performance snapshots. Python 3.10+, stdlib only."""
import argparse
import http.client
import json
from pathlib import Path
import sys

TOOLS = [
    ("vss_get_status", "/status", "Read capture state, age and latest FPS/CPU/loading/stage metrics."),
    ("vss_get_samples", "/samples", "Read recent gameplay intervals (up to 600); labels and modes support comparisons."),
    ("vss_get_hotspots", "/hotspots", "Read measured stage hotspots, evidence hints and labelled FPS summaries. Hints do not prove causality."),
    ("vss_get_report", "/report", "Read the complete bounded performance report, including measurement limits."),
]


def tool_list():
    result = []
    for name, _, description in TOOLS:
        schema = {"type": "object", "properties": {}, "additionalProperties": False}
        if name == "vss_get_samples":
            schema["properties"]["limit"] = {"type": "integer", "minimum": 1, "maximum": 600, "default": 60}
        result.append({"name": name, "description": description, "inputSchema": schema,
                       "annotations": {"readOnlyHint": True, "destructiveHint": False,
                                       "idempotentHint": True, "openWorldHint": False}})
    return result


def query(session_path, report_path, path, limit):
    if report_path:
        report = json.loads(Path(report_path).read_text(encoding="utf-8"))
        if not isinstance(report, dict):
            raise ValueError("Invalid performance report")
        if path == "/status":
            return {"offline": True, "capturing": False, "sampling": False,
                    "latest": report.get("latest", {}), "created": report.get("created")}
        if path == "/samples":
            return {"offline": True, "samples": report.get("samples", [])[-limit:]}
        if path == "/hotspots":
            return {"offline": True, "latest": report.get("latest", {}), "summary": report.get("summary", [])}
        return report
    session = json.loads(Path(session_path).read_text(encoding="utf-8"))
    if not isinstance(session, dict):
        raise ValueError("Invalid session descriptor")
    port, token = session.get("port"), session.get("token")
    if session.get("host") != "127.0.0.1" or type(port) is not int or not 1 <= port <= 65535:
        raise ValueError("Invalid VSS loopback session descriptor")
    if not isinstance(token, str) or not token or len(token) > 128 or not token.isascii() or not all(c.isalnum() or c == "-" for c in token):
        raise ValueError("Invalid VSS session token")
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
    try:
        url = path + (f"?limit={limit}" if path == "/samples" else "")
        connection.request("GET", url, headers={"Authorization": "Bearer " + token})
        response = connection.getresponse()
        if response.status != 200:
            raise ValueError(f"VSS diagnostic service returned HTTP {response.status}; restart capture if the session is stale")
        return json.loads(response.read(16 * 1024 * 1024))
    finally:
        connection.close()


def dispatch(request, session_path, report_path):
    """Return a JSON-RPC response, or None for notifications. Never mutate the game."""
    if not isinstance(request, dict):
        return {"jsonrpc": "2.0", "id": None, "error": {"code": -32600, "message": "Invalid request"}}
    if "id" not in request:
        return None
    request_id, method = request["id"], request.get("method")

    def ok(result):
        return {"jsonrpc": "2.0", "id": request_id, "result": result}

    def error(code, message):
        return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}

    params = request.get("params", {})
    if request.get("jsonrpc") != "2.0" or not isinstance(params, dict):
        return error(-32600, "Invalid request")
    if method == "initialize":
        version = params.get("protocolVersion")
        supported = {"2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"}
        return ok({"protocolVersion": version if isinstance(version, str) and version in supported else "2025-11-25",
                   "capabilities": {"tools": {"listChanged": False}},
                   "serverInfo": {"name": "vss-performance", "version": "1.0.0"},
                   "instructions": "Read-only VSS diagnostics. Use sample timestamps; compare matching scenes. CPU stages may nest; GPU samples are VSS pass time, not hardware utilization."})
    if method == "ping":
        return ok({})
    if method == "tools/list":
        return ok({"tools": tool_list()})
    if method != "tools/call":
        return error(-32601, "Method not found")
    name, arguments = params.get("name"), params.get("arguments", {})
    match = next((tool for tool in TOOLS if tool[0] == name), None)
    if match is None or not isinstance(arguments, dict):
        return error(-32602, "Unknown tool or invalid arguments")
    allowed = {"limit"} if name == "vss_get_samples" else set()
    limit = arguments.get("limit", 60)
    if set(arguments) - allowed or type(limit) is not int or not 1 <= limit <= 600:
        return error(-32602, "Invalid arguments; sample limit must be 1..600")
    try:
        data = query(session_path, report_path, match[1], limit)
        return ok({"content": [{"type": "text", "text": json.dumps(data, ensure_ascii=False)}], "isError": False})
    except (OSError, ValueError, http.client.HTTPException) as failure:
        # Do not echo descriptor contents or bearer tokens.
        detail = ("Session/report unavailable. Run /vssclient prediction perf start and check --session, "
                  "or select an exported JSON with --report. " + type(failure).__name__)
        return ok({"content": [{"type": "text", "text": detail}], "isError": True})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--session", help="Game directory/debug/vss-performance/session.json")
    source.add_argument("--report", help="Exported performance-UUID.json for offline analysis")
    args = parser.parse_args()
    sys.stdin.reconfigure(encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8", newline="\n")
    for line in sys.stdin:
        try:
            if len(line) > 1_048_576:
                raise ValueError("Request too large")
            response = dispatch(json.loads(line), args.session, args.report)
        except (json.JSONDecodeError, ValueError):
            response = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}}
        if response is not None:
            print(json.dumps(response, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()

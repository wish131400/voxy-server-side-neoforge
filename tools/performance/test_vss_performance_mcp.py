"""MCP protocol and loopback integration tests; no game or third-party packages required."""
import http.server
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

import vss_performance_mcp as mcp


class ProtocolTest(unittest.TestCase):
    def request(self, method, params=None, session=None, report=None):
        return mcp.dispatch({"jsonrpc": "2.0", "id": 7, "method": method, "params": params or {}}, session, report)

    def test_initialize_list_and_read_only_tool_schema(self):
        reply = self.request("initialize", {"protocolVersion": "2025-11-25"})["result"]
        self.assertEqual("2025-11-25", reply["protocolVersion"])
        tools = self.request("tools/list")["result"]["tools"]
        self.assertEqual(4, len(tools))
        self.assertTrue(all(tool["annotations"]["readOnlyHint"] for tool in tools))
        self.assertIsNone(mcp.dispatch({"jsonrpc": "2.0", "method": "notifications/initialized"}, None, None))

    def test_invalid_arguments_are_rejected_without_accessing_game(self):
        for limit in [0, 601, True, "60"]:
            reply = self.request("tools/call", {"name": "vss_get_samples", "arguments": {"limit": limit}})
            self.assertEqual(-32602, reply["error"]["code"])
        self.assertEqual(-32602, self.request("tools/call", {"name": "set_workers"})["error"]["code"])

    def test_unavailable_capture_is_tool_error_and_does_not_kill_server(self):
        reply = self.request("tools/call", {"name": "vss_get_status"}, session="nonexistent-session.json")
        self.assertTrue(reply["result"]["isError"])
        self.assertIn("result", self.request("ping"))

    def test_offline_report_and_real_stdio_framing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            path.write_text(json.dumps({"latest": {"fps": 25}, "samples": [{"label": "加载", "fps": 25}]}), encoding="utf-8")
            requests = [
                {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-03-26"}},
                {"jsonrpc": "2.0", "method": "notifications/initialized"},
                {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "vss_get_samples", "arguments": {"limit": 1}}},
            ]
            process = subprocess.run([sys.executable, str(Path(mcp.__file__)), "--report", str(path)],
                                     input="\n".join(json.dumps(row) for row in requests) + "\n",
                                     capture_output=True, encoding="utf-8", timeout=10)
            self.assertEqual(0, process.returncode, process.stderr)
            replies = [json.loads(line) for line in process.stdout.splitlines()]
            self.assertEqual([1, 2], [r["id"] for r in replies])
            data = json.loads(replies[1]["result"]["content"][0]["text"])
            self.assertEqual("加载", data["samples"][0]["label"])

    def test_live_loopback_token_and_session_rotation(self):
        observed = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                observed.append((self.path, self.headers.get("Authorization")))
                payload = json.dumps({"samples": [{"fps": 25}]}).encode()
                self.send_response(200); self.send_header("Content-Length", str(len(payload))); self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):
                pass

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "session.json"
                for token in ["first-token", "rotated-token"]:
                    path.write_text(json.dumps({"host": "127.0.0.1", "port": server.server_port, "token": token}), encoding="utf-8")
                    reply = self.request("tools/call", {"name": "vss_get_samples", "arguments": {"limit": 2}}, session=path)
                    self.assertFalse(reply["result"]["isError"])
                    self.assertEqual(("/samples?limit=2", "Bearer " + token), observed[-1])
                path.write_text(json.dumps({"host": "example.org", "port": 80, "token": "x"}), encoding="utf-8")
                self.assertTrue(self.request("tools/call", {"name": "vss_get_status"}, session=path)["result"]["isError"])
        finally:
            server.shutdown(); server.server_close(); thread.join(timeout=5)


if __name__ == "__main__":
    unittest.main()

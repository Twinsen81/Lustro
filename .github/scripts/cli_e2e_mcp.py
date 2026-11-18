"""Drives `lustro mcp` as an MCP client does, against the debug sample on a device.

Usage: cli_e2e_mcp.py OUT_DIR COMMAND...

COMMAND starts the server, such as `lustro mcp`. adb finds the device as it does
for the other steps, through ANDROID_SERIAL when there are several. The script
talks to the server over its stdin and stdout, one JSON-RPC message per line:
the legacy handshake (initialize), then the tools: it adds a mock rule, makes the
sample send the request that the rule answers, finds that request in
list_requests and reads it with get_request, and deletes the rule. Then it sends
modern requests (protocol 2026-07-28), which carry the version in _meta. Each
message and reply goes to OUT_DIR/mcp-transcript.jsonl. It exits 1 at the first
check that fails.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time

PACKAGE = "io.github.twinsen81.lustro.sample"
RULE_ID = "cli-e2e-mcp"
URL = "https://httpbingo.org/get"
MOCKED_BODY = '{"mockedBy":"lustro mcp"}'
MODERN_META = {
    "io.modelcontextprotocol/protocolVersion": "2026-07-28",
    "io.modelcontextprotocol/clientCapabilities": {},
    "io.modelcontextprotocol/clientInfo": {"name": "cli-e2e", "version": "1"},
}


def fail(message):
    print("error: " + message, file=sys.stderr)
    sys.exit(1)


def step(message):
    print("\n==> " + message, flush=True)


class Session:
    def __init__(self, command, transcript):
        self.proc = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        self.transcript = transcript
        self.last_id = 0

    def _send(self, message):
        line = json.dumps(message)
        self.transcript.write(json.dumps({"sent": message}) + "\n")
        self.proc.stdin.write(line.encode("utf-8") + b"\n")
        self.proc.stdin.flush()

    def notify(self, method):
        self._send({"jsonrpc": "2.0", "method": method})

    def request(self, method, params=None):
        self.last_id += 1
        message = {"jsonrpc": "2.0", "id": self.last_id, "method": method}
        if params is not None:
            message["params"] = params
        self._send(message)
        line = self.proc.stdout.readline()
        if not line:
            fail("lustro mcp closed its stdout; its exit status is {}".format(self.proc.poll()))
        reply = json.loads(line)
        self.transcript.write(json.dumps({"received": reply}) + "\n")
        if reply.get("id") != self.last_id:
            fail("the reply has id {}, expected {}".format(reply.get("id"), self.last_id))
        if "error" in reply:
            fail("{} failed: {}".format(method, reply["error"]))
        return reply["result"]

    def call(self, tool, meta=None, **arguments):
        params = {"name": tool, "arguments": arguments}
        if meta is not None:
            params["_meta"] = meta
        result = self.request("tools/call", params)
        text = result["content"][0]["text"]
        print("{}({}):\n{}".format(tool, json.dumps(arguments), text if len(text) < 1500 else text[:1500] + " ..."))
        if result.get("isError"):
            fail("the tool {} failed".format(tool))
        return text

    def close(self):
        self.proc.stdin.close()
        try:
            return self.proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            fail("lustro mcp did not exit within 10 s after its stdin closed")


def fire_sample_request():
    """Makes the sample send its GET /get request, as a tap on its button does."""
    subprocess.run(
        [
            "adb", "shell", "am", "start", "-W", "--activity-single-top",
            "-n", PACKAGE + "/.MainActivity", "--es", "request", "'GET /get'",
        ],
        check=True,
        stdout=subprocess.DEVNULL,
    )


def mocked_row(session):
    """The newest row of the sample's GET /get that the rule answered, or None."""
    for line in session.call("list_requests", url=URL, last=5).splitlines()[1:]:
        if "[mock]" in line:
            return line
    return None


def main():
    out_dir, command = sys.argv[1], sys.argv[2:]
    with open(os.path.join(out_dir, "mcp-transcript.jsonl"), "w") as transcript:
        session = Session(command, transcript)

        step("initialize, then tools/list")
        init = session.request(
            "initialize",
            {"protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": {"name": "cli-e2e", "version": "1"}},
        )
        if init["protocolVersion"] != "2025-11-25" or "tools" not in init["capabilities"]:
            fail("unexpected initialize result: {}".format(init))
        session.notify("notifications/initialized")
        tools = session.request("tools/list")["tools"]
        for tool in tools:
            print("{:<17} readOnlyHint={}".format(tool["name"], tool["annotations"]["readOnlyHint"]))
            if not isinstance(tool["annotations"]["readOnlyHint"], bool):
                fail("the tool {} does not say whether it changes the app".format(tool["name"]))

        step("add_mock_rule, then list_mock_rules")
        session.call(
            "add_mock_rule",
            id=RULE_ID,
            name="CLI end to end, MCP",
            url_pattern=URL,
            method="GET",
            status=200,
            body=MOCKED_BODY,
            headers={"Content-Type": "application/json"},
        )
        rules = json.loads(session.call("list_mock_rules"))["items"]
        if not rules or rules[0]["id"] != RULE_ID:
            fail("the new rule is not the first rule")

        step("the sample sends GET /get; list_requests shows it, answered by the rule")
        fire_sample_request()
        row = None
        for _ in range(30):
            row = mocked_row(session)
            if row:
                break
            time.sleep(1)
        if not row:
            fail("list_requests shows no mocked request to {} within 30 s".format(URL))

        step("get_request with the row's short id")
        tx = json.loads(session.call("get_request", id=row.split()[0]))
        if tx.get("mockRuleId") != RULE_ID or tx.get("responseBody") != MOCKED_BODY:
            fail("the request was not answered by {}: {}".format(RULE_ID, tx.get("mockRuleId")))

        step("delete_mock_rule, then list_mock_rules")
        session.call("delete_mock_rule", id=RULE_ID)
        if any(rule["id"] == RULE_ID for rule in json.loads(session.call("list_mock_rules"))["items"]):
            fail("the rule is still there after delete_mock_rule")

        step("set_throttle, read_logs, get_schema, and call_api")
        session.call("set_throttle", delay_ms=0)
        if "LustroSample" not in session.call("read_logs", tag="^LustroSample$", limit=5):
            fail("read_logs did not return the sample's start-up line")
        tabs = [tab["id"] for tab in json.loads(session.call("get_schema"))["tabs"]]
        if "network" not in tabs:
            fail("get_schema lists no network tab: {}".format(tabs))
        if json.loads(session.call("get_schema", tab="logs")).get("openapi") is None:
            fail("get_schema tab=logs returned no OpenAPI document")
        session.call("call_api", method="GET", path="prefs/files")

        step("modern requests: server/discover, then a tool with the version in _meta")
        discovered = session.request("server/discover", {"_meta": MODERN_META})
        if "2026-07-28" not in discovered["supportedVersions"]:
            fail("server/discover does not list 2026-07-28: {}".format(discovered["supportedVersions"]))
        result = session.request("tools/call", {"name": "list_mock_rules", "arguments": {}, "_meta": MODERN_META})
        if result.get("resultType") != "complete" or result.get("isError"):
            fail("unexpected modern tools/call result: {}".format(result))

        status = session.close()
        if status != 0:
            fail("lustro mcp exited {} after its stdin closed".format(status))
    print("lustro mcp passed")


if __name__ == "__main__":
    main()

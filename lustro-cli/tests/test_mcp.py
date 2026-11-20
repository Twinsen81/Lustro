"""`lustro mcp`: the protocol messages of both eras, the argument checks, and the
route that each tool calls, against a local server that records each request."""

from __future__ import annotations

import argparse
import io
import json
import os
import stat
import subprocess
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

import lustro_cli
from lustro_cli import cli, mcp, wire

TOKEN = "test-token-123"
FULL_ID = "3f2a7c10-1b2c-4d5e-8f90-a1b2c3d4e5f6"
# The real ones: fixtures replace them in their modules for every test.
add_sdk_adb_to_path = mcp.add_sdk_adb_to_path
adb_forward = cli._adb_forward
MODERN_META = {
    mcp.META_PROTOCOL_VERSION: "2026-07-28",
    mcp.META_CLIENT_CAPABILITIES: {},
    "io.modelcontextprotocol/clientInfo": {"name": "test", "version": "1"},
}


LOG_TEXT = (
    "11-18 09:00:00.000  1234  1234 I Sync: started, with a café\n"
    "11-18 09:00:00.001  1234  1234 W Sync: slow\n"
)


def _args(**overrides):
    values = dict(cli.GLOBAL_DEFAULTS)
    values.update(overrides)
    return argparse.Namespace(**values)


@pytest.fixture(autouse=True)
def _keep_the_path(monkeypatch):
    """serve() puts the SDK's adb on the PATH of the process, which a test must not change."""
    monkeypatch.setattr(mcp, "add_sdk_adb_to_path", lambda environ=None: None)


def _serve(messages, args=None):
    """Run the server over ``messages``, one line each, and return its replies, one per line.
    A message that is a string goes as it is."""
    lines = [m if isinstance(m, str) else json.dumps(m) for m in messages]
    stdin = io.BytesIO("".join(line + "\n" for line in lines).encode("utf-8"))
    stdout = io.BytesIO()
    assert mcp.serve(args or _args(), stdin=stdin, stdout=stdout) == 0
    return [json.loads(line) for line in stdout.getvalue().decode("ascii").splitlines()]


def _request(method, params=None, request_id=1):
    message = {"jsonrpc": "2.0", "id": request_id, "method": method}
    if params is not None:
        message["params"] = params
    return message


# ── protocol: the legacy era ──────────────────────────────────────────────────


def test_initialize_answers_with_the_version_that_the_client_asked_for():
    (reply,) = _serve([_request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}})])
    result = reply["result"]
    assert reply["id"] == 1
    assert result["protocolVersion"] == "2025-06-18"
    assert result["capabilities"] == {"tools": {"listChanged": False}}
    assert result["serverInfo"]["name"] == "lustro"
    assert "list_requests" in result["instructions"]


def test_initialize_with_an_unknown_version_answers_with_the_newest_legacy_one():
    (reply,) = _serve([_request("initialize", {"protocolVersion": "2099-01-01", "capabilities": {}})])
    assert reply["result"]["protocolVersion"] == mcp.LEGACY_VERSIONS[0]


def test_notifications_and_responses_get_no_reply():
    replies = _serve(
        [
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": 1}},
            {"jsonrpc": "2.0", "id": 7, "result": {}},
            _request("ping", request_id=2),
        ]
    )
    assert replies == [{"jsonrpc": "2.0", "id": 2, "result": {}}]


def test_a_legacy_result_has_only_the_fields_of_its_era():
    """The TypeScript SDK checks the empty result of ping strictly, and rejects resultType."""
    replies = _serve(
        [
            _request("initialize", {"protocolVersion": "2025-11-25", "capabilities": {}}, request_id=1),
            _request("ping", request_id=2),
            _request("tools/list", request_id=3),
            _request("tools/call", {"name": "set_throttle", "arguments": {}}, request_id=4),
        ]
    )
    results = [reply["result"] for reply in replies]
    assert set(results[0]) == {"protocolVersion", "capabilities", "serverInfo", "instructions"}
    assert results[1] == {}
    assert set(results[2]) == {"tools"}
    assert set(results[3]) == {"content", "isError"}


def test_tools_list_names_each_tool_and_whether_it_changes_the_app():
    (reply,) = _serve([_request("tools/list")])
    tools = reply["result"]["tools"]
    assert [tool["name"] for tool in tools] == [
        "list_requests",
        "get_request",
        "list_mock_rules",
        "add_mock_rule",
        "delete_mock_rule",
        "set_throttle",
        "read_logs",
        "call_api",
        "get_schema",
    ]
    read_only = {tool["name"] for tool in tools if tool["annotations"]["readOnlyHint"]}
    assert read_only == {"list_requests", "get_request", "list_mock_rules", "read_logs", "get_schema"}
    for tool in tools:
        assert tool["inputSchema"]["type"] == "object"
        assert tool["inputSchema"]["additionalProperties"] is False
        # The description says it too, for a client that doesn't show annotations.
        changes = not tool["annotations"]["readOnlyHint"]
        assert tool["description"].startswith(("Changes the app", "Can change the app") if changes else "Read-only")
        if changes:
            assert isinstance(tool["annotations"]["destructiveHint"], bool)


def test_the_tool_list_stays_small():
    """Every session of every client pays for the list in its context."""
    (reply,) = _serve([_request("tools/list")])
    assert len(json.dumps(reply["result"]["tools"])) < 9000


def test_a_tool_schema_uses_one_type_in_each_branch():
    """A client that maps tool schemas onto a dialect with one type, such as
    Gemini's function declarations, drops a list of types."""

    def walk(node):
        if isinstance(node, dict):
            assert not isinstance(node.get("type"), list), node
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    for tool in mcp.TOOLS:
        walk(tool.input_schema)


# ── protocol: the modern era ──────────────────────────────────────────────────


def test_server_discover_names_the_versions_capabilities_and_server():
    (reply,) = _serve([_request("server/discover", {"_meta": MODERN_META})])
    result = reply["result"]
    assert result["resultType"] == "complete"
    assert result["supportedVersions"][0] == "2026-07-28"
    assert set(mcp.LEGACY_VERSIONS) <= set(result["supportedVersions"])
    assert result["capabilities"] == {"tools": {"listChanged": False}}
    assert result["_meta"][mcp.META_SERVER_INFO]["name"] == "lustro"
    assert result["ttlMs"] > 0 and result["cacheScope"] == "public"


def test_server_discover_has_the_modern_fields_also_without_meta():
    (reply,) = _serve([_request("server/discover")])
    assert reply["result"]["resultType"] == "complete"
    assert reply["result"]["_meta"][mcp.META_SERVER_INFO]["name"] == "lustro"


def test_a_modern_request_gets_the_server_info_and_a_cacheable_tool_list():
    (reply,) = _serve([_request("tools/list", {"_meta": MODERN_META})])
    result = reply["result"]
    assert result["resultType"] == "complete"
    assert result["_meta"][mcp.META_SERVER_INFO]["version"]
    assert result["ttlMs"] > 0 and result["cacheScope"] == "public"


def test_an_unsupported_version_lists_the_supported_ones():
    meta = dict(MODERN_META, **{mcp.META_PROTOCOL_VERSION: "1900-01-01"})
    (reply,) = _serve([_request("server/discover", {"_meta": meta})])
    assert reply["error"]["code"] == -32022
    assert reply["error"]["data"] == {"supported": list(mcp.SUPPORTED_VERSIONS), "requested": "1900-01-01"}


def test_a_modern_request_without_client_capabilities_is_invalid():
    meta = {mcp.META_PROTOCOL_VERSION: "2026-07-28"}
    (reply,) = _serve([_request("tools/list", {"_meta": meta})])
    assert reply["error"]["code"] == -32602


# ── protocol: errors ──────────────────────────────────────────────────────────


def test_a_line_that_is_not_json_is_a_parse_error_and_the_server_goes_on():
    replies = _serve(["{not json", _request("ping", request_id=3)])
    assert replies[0]["id"] is None and replies[0]["error"]["code"] == -32700
    assert replies[1]["id"] == 3 and "result" in replies[1]


@pytest.mark.parametrize(
    "message, code",
    [
        ({"id": 1, "method": "ping"}, -32600),
        ({"jsonrpc": "2.0", "id": None, "method": "ping"}, -32600),
        ({"jsonrpc": "2.0", "id": 1, "method": 5}, -32600),
        ({"jsonrpc": "2.0", "id": 1, "method": "ping", "params": [1]}, -32602),
        ({"jsonrpc": "2.0", "id": 1, "method": "resources/list"}, -32601),
        ({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "nope"}}, -32602),
        (_request("tools/call", {"name": "list_requests", "arguments": [1]}), -32602),
    ],
)
def test_a_bad_request_gets_its_json_rpc_error(message, code):
    (reply,) = _serve([message])
    assert reply["error"]["code"] == code


def test_a_batch_gets_one_reply_for_each_request():
    (replies,) = _serve(
        [
            [
                _request("ping", request_id=1),
                {"jsonrpc": "2.0", "method": "notifications/initialized"},
                _request("ping", request_id=2),
            ]
        ]
    )
    assert [reply["id"] for reply in replies] == [1, 2]


def test_a_bug_in_a_tool_is_an_internal_error_and_the_server_goes_on(monkeypatch):
    def broken(args, arguments):
        raise RuntimeError("boom")

    tools = tuple(tool._replace(handler=broken) if tool.name == "list_mock_rules" else tool for tool in mcp.TOOLS)
    monkeypatch.setattr(mcp, "TOOLS", tools)
    replies = _serve([_request("tools/call", {"name": "list_mock_rules"}), _request("ping", request_id=2)])
    assert replies[0]["error"]["code"] == -32603
    assert replies[1]["id"] == 2


def test_a_print_in_a_tool_goes_to_stderr_not_into_the_protocol(monkeypatch, capsys):
    def chatty(args, arguments):
        print("a stray line")
        return "done"

    tools = tuple(tool._replace(handler=chatty) if tool.name == "list_mock_rules" else tool for tool in mcp.TOOLS)
    monkeypatch.setattr(mcp, "TOOLS", tools)
    (reply,) = _serve([_request("tools/call", {"name": "list_mock_rules"})])
    assert reply["result"]["content"] == [{"type": "text", "text": "done"}]
    assert "a stray line" in capsys.readouterr().err


# ── argument checks ───────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "tool, arguments, message",
    [
        ("set_throttle", {}, "missing argument: delay_ms"),
        ("set_throttle", {"delay_ms": -1}, "delay_ms must be at least 0"),
        ("set_throttle", {"delay_ms": 600001}, "delay_ms must be at most 600000"),
        ("set_throttle", {"delay_ms": "2000"}, "delay_ms must be of type integer, not str"),
        ("set_throttle", {"delay_ms": True}, "delay_ms must be of type integer, not bool"),
        ("set_throttle", {"delay_ms": 10, "delay": 10}, "unknown argument delay; the arguments are: delay_ms"),
        ("read_logs", {"level": "X"}, "level must be one of V, D, I, W, E, F"),
        ("add_mock_rule", {"url_pattern": "/x", "headers": {"A": 1}}, "headers.A must be of type string, not int"),
        ("add_mock_rule", {"url_pattern": "/x", "status": 600}, "status must be at most 599"),
        ("add_mock_rule", {"url_pattern": "/x", "failure": "timeout", "status": 500}, "failure fails the request"),
        ("call_api", {"method": "GET", "path": "x", "body": 5}, "body must be of type object or array or string"),
        ("call_api", {"method": "GET", "path": "x", "params": {"a": "1", "b": {}}}, "params.b must be of type"),
        ("call_api", {"method": "GET", "path": "http://example.com/"}, "PATH is a route"),
        ("call_api", {"method": "GET", "path": "x", "body": "{oops"}, "body is a string that is not JSON"),
        ("list_requests", {"regex": True}, "regex makes search a regular expression"),
        ("list_requests", {"status": "6xx"}, "status: "),
    ],
)
def test_a_bad_argument_is_a_tool_error_that_says_what_is_wrong(tool, arguments, message):
    (reply,) = _serve([_request("tools/call", {"name": tool, "arguments": arguments})])
    result = reply["result"]
    assert result["isError"] is True
    assert message in result["content"][0]["text"]


def test_a_null_argument_counts_as_missing_and_a_whole_float_as_an_integer():
    schema = mcp._schema({"a": {"type": "integer"}, "b": {"type": "string"}}, ("a",))
    assert mcp.check_arguments(schema, {"a": 2.0, "b": None}) == {"a": 2}
    with pytest.raises(mcp.ToolError):
        mcp.check_arguments(schema, {"a": 2.5})


# ── routes ────────────────────────────────────────────────────────────────────


class _Recorder(BaseHTTPRequestHandler):
    """Records each request, and answers it like the app."""

    requests = []
    # The protocolVersion that _meta answers, when not the one of the golden file.
    protocol = None

    def log_message(self, *args):  # silence
        pass

    def _send(self, status, body, content_type="application/json", warning=None):
        data = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        if warning:
            self.send_header("Lustro-Warning", warning)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _handle(self):
        if self.headers.get("Authorization") != "Bearer " + TOKEN:
            self._send(401, {"error": "unauthorized", "message": "missing token"})
            return
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length)) if length else None
        url = urllib.parse.urlsplit(self.path)
        self.requests.append((self.command, url.path, urllib.parse.parse_qsl(url.query), body))
        route = (self.command, url.path)
        if route == ("GET", "/api/v1/network/transactions"):
            listing = wire.load_golden("cursor-reset.json")
            listing["items"][0]["id"] = FULL_ID
            self._send(200, listing)
        elif route == ("GET", "/api/v1/network/transactions/" + FULL_ID):
            tx = wire.load_golden("transaction.json")
            tx["id"] = FULL_ID
            tx["responseBody"] = "x" * 5000
            self._send(200, tx)
        elif route == ("GET", "/api/v1/network/rules"):
            rules = wire.load_golden("rules-list.json")
            rules["items"][0]["responseBody"] = "y" * 3000
            self._send(200, rules)
        elif route == ("POST", "/api/v1/network/rules"):
            self._send(200, {"status": "ok", "id": body.get("id", "generated")})
        elif route == ("POST", "/api/v1/network/rules/delete"):
            self._send(200, {"status": "ok"})
        elif route == ("POST", "/api/v1/network/throttle"):
            self._send(200, {"status": "ok", "delayMs": body["delayMs"]})
        elif route == ("GET", "/api/v1/logs/text"):
            self._send(200, LOG_TEXT.encode("utf-8"), "text/plain; charset=utf-8")
        elif route == ("GET", "/api/v1/_meta"):
            meta = wire.load_golden("meta.json")
            self._send(200, dict(meta, protocolVersion=self.protocol or meta["protocolVersion"]))
        elif route == ("GET", "/api/v1/network/_schema"):
            self._send(200, {"openapi": "3.1.0", "info": {"title": "Network"}})
        elif route == ("GET", "/api/v1/screen/image"):
            self._send(200, b"\x89PNG\r\n\x1a\n\xff\xfe", "image/png")
        elif route == ("GET", "/api/v1/work/items"):
            names = [name for name, _ in urllib.parse.parse_qsl(url.query) if name != "name"]
            warning = ", ".join('unknown query parameter "{}"'.format(name) for name in names)
            if self.protocol and cli._version_tuple(self.protocol) < (1, 15):
                warning = None
            self._send(400 if "fail" in names else 200, {"items": []} if "fail" not in names else
                       {"error": "bad_request", "message": "no"}, warning=warning)
        elif route == ("GET", "/api/v1/big"):
            self._send(200, b"a" * 50, "text/plain")
        else:
            self._send(404, wire.load_golden("error-envelope.json"))

    do_GET = _handle
    do_POST = _handle


@pytest.fixture
def app(monkeypatch):
    _Recorder.requests = []
    monkeypatch.setattr(_Recorder, "protocol", None)
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), _Recorder)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    host, port = httpd.server_address
    args = _args(host=host, port=port, token=TOKEN)

    def call(tool, **arguments):
        (reply,) = _serve([_request("tools/call", {"name": tool, "arguments": arguments})], args)
        return reply["result"]

    call.requests = _Recorder.requests
    try:
        yield call
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=5)


def _text(result):
    assert result["isError"] is False, result
    return result["content"][0]["text"]


def test_list_requests_reads_the_transactions_and_prints_the_state_and_rows(app):
    text = _text(app("list_requests", search="orders", regex=True, method="get"))
    assert app.requests == [
        # regex reads the app's protocol first.
        ("GET", "/api/v1/_meta", [], None),
        ("GET", "/api/v1/network/transactions", [("search", "orders"), ("regex", "true")], None),
    ]
    lines = text.splitlines()
    assert lines[0] == "state: paused=false overwriteMode=false throttleDelayMs=0 enabledMockRules=1"
    assert len(lines) == 2 and lines[1].startswith(FULL_ID[:8]) and " GET " in lines[1]


def test_list_requests_says_when_it_leaves_rows_out(app):
    text = _text(app("list_requests", last=1))
    assert text.splitlines()[-1] == "showing the newest 1 of 2; raise last for more"


def test_get_request_resolves_a_short_id_and_cuts_the_bodies(app):
    tx = json.loads(_text(app("get_request", id=FULL_ID[:8], max_body=10)))
    assert [request[:2] for request in app.requests] == [
        ("GET", "/api/v1/network/transactions"),
        ("GET", "/api/v1/network/transactions/" + FULL_ID),
    ]
    assert tx["responseBody"] == (
        "x" * 10 + "[... 4990 more bytes: call_api GET network/transactions/{}/body/response]".format(FULL_ID)
    )


def test_get_request_with_an_unknown_id_says_which_tool_lists_them(app):
    result = app("get_request", id="zzzz")
    assert result["isError"] is True
    assert "`list_requests` prints the transactions" in result["content"][0]["text"]


def test_list_mock_rules_reads_the_rules_and_cuts_their_bodies(app):
    rules = json.loads(_text(app("list_mock_rules")))
    assert app.requests == [("GET", "/api/v1/network/rules", [], None)]
    assert rules["items"][0]["id"] == "orders-500"
    assert rules["items"][0]["responseBody"] == "y" * 2048 + "[... 952 more bytes: call_api GET network/rules]"
    assert rules["items"][1]["responseBody"] == '{"users":[]}'


def test_add_mock_rule_posts_the_rule_with_the_wire_names(app):
    _text(
        app(
            "add_mock_rule",
            url_pattern="/v1/orders",
            id="orders-500",
            method="GET",
            status=500,
            body='{"error":"boom"}',
            headers={"Content-Type": "application/json"},
            enabled=False,
        )
    )
    assert app.requests == [
        (
            "POST",
            "/api/v1/network/rules",
            [],
            {
                "urlPattern": "/v1/orders",
                "id": "orders-500",
                "method": "GET",
                "statusCode": 500,
                "responseBody": '{"error":"boom"}',
                "responseHeaders": {"Content-Type": "application/json"},
                "enabled": False,
            },
        )
    ]


def test_add_mock_rule_posts_a_failure(app):
    _text(app("add_mock_rule", url_pattern="/v1/orders", failure="unknownHost"))
    rule = {"urlPattern": "/v1/orders", "failure": "unknownHost"}
    assert app.requests == [("GET", "/api/v1/_meta", [], None), ("POST", "/api/v1/network/rules", [], rule)]


def test_delete_mock_rule_posts_the_id(app):
    _text(app("delete_mock_rule", id="orders-500"))
    assert app.requests == [("POST", "/api/v1/network/rules/delete", [], {"id": "orders-500"})]


def test_set_throttle_posts_the_delay(app):
    assert json.loads(_text(app("set_throttle", delay_ms=2000))) == {"status": "ok", "delayMs": 2000}
    assert app.requests == [("POST", "/api/v1/network/throttle", [], {"delayMs": 2000})]


def test_read_logs_reads_the_text_route_with_the_wire_names_of_the_filters(app):
    text = _text(app("read_logs", level="W", tag="^Sync$", text="slow", message_regex="s.ow", from_ms=1, to_ms=2))
    assert app.requests == [
        # from_ms and to_ms read the version of the app's Logs tab first.
        ("GET", "/api/v1/_meta", [], None),
        (
            "GET",
            "/api/v1/logs/text",
            [
                ("level", "W"),
                ("tag", "^Sync$"),
                ("q", "slow"),
                ("mq", "s.ow"),
                ("from", "1"),
                ("to", "2"),
                ("limit", "100"),
            ],
            None,
        )
    ]
    assert text.endswith("W Sync: slow\n")


def test_read_logs_keeps_the_newest_lines_within_max_bytes(app):
    newest = "11-18 09:00:00.001  1234  1234 W Sync: slow\n"
    assert _text(app("read_logs", max_bytes=len(newest) + 5)) == (
        "[... {} earlier bytes: call again with a larger max_bytes or a smaller limit]\n{}".format(
            len(LOG_TEXT.encode("utf-8")) - len(newest), newest
        )
    )
    # One line longer than max_bytes: its end, from a whole character.
    assert _text(app("read_logs", max_bytes=10)).endswith("smaller limit]\nync: slow\n")
    assert _text(app("read_logs")) == LOG_TEXT


def test_cut_head_keeps_a_line_that_starts_at_the_cut_and_whole_characters():
    note = "[... {} earlier bytes: call again with a larger max_bytes or a smaller limit]\n"
    assert mcp._cut_head("aaaa\nbbbb\ncccc\n", 10) == note.format(5) + "bbbb\ncccc\n"
    # The cut falls on the second byte of "é": the text starts after it.
    assert mcp._cut_head("abcé1234\n", 6) == note.format(5) + "1234\n"


def test_call_api_sends_any_request_and_returns_the_body(app):
    _text(app("call_api", method="post", path="network/throttle", body='{"delayMs": 5}'))
    meta = json.loads(_text(app("call_api", method="GET", path="/api/v1/_meta", params={"a": ["1", 2], "b": True})))
    assert app.requests == [
        ("POST", "/api/v1/network/throttle", [], {"delayMs": 5}),
        # Query parameters read the app's protocol first.
        ("GET", "/api/v1/_meta", [], None),
        ("GET", "/api/v1/_meta", [("a", "1"), ("a", "2"), ("b", "true")], None),
    ]
    assert meta["protocolVersion"]


def test_call_api_cuts_a_long_response_and_describes_a_binary_one(app):
    assert _text(app("call_api", method="GET", path="big", max_bytes=10)) == (
        "a" * 10 + "\n[... 40 more bytes: call again with a larger max_bytes]"
    )
    assert _text(app("call_api", method="GET", path="screen/image")) == (
        "(a binary body of 10 bytes, Content-Type image/png)"
    )


def test_an_error_response_is_a_tool_error_with_the_envelope(app):
    result = app("call_api", method="GET", path="network/nope")
    assert result["isError"] is True
    assert result["content"][0]["text"].startswith("error: 404 not_found")


def test_get_schema_reads_meta_without_a_tab_and_the_schema_with_one(app):
    _text(app("get_schema"))
    assert json.loads(_text(app("get_schema", tab="network")))["openapi"] == "3.1.0"
    assert [request[:2] for request in app.requests] == [
        ("GET", "/api/v1/_meta"),
        ("GET", "/api/v1/network/_schema"),
    ]


def test_the_adb_note_is_only_for_an_app_that_the_server_did_not_reach(app, monkeypatch):
    monkeypatch.setattr(mcp.shutil, "which", lambda name, path=None: None)
    note = "note: adb is not on the PATH of the MCP server"
    answered = app("call_api", method="GET", path="network/nope")
    assert answered["isError"] is True
    assert note not in answered["content"][0]["text"]
    closed = ThreadingHTTPServer(("127.0.0.1", 0), _Recorder)
    port = closed.server_address[1]
    closed.server_close()
    (reply,) = _serve(
        [_request("tools/call", {"name": "list_mock_rules"})], _args(host="127.0.0.1", port=port, token=TOKEN)
    )
    assert reply["result"]["isError"] is True
    assert note in reply["result"]["content"][0]["text"]


def test_a_tool_without_a_token_or_a_device_says_how_to_give_one(monkeypatch):
    monkeypatch.delenv("LUSTRO_TOKEN", raising=False)
    (reply,) = _serve([_request("tools/call", {"name": "list_mock_rules"})])
    assert reply["result"]["isError"] is True
    # The computer of the test has no adb: the cause says to install it, or to pass --token.
    assert "--token" in reply["result"]["content"][0]["text"]


# ── adb on the PATH ───────────────────────────────────────────────────────────


def _fake_adb(directory):
    directory.mkdir(parents=True)
    adb = directory / ("adb.exe" if os.name == "nt" else "adb")
    adb.write_text("")
    adb.chmod(adb.stat().st_mode | stat.S_IEXEC)
    return str(directory)


def test_the_sdk_platform_tools_go_on_the_path_when_adb_is_not_on_it(tmp_path):
    tools = _fake_adb(tmp_path / "sdk" / "platform-tools")
    environ = {"PATH": str(tmp_path / "bin"), "ANDROID_HOME": str(tmp_path / "sdk")}
    assert add_sdk_adb_to_path(environ) == tools
    assert environ["PATH"] == tools + os.pathsep + str(tmp_path / "bin")


def test_the_path_stays_as_it_is_when_it_has_adb(tmp_path):
    tools = _fake_adb(tmp_path / "bin")
    _fake_adb(tmp_path / "sdk" / "platform-tools")
    environ = {"PATH": tools, "ANDROID_HOME": str(tmp_path / "sdk")}
    assert add_sdk_adb_to_path(environ) is None
    assert environ["PATH"] == tools


# ── the server's stdin ────────────────────────────────────────────────────────


@pytest.mark.skipif(os.name == "nt", reason="the stand-in adb is a script that runs through its #! line")
def test_adb_does_not_read_the_messages_on_the_servers_stdin(tmp_path):
    """`adb shell` can forward its stdin to the device. The server's stdin carries the
    client's messages, so an adb child that inherits it can take the next one. Here a
    stand-in adb reads its stdin while discovery runs, and the client sends a ping."""
    taken = tmp_path / "taken-by-adb"
    adb = tmp_path / "bin" / "adb"
    adb.parent.mkdir()
    adb.write_text(
        "#!{}\n"
        "import os, select, sys\n"
        "if sys.argv[1:2] == ['shell'] and select.select([0], [], [], 2)[0]:\n"
        "    open({!r}, 'ab').write(os.read(0, 65536))\n"
        "sys.exit(1)\n".format(sys.executable, str(taken))
    )
    adb.chmod(0o755)
    env = dict(os.environ, PATH=str(adb.parent) + os.pathsep + os.environ.get("PATH", ""))
    env["PYTHONPATH"] = os.path.dirname(os.path.dirname(lustro_cli.__file__))
    env.pop("LUSTRO_TOKEN", None)
    server = subprocess.Popen(
        [sys.executable, "-c", "import sys; from lustro_cli.cli import main; sys.exit(main())", "mcp"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        env=env,
    )
    replies = []
    reader = threading.Thread(target=lambda: replies.extend(json.loads(line) for line in server.stdout), daemon=True)
    reader.start()
    try:
        # No token anywhere, so discovery asks adb which app is in the foreground.
        server.stdin.write((json.dumps(_request("tools/call", {"name": "list_mock_rules"})) + "\n").encode())
        server.stdin.flush()
        time.sleep(0.5)
        server.stdin.write((json.dumps(_request("ping", request_id=2)) + "\n").encode())
        server.stdin.flush()
        deadline = time.monotonic() + 20
        while len(replies) < 2 and time.monotonic() < deadline:
            time.sleep(0.05)
    finally:
        server.stdin.close()
        server.wait(timeout=20)
    assert [reply["id"] for reply in replies] == [1, 2]
    assert not taken.exists() or taken.read_bytes() == b""


def test_adb_forward_does_not_inherit_stdin(monkeypatch):
    calls = []

    def run(cmd, **kwargs):
        calls.append(kwargs)
        return subprocess.CompletedProcess(cmd, 0, b"", b"")

    monkeypatch.setattr(cli.subprocess, "run", run)
    assert adb_forward(18080, 8080, "emulator-5554") == (18080, None)
    assert calls[0]["stdin"] is subprocess.DEVNULL


def test_a_warning_from_the_app_is_a_text_block_after_the_output(app):
    result = app("call_api", method="GET", path="work/items", params={"uniqueName": "X", "name": "Y"})
    assert result["isError"] is False
    assert [block["text"] for block in result["content"]] == [
        '{"items":[]}',
        'warning: unknown query parameter "uniqueName"',
    ]


def test_only_declared_parameters_add_no_warning_block(app):
    result = app("call_api", method="GET", path="work/items", params={"name": "Y"})
    assert len(result["content"]) == 1


def test_an_error_result_keeps_its_warning_and_the_next_call_starts_without_it(app):
    result = app("call_api", method="GET", path="work/items", params={"fail": 1})
    assert result["isError"] is True
    assert result["content"][1]["text"] == 'warning: unknown query parameter "fail"'
    assert len(app("call_api", method="GET", path="work/items")["content"]) == 1



def test_a_call_with_parameters_to_an_app_before_1_15_gets_a_note(app):
    _Recorder.protocol = "1.14"
    result = app("call_api", method="GET", path="work/items", params={"uniqueName": "X"})
    assert result["isError"] is False
    assert [block["text"] for block in result["content"]] == [
        '{"items":[]}',
        "note: the app uses wire protocol 1.14, so it does not warn about a query parameter that the route does "
        "not declare (from 1.15), and the route ignores it: get_schema with the tab lists the parameters of each "
        "route",
    ]
    assert [request[:2] for request in app.requests] == [("GET", "/api/v1/_meta"), ("GET", "/api/v1/work/items")]


def test_a_call_without_parameters_does_not_read_meta(app):
    _Recorder.protocol = "1.14"
    assert len(app("call_api", method="GET", path="work/items")["content"]) == 1
    assert [request[:2] for request in app.requests] == [("GET", "/api/v1/work/items")]

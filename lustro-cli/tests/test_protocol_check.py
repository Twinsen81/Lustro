"""The options that an app on an older wire protocol ignores: a command with one reads
the app's protocol from _meta, and refuses to run against an older app."""

from __future__ import annotations

import argparse
import json
import re
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

from lustro_cli import PROTOCOL_VERSION, cli, mcp, wire

TOKEN = "test-token-123"
WS_ID = "5b1e2c3d-4f5a-4b6c-8d7e-9f0a1b2c3d4e"
README = Path(__file__).resolve().parents[2] / "wire-protocol" / "v1" / "README.md"


class _App(BaseHTTPRequestHandler):
    """Answers like the app on ``protocol``, with the Logs tab of version ``logs`` (None: no
    Logs tab), and records each request."""

    protocol = PROTOCOL_VERSION
    logs = None
    requests = []

    def log_message(self, *args):  # silence
        pass

    def _send(self, status, body):
        data = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _handle(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length)) if length else None
        url = urllib.parse.urlsplit(self.path)
        self.requests.append((self.command, url.path, dict(urllib.parse.parse_qsl(url.query)), body))
        route = (self.command, url.path)
        if route == ("GET", "/api/v1/_meta"):
            tabs = [{"id": "network", "title": "Network", "schemaUrl": "/api/v1/network/_schema", "version": self.protocol}]
            if self.logs is not None:
                tabs.append({"id": "logs", "title": "Logs", "schemaUrl": "/api/v1/logs/_schema", "version": self.logs})
            self._send(200, {"libraryVersion": "0.1.0", "protocolVersion": self.protocol, "tabs": tabs})
        elif route == ("GET", "/api/v1/logs/text") and self.logs is not None:
            data = b"11-18 09:00:00.000  1234  1234 W Sync: slow\n"
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        elif route == ("GET", "/api/v1/network/transactions"):
            self._send(200, wire.load_golden("cursor-reset.json"))
        elif route == ("GET", "/api/v1/network/websockets/{}/events".format(WS_ID)):
            self._send(200, wire.load_golden("stream-reset.json"))
        elif route == ("POST", "/api/v1/network/rules"):
            self._send(200, {"status": "ok", "id": "r1"})
        elif route == ("POST", "/api/v1/network/rules/_/sync"):
            self._send(200, {"status": "ok", "count": len(body)})
        elif route == ("POST", "/api/v1/network/send"):
            self._send(200, wire.load_golden("send-result.json"))
        else:
            self._send(404, wire.load_golden("error-envelope.json"))

    do_GET = _handle
    do_POST = _handle


class _Running:
    def __init__(self, monkeypatch, host, port):
        self._monkeypatch = monkeypatch
        self.requests = _App.requests
        self.argv = ["--host", host, "--port", str(port), "--token", TOKEN]
        self.args = argparse.Namespace(**dict(cli.GLOBAL_DEFAULTS, host=host, port=port, token=TOKEN))

    def on(self, protocol):
        """Answer _meta with this protocolVersion."""
        self._monkeypatch.setattr(_App, "protocol", protocol)

    def with_logs(self, version):
        """Have a Logs tab of this version, or none for None."""
        self._monkeypatch.setattr(_App, "logs", version)


@pytest.fixture
def app(monkeypatch):
    monkeypatch.setattr(_App, "requests", [])
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), _App)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    host, port = httpd.server_address
    try:
        yield _Running(monkeypatch, host, port)
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=5)


def _routes(app):
    return [request[:2] for request in app.requests]


# Each command with an option of OPTION_PROTOCOLS, and the request that carries the option.
COMMANDS = [
    (["net", "list", "--search", "ord.rs", "--regex"], "1.8", ("GET", "/api/v1/network/transactions")),
    (["net", "poll", "--once", "--search", "ord.rs", "--regex"], "1.8", ("GET", "/api/v1/network/transactions")),
    (
        ["net", "wait", "--timeout", "0.1", "--interval", "0.1", "--search", "nothing", "--regex"],
        "1.8",
        ("GET", "/api/v1/network/transactions"),
    ),
    (
        ["net", "ws", "events", WS_ID, "--search", "pi.g", "--regex"],
        "1.8",
        ("GET", "/api/v1/network/websockets/{}/events".format(WS_ID)),
    ),
    (["mock", "add", "--url-pattern", "/v1/feed", "--failure", "timeout"], "1.12", ("POST", "/api/v1/network/rules")),
    (["send", "--url", "/popular.json", "--sender", "Lists"], "1.7", ("POST", "/api/v1/network/send")),
]


def _older(version):
    major, minor = version.split(".")
    return "{}.{}".format(major, int(minor) - 1)


@pytest.mark.parametrize("argv, needed, route", COMMANDS)
def test_an_option_that_the_apps_protocol_is_too_old_for_is_refused_before_the_request(
    app, capsys, argv, needed, route
):
    app.on(_older(needed))
    assert cli.main(app.argv + argv) == 1
    assert _routes(app) == [("GET", "/api/v1/_meta")]
    option = next(arg for arg in argv if arg in ("--regex", "--failure", "--sender"))
    assert capsys.readouterr().err == (
        "error: protocol_too_old: {} needs wire protocol {}, and the app uses {}, which ignores it\n"
        "  hint: update Lustro in the app, or leave out {}\n".format(option, needed, _older(needed), option)
    )


@pytest.mark.parametrize("argv, needed, route", COMMANDS)
def test_an_app_on_the_protocol_that_added_the_option_gets_the_request(app, argv, needed, route):
    app.on(needed)
    cli.main(app.argv + argv)
    assert _routes(app)[0] == ("GET", "/api/v1/_meta")
    assert route in _routes(app)


@pytest.mark.parametrize(
    "argv",
    [
        ["net", "list", "--search", "orders"],
        ["mock", "add", "--url-pattern", "/v1/feed", "--status", "500"],
        ["send", "--url", "https://example.com/"],
    ],
)
def test_a_command_without_such_an_option_does_not_read_meta(app, argv):
    app.on("1.0")
    assert cli.main(app.argv + argv) == 0
    assert ("GET", "/api/v1/_meta") not in _routes(app)


def test_an_app_without_a_version_that_the_cli_can_compare_is_not_refused(app):
    app.on("unknown")
    assert cli.main(app.argv + ["mock", "add", "--url-pattern", "/v1/feed", "--failure", "timeout"]) == 0
    assert _routes(app) == [("GET", "/api/v1/_meta"), ("POST", "/api/v1/network/rules")]


def test_mock_sync_refuses_a_rule_with_a_failure_for_an_older_app(app, capsys, tmp_path):
    rules = tmp_path / "rules.json"
    rules.write_text(json.dumps([{"urlPattern": "/a"}, {"urlPattern": "/b", "failure": "tls"}]), encoding="utf-8")
    app.on("1.11")
    assert cli.main(app.argv + ["mock", "sync", str(rules)]) == 1
    assert _routes(app) == [("GET", "/api/v1/_meta")]
    err = capsys.readouterr().err
    assert "failure needs wire protocol 1.12, and the app uses 1.11" in err
    assert "leave failure out of the rules" in err
    app.on("1.12")
    assert cli.main(app.argv + ["mock", "sync", str(rules)]) == 0
    assert _routes(app)[-1] == ("POST", "/api/v1/network/rules/_/sync")


def test_mock_sync_of_the_rules_that_a_newer_app_listed_goes_to_an_older_app(app, tmp_path):
    # `mock list --json` of a 1.12 app has "failure": null in each rule that answers.
    rules = tmp_path / "rules.json"
    rules.write_text(json.dumps({"items": [{"id": "a", "urlPattern": "/a", "failure": None}]}), encoding="utf-8")
    app.on("1.11")
    assert cli.main(app.argv + ["mock", "sync", str(rules)]) == 0
    assert _routes(app) == [("POST", "/api/v1/network/rules/_/sync")]


def test_net_wait_does_not_run_its_action_for_an_older_app(app, tmp_path):
    marker = tmp_path / "ran"
    app.on("1.7")
    argv = ["net", "wait", "--search", "x", "--regex", "--", "touch", str(marker)]
    assert cli.main(app.argv + argv) == 1
    assert not marker.exists()


# ── MCP ───────────────────────────────────────────────────────────────────────


def _call(app, tool, **arguments):
    request = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": tool, "arguments": arguments}}
    return mcp.Server(app.args).handle(request)["result"]


@pytest.mark.parametrize(
    "tool, arguments, needed",
    [
        ("add_mock_rule", {"url_pattern": "/v1/feed", "failure": "timeout"}, "1.12"),
        ("list_requests", {"search": "ord.rs", "regex": True}, "1.8"),
    ],
)
def test_an_mcp_argument_that_the_apps_protocol_is_too_old_for_is_a_tool_error(app, tool, arguments, needed):
    app.on(_older(needed))
    result = _call(app, tool, **arguments)
    assert result["isError"] is True
    option = "failure" if "failure" in arguments else "regex"
    assert result["content"][0]["text"] == (
        "error: protocol_too_old: {} needs wire protocol {}, and the app uses {}, which ignores it\n"
        "  hint: update Lustro in the app, or leave out {}".format(option, needed, _older(needed), option)
    )
    assert _routes(app) == [("GET", "/api/v1/_meta")]
    app.on(needed)
    assert _call(app, tool, **arguments)["isError"] is False


@pytest.mark.parametrize(
    "arguments, names",
    [
        ({"from_ms": 1}, "from_ms needs"),
        ({"to_ms": 2}, "to_ms needs"),
        ({"from_ms": 1, "to_ms": 2}, "from_ms and to_ms need"),
    ],
)
def test_read_logs_with_a_time_range_is_a_tool_error_for_a_logs_tab_before_1_1(app, arguments, names):
    app.with_logs("1.0")
    result = _call(app, "read_logs", **arguments)
    assert result["isError"] is True
    which = "them" if " and " in names else "it"
    assert result["content"][0]["text"] == (
        "error: protocol_too_old: {} version 1.1 of the logs tab, and the app's is 1.0, which ignores {}\n"
        "  hint: update Lustro in the app, or leave out {}".format(names, which, names.rsplit(" ", 1)[0])
    )
    assert _routes(app) == [("GET", "/api/v1/_meta")]


def test_a_logs_tab_that_reports_the_protocol_version_is_its_first_version(app):
    # An app from before the tabs reported a version of their own: its Logs tab had no time range.
    app.on("1.10")
    app.with_logs("1.10")
    result = _call(app, "read_logs", from_ms=1)
    assert result["content"][0]["text"].startswith(
        "error: protocol_too_old: from_ms needs version 1.1 of the logs tab, and the app's is 1.0, which ignores it"
    )
    assert _routes(app) == [("GET", "/api/v1/_meta")]


def test_read_logs_with_a_time_range_reads_the_logs_of_a_logs_tab_1_1(app):
    app.with_logs("1.1")
    assert _call(app, "read_logs", from_ms=1, to_ms=2)["isError"] is False
    assert _routes(app) == [("GET", "/api/v1/_meta"), ("GET", "/api/v1/logs/text")]
    assert app.requests[-1][2] == {"from": "1", "to": "2", "limit": "100"}


def test_read_logs_without_a_time_range_does_not_read_meta(app):
    app.with_logs("1.0")
    assert _call(app, "read_logs", level="W")["isError"] is False
    assert _routes(app) == [("GET", "/api/v1/logs/text")]


def test_an_app_without_the_tab_is_not_refused_and_gets_the_apps_404(app):
    result = _call(app, "read_logs", from_ms=1)
    assert _routes(app) == [("GET", "/api/v1/_meta"), ("GET", "/api/v1/logs/text")]
    assert result["content"][0]["text"].startswith("error: 404 not_found")


# ── the table ─────────────────────────────────────────────────────────────────


def _revisions():
    """The minor revisions that the wire protocol README lists."""
    return set(re.findall(r"^- \*\*(1\.\d+)\*\*", README.read_text(encoding="utf-8"), re.MULTILINE))


def test_each_version_in_the_table_is_one_that_the_protocol_or_the_tab_has():
    revisions = _revisions()
    ours = cli._version_tuple(PROTOCOL_VERSION)
    for (command, option), needs in cli.OPTION_PROTOCOLS.items():
        if needs.tab is None:
            assert needs.version in revisions, (command, option)
            assert cli._version_tuple(needs.version) <= ours
        else:
            tab_version = wire.load_openapi(needs.tab)["info"]["version"]
            assert cli._version_tuple(needs.version) <= cli._version_tuple(tab_version), (command, option)
    assert cli.UNKNOWN_PARAMETER_WARNINGS in revisions


def _subparser(parser, names):
    for name in names:
        action = next(a for a in parser._actions if a.__class__.__name__ == "_SubParsersAction")
        parser = action.choices[name]
    return parser


def test_each_command_and_mcp_tool_in_the_table_has_the_option():
    parser = cli.build_parser()
    tools = {tool.name: tool for tool in mcp.TOOLS}
    for command, option in cli.OPTION_PROTOCOLS:
        if command in tools:
            assert option in tools[command].input_schema["properties"], (command, option)
        elif option.startswith("--"):
            options = {o for action in _subparser(parser, command.split())._actions for o in action.option_strings}
            assert option in options, (command, option)
        else:
            # A field of the file that the command posts.
            assert (command, option) == ("mock sync", "failure")

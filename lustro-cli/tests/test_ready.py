"""``lustro ready``: it waits until the app's server answers, or prints the cause
and exits with its code. adb is a fake that answers each command from a table."""

from __future__ import annotations

import json
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

import lustro_cli
from lustro_cli import cli, discovery, exitcodes, mcp

TOKEN = "tok"
PACKAGE = "com.example.app"
LOGCAT = "adb logcat -d -s LustroToken"
PM_PATH = "adb shell pm path " + PACKAGE
APK = "/data/app/~~Xy==/com.example.app-Ab==/base.apk"
GREP = "adb shell grep -c -F assets/lustro/ '{}' || true".format(APK)
PIDOF = "adb shell pidof {} || true".format(PACKAGE)
DUMPSYS = "adb shell dumpsys activity activities | grep ResumedActivity"


def _resumed(package):
    return "  topResumedActivity=ActivityRecord{{1a2b u0 {}/.MainActivity t12}}\n".format(package)


def _ready_line(port, token=TOKEN):
    return "I LustroToken: Lustro ready endpoint=http://127.0.0.1:{} token={}\n".format(port, token)


# adb's answers for an installed debug build that runs in the foreground.
HEALTHY = {
    PM_PATH: "package:{}\n".format(APK),
    GREP: "3\n",
    PIDOF: "4242\n",
    DUMPSYS: _resumed(PACKAGE),
}


class FakeAdb:
    """Answers each adb command from ``outputs``, keyed by its arguments. A value can
    be a function of the number of earlier calls of the same command. ``adb get-state``
    answers ``state``, its exit status and output."""

    def __init__(self, monkeypatch, outputs, state=(0, "device\n")):
        self.outputs = outputs
        self.state = state
        self.calls = []
        monkeypatch.setattr(discovery, "_run", self.run)
        monkeypatch.setattr(discovery, "_run_status", self.run_status)

    def run(self, cmd, timeout=10.0):
        key = " ".join(cmd)
        earlier = self.count(key)
        self.calls.append(key)
        answer = self.outputs.get(key)
        return answer(earlier) if callable(answer) else answer

    def run_status(self, cmd, timeout=10.0):
        self.calls.append(" ".join(cmd))
        return self.state

    def count(self, key):
        return sum(1 for call in self.calls if call == key)


class _MetaHandler(BaseHTTPRequestHandler):
    meta = {"libraryVersion": "0.1.0", "protocolVersion": lustro_cli.PROTOCOL_VERSION, "tabs": []}
    status = 200

    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.headers.get("Authorization") != "Bearer " + TOKEN:
            body = {"error": "unauthorized", "message": "missing token"}
            status = 401
        else:
            body, status = self.server.meta, self.server.status
        data = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


@pytest.fixture
def server():
    """A server that answers ``GET _meta``; set ``.meta`` and ``.status`` to change the answer."""
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), _MetaHandler)
    httpd.meta = dict(_MetaHandler.meta)
    httpd.status = 200
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    try:
        yield httpd
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=5)


@pytest.fixture
def closed_port():
    """A port on which nothing listens."""
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


@pytest.fixture(autouse=True)
def _fast(monkeypatch):
    monkeypatch.setattr(cli, "READY_INTERVAL", 0.01)
    monkeypatch.delenv("LUSTRO_TOKEN", raising=False)
    cli._printed_warnings.clear()


def _ready(*argv):
    return cli.main(["ready", "--timeout", "0.2", *argv])


# ── the server answers ─────────────────────────────────────────────────────────


def test_ready_prints_the_endpoint_when_the_server_answers(server, capsys):
    port = server.server_address[1]
    assert _ready("--port", str(port), "--token", TOKEN) == exitcodes.OK
    out, err = capsys.readouterr()
    assert out == "http://127.0.0.1:{}\n".format(port)
    assert err == ""


def test_ready_runs_discovery_again_until_the_app_logs_its_ready_line(server, monkeypatch, capsys):
    port = server.server_address[1]
    # Without adb's forward, a command connects to the app's port on this computer.
    adb = FakeAdb(monkeypatch, {LOGCAT: lambda earlier: _ready_line(port) if earlier >= 3 else ""})
    assert cli.main(["ready", "--timeout", "5", "--package", PACKAGE]) == exitcodes.OK
    assert capsys.readouterr().out == "http://127.0.0.1:{}\n".format(port)
    assert adb.count(LOGCAT) == 4
    # The checks of the cause run only when the time is over.
    assert adb.count(GREP) == 0
    assert not any(call.endswith("get-state") for call in adb.calls)


def test_ready_tries_again_while_the_port_does_not_answer(server, closed_port, monkeypatch, capsys):
    port = server.server_address[1]
    # A line from an earlier start names a port that does not answer; the new start logs another.
    FakeAdb(monkeypatch, {LOGCAT: lambda earlier: _ready_line(port if earlier >= 2 else closed_port)})
    assert cli.main(["ready", "--timeout", "5"]) == exitcodes.OK
    assert capsys.readouterr().out == "http://127.0.0.1:{}\n".format(port)


def test_ready_json_has_the_endpoint_and_the_versions_and_no_token(server, capsys):
    port = server.server_address[1]
    assert _ready("--port", str(port), "--token", TOKEN, "--json") == exitcodes.OK
    printed = json.loads(capsys.readouterr().out)
    assert printed == {
        "endpoint": "http://127.0.0.1:{}".format(port),
        "libraryVersion": "0.1.0",
        "protocolVersion": lustro_cli.PROTOCOL_VERSION,
    }


# ── the cause at the timeout ───────────────────────────────────────────────────


def _cause(capsys):
    err = capsys.readouterr().err
    assert err.startswith("error: the app's server did not answer within 0.2 s: "), err
    return err[len("error: the app's server did not answer within 0.2 s: ") :]


def test_ready_without_adb(monkeypatch, capsys):
    FakeAdb(monkeypatch, {}, state=(127, ""))
    assert _ready() == exitcodes.NO_DEVICE
    assert _cause(capsys).startswith("adb is not installed")


def test_ready_without_a_device(monkeypatch, capsys):
    FakeAdb(monkeypatch, {}, state=(1, "error: no devices/emulators found\n"))
    assert _ready("--package", PACKAGE) == exitcodes.NO_DEVICE
    assert _cause(capsys).startswith("no device that adb can use (error: no devices/emulators found)")


def test_ready_with_more_than_one_device_and_none_selected(monkeypatch, capsys):
    FakeAdb(monkeypatch, {}, state=(1, "error: more than one device/emulator\n"))
    assert _ready("--package", PACKAGE) == exitcodes.USAGE
    assert "--device SERIAL" in _cause(capsys)


def test_ready_when_the_app_is_not_installed(monkeypatch, capsys):
    FakeAdb(monkeypatch, {})
    assert _ready("--package", PACKAGE) == exitcodes.NOT_INSTALLED
    assert _cause(capsys).startswith(PACKAGE + " is not installed on the device")


def test_ready_when_the_build_has_no_lustro_runtime(monkeypatch, capsys):
    adb = FakeAdb(monkeypatch, dict(HEALTHY, **{GREP: "0\n"}))
    assert _ready("--package", PACKAGE) == exitcodes.NO_RUNTIME
    cause = _cause(capsys)
    assert cause.startswith("the installed build of {} does not contain the Lustro runtime".format(PACKAGE))
    assert "from before the app added Lustro" in cause
    # One read of the APK, at the end, however many tries there were.
    assert adb.count(GREP) == 1
    assert adb.count(LOGCAT) > 1


def test_ready_for_a_no_op_build_with_a_ready_line_from_an_earlier_debug_build(closed_port, monkeypatch, capsys):
    # The line names a port where nothing listens now: the cause is the build, not the port.
    FakeAdb(monkeypatch, dict(HEALTHY, **{GREP: "0\n", LOGCAT: _ready_line(closed_port)}))
    assert _ready("--package", PACKAGE) == exitcodes.NO_RUNTIME
    assert "does not contain the Lustro runtime" in _cause(capsys)


def test_ready_when_the_app_is_not_running(closed_port, monkeypatch, capsys):
    FakeAdb(monkeypatch, dict(HEALTHY, **{PIDOF: "", LOGCAT: _ready_line(closed_port)}))
    assert _ready("--package", PACKAGE) == exitcodes.NOT_REACHABLE
    assert _cause(capsys).startswith(PACKAGE + " is not running")


def test_ready_when_the_app_is_in_the_background(monkeypatch, capsys):
    FakeAdb(monkeypatch, dict(HEALTHY, **{DUMPSYS: _resumed("com.android.launcher3")}))
    assert _ready("--package", PACKAGE) == exitcodes.NOT_REACHABLE
    assert _cause(capsys).startswith(PACKAGE + " runs, but com.android.launcher3 is in the foreground")


def test_ready_when_the_port_does_not_answer(closed_port, monkeypatch, capsys):
    FakeAdb(monkeypatch, dict(HEALTHY, **{LOGCAT: _ready_line(closed_port)}))
    assert _ready("--package", PACKAGE) == exitcodes.NOT_REACHABLE
    assert _cause(capsys).startswith("connection_failed: could not reach http://127.0.0.1:{}/".format(closed_port))


def test_ready_when_the_app_logs_no_ready_line(monkeypatch, capsys):
    FakeAdb(monkeypatch, HEALTHY)
    assert _ready("--package", PACKAGE) == exitcodes.NOT_REACHABLE
    assert _cause(capsys).startswith("no token:")


def test_ready_with_a_port_that_does_not_answer_and_no_adb_checks(closed_port, monkeypatch, capsys):
    # --port names a local port, which can be a server on this computer: adb has nothing to say.
    adb = FakeAdb(monkeypatch, HEALTHY)
    assert _ready("--port", str(closed_port), "--token", TOKEN) == exitcodes.NOT_REACHABLE
    assert _cause(capsys).startswith("connection_failed:")
    assert not any(call.endswith("get-state") or call in (PM_PATH, GREP, PIDOF) for call in adb.calls)


def test_ready_when_the_app_answers_with_an_error(server, capsys):
    server.status = 503
    server.meta = {"error": "unavailable", "message": "busy"}
    assert _ready("--port", str(server.server_address[1]), "--token", TOKEN) == exitcodes.ERROR
    assert _cause(capsys).startswith("503 unavailable: busy")


def test_ready_rejects_a_timeout_that_is_not_positive(capsys):
    with pytest.raises(SystemExit) as excinfo:
        cli.main(["ready", "--timeout", "0"])
    assert excinfo.value.code == exitcodes.USAGE


# ── the exit codes of the other commands ───────────────────────────────────────


def test_a_command_exits_with_the_code_of_the_cause(monkeypatch, capsys):
    FakeAdb(monkeypatch, dict(HEALTHY, **{GREP: "0\n"}))
    assert cli.main(["net", "list", "--package", PACKAGE]) == exitcodes.NO_RUNTIME
    assert capsys.readouterr().err.startswith("error: the installed build of {}".format(PACKAGE))


def test_a_command_whose_connection_fails_gets_the_cause_from_adb(closed_port, monkeypatch, capsys):
    FakeAdb(monkeypatch, dict(HEALTHY, **{PIDOF: "", LOGCAT: _ready_line(closed_port)}))
    assert cli.main(["meta", "--package", PACKAGE]) == exitcodes.NOT_REACHABLE
    assert capsys.readouterr().err.startswith("error: {} is not running".format(PACKAGE))


def test_an_error_response_exits_1(server, capsys):
    server.status = 404
    server.meta = {"error": "not_found", "message": "no route"}
    assert cli.main(["meta", "--port", str(server.server_address[1]), "--token", TOKEN]) == exitcodes.ERROR


# ── the protocol version ───────────────────────────────────────────────────────


def test_version_prints_the_protocol_that_the_cli_implements(capsys):
    with pytest.raises(SystemExit) as excinfo:
        cli.main(["--version"])
    assert excinfo.value.code == 0
    assert capsys.readouterr().out == "lustro {} (protocol {})\n".format(
        lustro_cli.__version__, lustro_cli.PROTOCOL_VERSION
    )


def _newer(version):
    major, minor = version.split(".")
    return "{}.{}".format(major, int(minor) + 1)


@pytest.mark.parametrize("command", [["ready"], ["meta"]])
def test_a_newer_protocol_in_the_app_gets_a_warning(server, command, capsys):
    newer = _newer(lustro_cli.PROTOCOL_VERSION)
    server.meta = dict(server.meta, protocolVersion=newer)
    port = str(server.server_address[1])
    assert cli.main(command + ["--port", port, "--token", TOKEN]) == exitcodes.OK
    out, err = capsys.readouterr()
    assert err == "warning: the app uses wire protocol {}, and lustro {} implements {}: update the CLI to get what " \
        "the newer protocol adds\n".format(newer, lustro_cli.__version__, lustro_cli.PROTOCOL_VERSION)
    assert out


@pytest.mark.parametrize("version", [lustro_cli.PROTOCOL_VERSION, "1.2", "1.9", "beta", None])
def test_the_same_an_older_or_an_unknown_protocol_gets_no_warning(version):
    assert cli.newer_protocol_warning({"protocolVersion": version}) is None


def test_versions_compare_as_numbers():
    # 1.10 is newer than 1.9, which a comparison of text gets wrong.
    assert cli._version_tuple("1.10") > cli._version_tuple("1.9")


def test_the_mcp_tool_for_the_tabs_passes_the_warning_on(server, monkeypatch):
    newer = _newer(lustro_cli.PROTOCOL_VERSION)
    server.meta = dict(server.meta, protocolVersion=newer)
    args = cli.build_parser().parse_args(["mcp"])
    for name, default in cli.GLOBAL_DEFAULTS.items():
        setattr(args, name, getattr(args, name, default))
    args.port, args.token = server.server_address[1], TOKEN
    result = mcp.Server(args)._tools_call({"name": "get_schema", "arguments": {}})
    assert result["isError"] is False
    assert json.loads(result["content"][0]["text"])["protocolVersion"] == newer
    assert result["content"][1]["text"].startswith("warning: the app uses wire protocol " + newer)

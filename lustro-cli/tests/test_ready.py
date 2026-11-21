"""``lustro ready``: it waits until the app's server answers, or prints the cause
and exits with its code. adb is a fake that answers each command from a table."""

from __future__ import annotations

import json
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from fake_apk import APP_ENTRIES, RUNTIME_ENTRIES, apk_bytes, tail_of

import lustro_cli
from lustro_cli import cli, discovery, exitcodes, mcp

TOKEN = "tok"
PACKAGE = "com.example.app"
LOGCAT = "adb logcat -d -s LustroToken"
PM_PATH = "adb shell pm path " + PACKAGE
APK = "/data/app/~~Xy==/com.example.app-Ab==/base.apk"
TAIL = "adb exec-out tail -c 65557 '{}'".format(APK)
PIDOF = "adb shell pidof {} || true".format(PACKAGE)
# With --package, discovery reads only the lines of the app's process, which PIDOF gives.
LOGCAT_PID = LOGCAT + " --pid=4242"
DUMPSYS = "adb shell dumpsys activity activities | grep ResumedActivity"


def _resumed(package):
    return "  topResumedActivity=ActivityRecord{{1a2b u0 {}/.MainActivity t12}}\n".format(package)


def _ready_line(port, token=TOKEN):
    return "I LustroToken: Lustro ready endpoint=http://127.0.0.1:{} token={}\n".format(port, token)


WITH_RUNTIME = apk_bytes(APP_ENTRIES + RUNTIME_ENTRIES)
NO_RUNTIME = apk_bytes(APP_ENTRIES)

# adb's answers for an installed debug build that runs in the foreground.
HEALTHY = {
    PM_PATH: "package:{}\n".format(APK),
    APK: WITH_RUNTIME,
    PIDOF: "4242\n",
    DUMPSYS: _resumed(PACKAGE),
}


RUN_AS = "adb shell run-as {} cat shared_prefs/lustro_debug.xml".format(PACKAGE)


def _prefs(port):
    """The app's prefs, with the token and the port of its last bind."""
    return (
        '<map><string name="lustro_token">{}</string><string name="lustro_host">127.0.0.1</string>'
        '<int name="lustro_port" value="{}" /></map>'.format(TOKEN, port)
    )


class FakeAdb:
    """Answers each adb command from ``outputs``, keyed by its arguments. A value can
    be a function of the number of earlier calls of the same command. ``adb get-state``
    answers ``state``, its exit status and output. ``tail -c`` through ``adb exec-out``
    reads the bytes in ``outputs`` at the path of the file."""

    def __init__(self, monkeypatch, outputs, state=(0, "device\n")):
        self.outputs = outputs
        self.state = state
        self.calls = []
        monkeypatch.setattr(discovery, "_run", self.run)
        monkeypatch.setattr(discovery, "_run_bytes", self.run_bytes)
        monkeypatch.setattr(discovery, "_run_status", self.run_status)

    def run(self, cmd, timeout=10.0):
        key = " ".join(cmd)
        earlier = self.count(key)
        self.calls.append(key)
        answer = self.outputs.get(key)
        return answer(earlier) if callable(answer) else answer

    def run_bytes(self, cmd, timeout=10.0):
        self.calls.append(" ".join(cmd))
        return tail_of(self.outputs, cmd)

    def run_status(self, cmd, timeout=10.0):
        key = " ".join(cmd)
        if cmd[-1] == "get-state":
            self.calls.append(key)
            return self.state
        # A command that is not in the table exits 1 with no output, as `pm path`
        # does for a package that is not installed.
        out = self.run(cmd, timeout)
        return (1, "") if out is None else (0, out)

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


class _OtherServerHandler(BaseHTTPRequestHandler):
    """The debug server of another app: it has no Lustro routes, and its 404 is not
    Lustro's error envelope."""

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.server.requests += 1
        data = b"Endpoint not found: v1"
        self.send_response(404)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


@pytest.fixture
def other_server():
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), _OtherServerHandler)
    httpd.requests = 0
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
    adb = FakeAdb(monkeypatch, {PIDOF: "4242\n", LOGCAT_PID: lambda earlier: _ready_line(port) if earlier >= 3 else ""})
    assert cli.main(["ready", "--timeout", "5", "--package", PACKAGE]) == exitcodes.OK
    assert capsys.readouterr().out == "http://127.0.0.1:{}\n".format(port)
    assert adb.count(LOGCAT_PID) == 4
    # The checks of the cause run only when the time is over.
    assert adb.count(TAIL) == 0
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


@pytest.mark.parametrize("command", [["ready", "--timeout", "0.2"], ["meta"]])
def test_the_server_of_another_app_is_not_used_for_a_package_that_is_not_installed(server, command, monkeypatch, capsys):
    # Another app answers on the port of the last ready line in the log, which it logged.
    FakeAdb(monkeypatch, {LOGCAT: _ready_line(server.server_address[1]), PIDOF: ""})
    assert cli.main(command + ["--package", PACKAGE]) == exitcodes.NOT_INSTALLED
    out, err = capsys.readouterr()
    assert out == ""
    assert PACKAGE + " is not installed on the device" in err


def test_ready_when_the_build_has_no_lustro_runtime(monkeypatch, capsys):
    adb = FakeAdb(monkeypatch, dict(HEALTHY, **{APK: NO_RUNTIME}))
    assert _ready("--package", PACKAGE) == exitcodes.NO_RUNTIME
    cause = _cause(capsys)
    assert cause.startswith("the installed build of {} does not contain the Lustro runtime".format(PACKAGE))
    assert "from before the app added Lustro" in cause
    # One read of the APK, at the end, however many tries there were.
    assert adb.count(TAIL) == 1
    assert adb.count(LOGCAT_PID) > 1


def test_ready_for_a_no_op_build_with_a_ready_line_from_an_earlier_debug_build(closed_port, monkeypatch, capsys):
    # The line of the earlier build's process names a port where nothing listens now: the
    # cause is the build, not the port.
    FakeAdb(monkeypatch, dict(HEALTHY, **{APK: NO_RUNTIME, LOGCAT: _ready_line(closed_port)}))
    assert _ready("--package", PACKAGE) == exitcodes.NO_RUNTIME
    assert "does not contain the Lustro runtime" in _cause(capsys)


def test_ready_when_the_app_is_not_running(closed_port, monkeypatch, capsys):
    # The prefs keep the token and the port of the app's last start.
    FakeAdb(monkeypatch, dict(HEALTHY, **{PIDOF: "", LOGCAT: _ready_line(closed_port), RUN_AS: _prefs(closed_port)}))
    assert _ready("--package", PACKAGE) == exitcodes.NOT_REACHABLE
    assert _cause(capsys).startswith(PACKAGE + " is not running")


def test_ready_when_the_app_is_in_the_background(monkeypatch, capsys):
    FakeAdb(monkeypatch, dict(HEALTHY, **{DUMPSYS: _resumed("com.android.launcher3")}))
    assert _ready("--package", PACKAGE) == exitcodes.NOT_REACHABLE
    assert _cause(capsys).startswith(PACKAGE + " runs, but com.android.launcher3 is in the foreground")


def test_ready_when_the_port_does_not_answer(closed_port, monkeypatch, capsys):
    FakeAdb(monkeypatch, dict(HEALTHY, **{LOGCAT_PID: _ready_line(closed_port)}))
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
    assert not any(call.endswith("get-state") or call in (PM_PATH, TAIL, PIDOF) for call in adb.calls)


def _other_server_text(where):
    return "nother server answers on {}: it answered 404 to GET /api/v1/_meta, a route that every Lustro " \
        "server has.\n".format(where)


def test_ready_waits_while_another_server_answers_on_the_port_and_prints_the_cause(other_server, monkeypatch, capsys):
    # The app is stopped, and another app's server listens on the port that its prefs keep. The
    # forward to that port has another local port, as when another device's forward holds 8080.
    port = other_server.server_address[1]
    FakeAdb(monkeypatch, dict(HEALTHY, **{PIDOF: "", RUN_AS: _prefs(8080)}))
    monkeypatch.setattr(cli, "forwarded_ports", lambda device_port, device: [port] if device_port == 8080 else [])
    started = time.monotonic()
    assert _ready("--package", PACKAGE) == exitcodes.NOT_REACHABLE
    assert time.monotonic() - started >= 0.2
    assert other_server.requests > 1
    cause = _cause(capsys)
    assert cause.startswith(PACKAGE + " is not running: start it.")
    assert cause.endswith(" A" + _other_server_text("port 8080 of the device (through http://127.0.0.1:{})".format(port)))


def test_ready_says_that_another_server_answers_when_adb_finds_no_cause(other_server, monkeypatch, capsys):
    # Without adb's forward, a command connects to the app's port on this computer.
    port = other_server.server_address[1]
    FakeAdb(monkeypatch, dict(HEALTHY, **{RUN_AS: _prefs(port)}))
    assert _ready("--package", PACKAGE) == exitcodes.NOT_REACHABLE
    assert _cause(capsys) == "a" + _other_server_text("http://127.0.0.1:{}".format(port))


def test_ready_with_a_port_where_another_server_answers_and_no_adb_checks(other_server, monkeypatch, capsys):
    adb = FakeAdb(monkeypatch, HEALTHY)
    port = other_server.server_address[1]
    assert _ready("--port", str(port), "--token", TOKEN) == exitcodes.NOT_REACHABLE
    assert _cause(capsys) == "a" + _other_server_text("http://127.0.0.1:{}".format(port))
    assert not any(call.endswith("get-state") or call in (PM_PATH, TAIL, PIDOF) for call in adb.calls)


def test_ready_finds_the_app_that_starts_while_another_server_answers_on_its_old_port(
    server, other_server, monkeypatch, capsys
):
    # The app starts on another port, which its new ready line names.
    old, new = other_server.server_address[1], server.server_address[1]
    FakeAdb(monkeypatch, {LOGCAT: lambda earlier: _ready_line(new if earlier >= 2 else old)})
    assert cli.main(["ready", "--timeout", "5"]) == exitcodes.OK
    assert capsys.readouterr().out == "http://127.0.0.1:{}\n".format(new)
    assert other_server.requests == 2


def test_ready_with_the_port_of_a_scripts_forward_gets_the_cause_from_adb(closed_port, monkeypatch, capsys):
    # A script that forwards the app's port itself passes the local port with --port.
    FakeAdb(monkeypatch, dict(HEALTHY, **{PIDOF: ""}))
    assert _ready("--package", PACKAGE, "--port", str(closed_port), "--token", TOKEN) == exitcodes.NOT_REACHABLE
    assert _cause(capsys).startswith(PACKAGE + " is not running: start it.")


def test_ready_tries_again_while_the_server_is_busy(server, capsys):
    server.status = 503
    server.meta = {"error": "unavailable", "message": "busy"}
    started = time.monotonic()
    assert _ready("--port", str(server.server_address[1]), "--token", TOKEN) == exitcodes.ERROR
    assert time.monotonic() - started >= 0.2
    # The server answered, so the error is its answer, not "did not answer".
    assert capsys.readouterr().err == "error: 503 unavailable: busy\n"


def test_ready_stops_at_an_error_that_a_new_try_can_not_change(server, capsys):
    started = time.monotonic()
    assert cli.main(["ready", "--timeout", "30", "--port", str(server.server_address[1]), "--token", "wrong"]) == (
        exitcodes.ERROR
    )
    assert time.monotonic() - started < 5
    assert capsys.readouterr().err.startswith("error: 401 unauthorized: missing token")


def test_ready_rejects_a_timeout_that_is_not_positive(capsys):
    with pytest.raises(SystemExit) as excinfo:
        cli.main(["ready", "--timeout", "0"])
    assert excinfo.value.code == exitcodes.USAGE


# ── the exit codes of the other commands ───────────────────────────────────────


def test_a_command_exits_with_the_code_of_the_cause(monkeypatch, capsys):
    FakeAdb(monkeypatch, dict(HEALTHY, **{APK: NO_RUNTIME}))
    assert cli.main(["net", "list", "--package", PACKAGE]) == exitcodes.NO_RUNTIME
    assert capsys.readouterr().err.startswith("error: the installed build of {}".format(PACKAGE))


def test_a_command_whose_connection_fails_gets_the_cause_from_adb(closed_port, monkeypatch, capsys):
    FakeAdb(monkeypatch, dict(HEALTHY, **{PIDOF: "", RUN_AS: _prefs(closed_port)}))
    assert cli.main(["meta", "--package", PACKAGE]) == exitcodes.NOT_REACHABLE
    assert capsys.readouterr().err.startswith("error: {} is not running".format(PACKAGE))


def test_a_command_with_the_port_of_a_scripts_forward_gets_the_cause_from_adb(closed_port, monkeypatch, capsys):
    FakeAdb(monkeypatch, dict(HEALTHY, **{APK: NO_RUNTIME}))
    argv = ["--package", PACKAGE, "--host", "127.0.0.1", "--port", str(closed_port), "--token", TOKEN, "net", "list"]
    assert cli.main(argv) == exitcodes.NO_RUNTIME
    assert capsys.readouterr().err.startswith("error: the installed build of {}".format(PACKAGE))


def test_a_command_with_a_port_and_a_device_that_adb_does_not_find(closed_port, monkeypatch, capsys):
    FakeAdb(monkeypatch, {}, state=(1, "error: device 'emulator-5554' not found\n"))
    argv = ["--device", "emulator-5554", "--port", str(closed_port), "--token", TOKEN, "meta"]
    assert cli.main(argv) == exitcodes.NO_DEVICE
    assert capsys.readouterr().err.startswith("error: no device that adb can use")


def test_a_command_with_a_port_is_not_a_usage_error_for_more_than_one_device(closed_port, monkeypatch, capsys):
    # The command connects to the local port without adb: it needs no device selected.
    FakeAdb(monkeypatch, {}, state=(1, "error: more than one device/emulator\n"))
    argv = ["--package", PACKAGE, "--port", str(closed_port), "--token", TOKEN, "meta"]
    assert cli.main(argv) == exitcodes.NOT_REACHABLE
    assert capsys.readouterr().err.startswith("error: connection_failed:")


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

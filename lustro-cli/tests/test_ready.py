"""``lustro ready``: it waits until the app's server answers, or prints the cause
and exits with its code. adb is a fake that answers each command from a table."""

from __future__ import annotations

import json
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import adb_parts
import pytest
from adb_parts import TCP_HEADER, listening
from fake_apk import APP_ENTRIES, RUNTIME_ENTRIES, apk_bytes, tail_of

import lustro_cli
from lustro_cli import cli, discovery, exitcodes, mcp

TOKEN = "tok"
PACKAGE = "com.example.app"
APP_UID = 10213
OTHER_UID = 10217
OTHER = "com.other.app"
# The dump of the log, with the PID of each line. With --package, discovery keeps the
# lines of the app's process, 4242 in PIDS.
LOGCAT = "adb logcat -d -v threadtime -s LustroToken"
LOGCAT_PIDS = LOGCAT
PM_PATH = "adb shell pm path " + PACKAGE
APK = "/data/app/~~Xy==/com.example.app-Ab==/base.apk"
TAIL = "adb exec-out tail -c 65557 '{}'".format(APK)
PIDOF = "adb shell pidof {} || true".format(PACKAGE)
DUMPSYS = "adb shell dumpsys activity activities | grep ResumedActivity"
# The commands of the adb call that reads the app (discovery.read_app): the prefs and the
# status of the run-as process, which has the app's uid, and the sockets.
RUN_AS = "adb shell run-as {} cat shared_prefs/lustro_debug.xml /proc/self/status 2>/dev/null".format(PACKAGE)
PROC_NET = "adb shell cat /proc/net/tcp /proc/net/tcp6 2>/dev/null"
# The PIDs of the app, with the uid of each (discovery.app_process_uids).
PIDS = "adb shell for p in $(pidof {}); do echo $p $(stat -c %u /proc/$p 2>/dev/null); done".format(PACKAGE)
# The uid of the process that logged a line, 4242 in _app_line.
STAT = "adb shell stat -c %u /proc/4242 2>/dev/null"


def _resumed(package):
    return "  topResumedActivity=ActivityRecord{{1a2b u0 {}/.MainActivity t12}}\n".format(package)


def _ready_line(port, token=TOKEN):
    return "I LustroToken: Lustro ready endpoint=http://127.0.0.1:{} token={}\n".format(port, token)


def _app_line(port, token=TOKEN, pid=4242):
    """The ready line of a process in the ``threadtime`` format, of the app's by default."""
    return "11-21 10:59:08.064  {}  {} ".format(pid, pid + 31) + _ready_line(port, token)


WITH_RUNTIME = apk_bytes(APP_ENTRIES + RUNTIME_ENTRIES)
NO_RUNTIME = apk_bytes(APP_ENTRIES)

# adb's answers for an installed debug build that runs in the foreground. A test adds
# who listens on which port, with PROC_NET.
HEALTHY = {
    PM_PATH: "package:{}\n".format(APK),
    APK: WITH_RUNTIME,
    PIDOF: "4242\n",
    DUMPSYS: _resumed(PACKAGE),
    PIDS: "4242 {}\n".format(APP_UID),
    STAT: "{}\n".format(APP_UID),
}


def _app_listens(port, uid=APP_UID):
    """The answer of PROC_NET when ``uid`` listens on ``port``, the app's uid by default."""
    return listening((port, uid))


def _prefs(port, token=TOKEN, uid=APP_UID):
    """The app's prefs, with the token and the port of its last bind, and the status of the
    run-as process that read them, with the app's uid."""
    return (
        '<map><string name="lustro_token">{}</string><string name="lustro_host">127.0.0.1</string>'
        '<int name="lustro_port" value="{}" /></map>\nName:\tcat\nUid:\t{uid}\t{uid}\t{uid}\t{uid}\n'.format(
            token, port, uid=uid
        )
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
        parts = adb_parts.split(cmd)
        if parts is not None:
            return adb_parts.join(self.run(part, timeout) for part in parts)
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
        self.server.tokens.append(self.headers.get("Authorization"))
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
    """A server that answers ``GET _meta``; set ``.meta`` and ``.status`` to change the answer.
    ``.tokens`` has the Authorization header of each request."""
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), _MetaHandler)
    httpd.meta = dict(_MetaHandler.meta)
    httpd.status = 200
    httpd.tokens = []
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
    adb = FakeAdb(
        monkeypatch,
        dict(HEALTHY, **{LOGCAT_PIDS: lambda earlier: _app_line(port) if earlier >= 3 else "", PROC_NET: _app_listens(port)}),
    )
    assert cli.main(["ready", "--timeout", "5", "--package", PACKAGE]) == exitcodes.OK
    assert capsys.readouterr().out == "http://127.0.0.1:{}\n".format(port)
    assert adb.count(LOGCAT_PIDS) == 4
    # With --package, the checks of the cause run once, after the first try, and find
    # nothing that ends the wait.
    assert adb.count(TAIL) == 1
    assert adb.count("adb get-state") == 1


def test_ready_without_package_makes_no_checks_while_it_waits(server, monkeypatch, capsys):
    port = server.server_address[1]
    adb = FakeAdb(
        monkeypatch,
        dict(HEALTHY, **{LOGCAT: lambda earlier: _app_line(port) if earlier >= 3 else "", PROC_NET: _app_listens(port)}),
    )
    assert cli.main(["ready", "--timeout", "5"]) == exitcodes.OK
    assert capsys.readouterr().out == "http://127.0.0.1:{}\n".format(port)
    assert adb.count(TAIL) == 0
    assert not any(call.endswith("get-state") for call in adb.calls)


def test_ready_tries_again_while_the_port_does_not_answer(server, closed_port, monkeypatch, capsys):
    port = server.server_address[1]
    # A line from an earlier start names a port that does not answer; the new start logs another.
    outputs = dict(HEALTHY, **{LOGCAT: lambda earlier: _app_line(port if earlier >= 2 else closed_port), PROC_NET: _app_listens(port)})
    FakeAdb(monkeypatch, outputs)
    assert cli.main(["ready", "--timeout", "5"]) == exitcodes.OK
    assert capsys.readouterr().out == "http://127.0.0.1:{}\n".format(port)


def test_ready_with_package_reads_the_prefs_and_not_the_log(server, monkeypatch, capsys):
    port = server.server_address[1]
    adb = FakeAdb(monkeypatch, dict(HEALTHY, **{RUN_AS: _prefs(port), LOGCAT_PIDS: _app_line(port), PROC_NET: _app_listens(port)}))
    assert cli.main(["ready", "--timeout", "5", "--package", PACKAGE]) == exitcodes.OK
    assert capsys.readouterr().out == "http://127.0.0.1:{}\n".format(port)
    assert adb.count(RUN_AS) == 1
    assert adb.count(LOGCAT_PIDS) == 0
    assert adb.count(PIDOF) == 0


def test_ready_tries_again_after_a_401_for_the_token_of_the_apps_last_process(server, monkeypatch, capsys):
    # A new process answers before its token is in the prefs: the first try and the
    # client's second read of the prefs get the token of the last process.
    port = server.server_address[1]
    adb = FakeAdb(
        monkeypatch,
        dict(HEALTHY, **{RUN_AS: lambda earlier: _prefs(port, "old" if earlier < 2 else TOKEN), PROC_NET: _app_listens(port)}),
    )
    assert cli.main(["ready", "--timeout", "5", "--package", PACKAGE]) == exitcodes.OK
    assert capsys.readouterr().out == "http://127.0.0.1:{}\n".format(port)
    assert adb.count(RUN_AS) == 3


def test_ready_without_package_tries_again_after_a_401_for_a_token_from_the_log(server, monkeypatch, capsys):
    # The last line in the log is from an earlier start, with a token that is no longer
    # valid; the new start logs a line with the current one.
    port = server.server_address[1]
    outputs = dict(HEALTHY, **{LOGCAT: lambda earlier: _app_line(port, "old" if earlier < 2 else TOKEN), PROC_NET: _app_listens(port)})
    adb = FakeAdb(monkeypatch, outputs)
    assert cli.main(["ready", "--timeout", "5"]) == exitcodes.OK
    assert capsys.readouterr().out == "http://127.0.0.1:{}\n".format(port)
    assert adb.count(LOGCAT) == 3


def test_ready_with_a_401_until_the_timeout_prints_the_cause_from_adb(server, monkeypatch, capsys):
    # The prefs keep the token of the app's last process, and the app is in the background
    # now: its server, which still drains, answers 401 to that token on each try.
    port = server.server_address[1]
    FakeAdb(
        monkeypatch,
        dict(HEALTHY, **{RUN_AS: _prefs(port, "old"), DUMPSYS: _resumed("com.other.app"), PROC_NET: _app_listens(port)}),
    )
    started = time.monotonic()
    assert _ready("--package", PACKAGE) == exitcodes.NOT_REACHABLE
    assert time.monotonic() - started >= 0.2
    err = capsys.readouterr().err
    assert err.startswith("error: {} runs, but com.other.app is in the foreground".format(PACKAGE)), err
    assert "answered: 401 unauthorized" in err


def test_ready_with_a_401_until_the_timeout_and_no_cause_from_adb_exits_1(server, monkeypatch, capsys):
    port = server.server_address[1]
    FakeAdb(monkeypatch, dict(HEALTHY, **{RUN_AS: _prefs(port, "old"), PROC_NET: _app_listens(port)}))
    started = time.monotonic()
    assert _ready("--package", PACKAGE) == exitcodes.ERROR
    assert time.monotonic() - started >= 0.2
    assert capsys.readouterr().err.startswith("error: 401 unauthorized: missing token")


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


def _stops_at_once(argv, capsys):
    """Runs ``ready`` with a long timeout, checks that it ends at once, and returns
    its exit code and the cause that it prints."""
    started = time.monotonic()
    code = cli.main(["ready", "--timeout", "30", *argv])
    assert time.monotonic() - started < 5
    out, err = capsys.readouterr()
    assert out == ""
    assert err.startswith("error: "), err
    return code, err[len("error: ") :]


def test_ready_stops_at_once_when_the_app_is_not_installed(monkeypatch, capsys):
    adb = FakeAdb(monkeypatch, {})
    code, cause = _stops_at_once(["--package", PACKAGE], capsys)
    assert code == exitcodes.NOT_INSTALLED
    assert cause.startswith(PACKAGE + " is not installed on the device")
    assert adb.count(PM_PATH) == 2


def test_ready_checks_again_when_the_first_try_finds_no_device(monkeypatch, capsys):
    # An emulator that starts: adb finds no device on the first try, and then finds it.
    adb = FakeAdb(monkeypatch, {})
    states = iter([(1, "error: no devices/emulators found\n")])
    run_status = adb.run_status

    def changing_state(cmd, timeout=10.0):
        if cmd[-1] == "get-state":
            adb.state = next(states, (0, "device\n"))
        return run_status(cmd, timeout)

    monkeypatch.setattr(discovery, "_run_status", changing_state)
    code, cause = _stops_at_once(["--package", PACKAGE], capsys)
    assert code == exitcodes.NOT_INSTALLED
    assert cause.startswith(PACKAGE + " is not installed on the device")
    assert adb.count("adb get-state") == 2


def test_ready_checks_again_while_the_package_manager_does_not_answer(monkeypatch, capsys):
    # The device boots: `pm path` fails with an error text, and then answers with nothing.
    adb = FakeAdb(monkeypatch, {PM_PATH: lambda earlier: "Error: Can't find service: package\n" if earlier < 4 else None})
    code, cause = _stops_at_once(["--package", PACKAGE], capsys)
    assert code == exitcodes.NOT_INSTALLED
    assert cause.startswith(PACKAGE + " is not installed on the device")


def test_ready_stops_at_once_when_the_package_manager_starts_during_the_early_check(monkeypatch, capsys):
    # The package manager does not run yet when the early check asks it, and answers when
    # the checks run just after.
    answers = ["Error: Can't find service: package\n"]
    adb = FakeAdb(monkeypatch, {PM_PATH: lambda earlier: answers[earlier] if earlier < len(answers) else None})
    code, cause = _stops_at_once(["--package", PACKAGE], capsys)
    assert code == exitcodes.NOT_INSTALLED
    assert cause.startswith(PACKAGE + " is not installed on the device")


def test_ready_checks_only_once_when_the_package_manager_answers(monkeypatch, capsys):
    # The app is installed and does not run yet: nothing in the checks ends the wait.
    adb = FakeAdb(monkeypatch, dict(HEALTHY, **{PIDOF: ""}))
    assert _ready("--package", PACKAGE) == exitcodes.NOT_REACHABLE
    assert _cause(capsys).startswith(PACKAGE + " is not running")
    # The early check asks the package manager, then checks; and the check at the timeout.
    assert adb.count(PM_PATH) == 3


def test_ready_with_a_short_timeout_when_the_app_is_not_installed(monkeypatch, capsys):
    FakeAdb(monkeypatch, {})
    assert _ready("--package", PACKAGE) == exitcodes.NOT_INSTALLED
    assert capsys.readouterr().err.startswith("error: {} is not installed on the device".format(PACKAGE))


@pytest.mark.parametrize("command", [["ready", "--timeout", "30"], ["meta"]])
def test_the_server_of_another_app_is_not_used_for_a_package_that_is_not_installed(server, command, monkeypatch, capsys):
    # Another app answers on the port of the last ready line in the log, which it logged.
    FakeAdb(monkeypatch, {LOGCAT: _ready_line(server.server_address[1]), PIDOF: ""})
    assert cli.main(command + ["--package", PACKAGE]) == exitcodes.NOT_INSTALLED
    out, err = capsys.readouterr()
    assert out == ""
    assert PACKAGE + " is not installed on the device" in err


def test_ready_stops_at_once_when_the_build_has_no_lustro_runtime(monkeypatch, capsys):
    adb = FakeAdb(monkeypatch, dict(HEALTHY, **{APK: NO_RUNTIME}))
    code, cause = _stops_at_once(["--package", PACKAGE], capsys)
    assert code == exitcodes.NO_RUNTIME
    assert cause.startswith("the installed build of {} does not contain the Lustro runtime".format(PACKAGE))
    assert "from before the app added Lustro" in cause
    # One try, and one read of the APK.
    assert adb.count(TAIL) == 1
    assert adb.count(LOGCAT_PIDS) == 1


def test_ready_for_a_no_op_build_with_the_prefs_of_an_earlier_debug_build(closed_port, monkeypatch, capsys):
    # The earlier build's prefs, which an install over it keeps, name a port where nothing
    # listens now: the cause is the build, not the port.
    adb = FakeAdb(monkeypatch, dict(HEALTHY, **{APK: NO_RUNTIME, RUN_AS: _prefs(closed_port)}))
    code, cause = _stops_at_once(["--package", PACKAGE], capsys)
    assert code == exitcodes.NO_RUNTIME
    assert "does not contain the Lustro runtime" in cause
    assert adb.count(RUN_AS) == 1


def test_ready_stops_at_once_for_a_build_without_the_runtime_behind_another_server(other_server, monkeypatch, capsys):
    port = other_server.server_address[1]
    FakeAdb(monkeypatch, dict(HEALTHY, **{APK: NO_RUNTIME, RUN_AS: _prefs(port), PROC_NET: _app_listens(port, OTHER_UID)}))
    code, cause = _stops_at_once(["--package", PACKAGE], capsys)
    assert code == exitcodes.NO_RUNTIME
    assert "does not contain the Lustro runtime" in cause
    # The other app's server does not get the token.
    assert other_server.requests == 0


def test_ready_stops_at_once_with_the_port_of_a_scripts_forward(closed_port, monkeypatch, capsys):
    FakeAdb(monkeypatch, dict(HEALTHY, **{APK: NO_RUNTIME}))
    code, cause = _stops_at_once(["--package", PACKAGE, "--port", str(closed_port), "--token", TOKEN], capsys)
    assert code == exitcodes.NO_RUNTIME
    assert "does not contain the Lustro runtime" in cause


def test_ready_without_package_waits_for_a_foreground_app_without_the_runtime(monkeypatch, capsys):
    # The app in the foreground can be the launcher while the user's app starts.
    adb = FakeAdb(monkeypatch, {DUMPSYS: _resumed("com.android.launcher3"), "adb shell pm path com.android.launcher3":
                                "package:{}\n".format(APK), APK: NO_RUNTIME})
    started = time.monotonic()
    assert _ready() == exitcodes.NOT_REACHABLE
    assert time.monotonic() - started >= 0.2
    assert _cause(capsys).startswith("the app in the foreground, com.android.launcher3, does not contain")
    assert adb.count(TAIL) == 1


def test_ready_waits_for_an_app_that_is_not_running(closed_port, monkeypatch, capsys):
    # The prefs keep the token and the port of the app's last start. The ready line in the
    # log is of a process that no longer runs, so the CLI does not read the log.
    adb = FakeAdb(monkeypatch, dict(HEALTHY, **{PIDOF: "", LOGCAT: _ready_line(closed_port), RUN_AS: _prefs(closed_port)}))
    started = time.monotonic()
    assert _ready("--package", PACKAGE) == exitcodes.NOT_REACHABLE
    assert time.monotonic() - started >= 0.2
    assert _cause(capsys).startswith(PACKAGE + " is not running")
    assert adb.count(LOGCAT) == 0
    assert adb.count(RUN_AS) > 1


def test_ready_when_the_app_is_in_the_background(monkeypatch, capsys):
    FakeAdb(monkeypatch, dict(HEALTHY, **{DUMPSYS: _resumed("com.android.launcher3")}))
    assert _ready("--package", PACKAGE) == exitcodes.NOT_REACHABLE
    assert _cause(capsys).startswith(PACKAGE + " runs, but com.android.launcher3 is in the foreground")


def test_ready_when_the_port_does_not_answer(closed_port, monkeypatch, capsys):
    # The app's uid listens on the port of the device, and the forward does not reach it.
    FakeAdb(monkeypatch, dict(HEALTHY, **{LOGCAT_PIDS: _app_line(closed_port), PROC_NET: _app_listens(closed_port)}))
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


def test_ready_waits_while_another_app_listens_on_the_port_and_prints_the_cause(other_server, monkeypatch, capsys):
    # The app is stopped, and another app's server listens on the port that its prefs keep. The
    # forward to that port has another local port, as when another device's forward holds 8080.
    port = other_server.server_address[1]
    FakeAdb(monkeypatch, dict(HEALTHY, **{PIDOF: "", PIDS: "", RUN_AS: _prefs(8080), PROC_NET: _app_listens(8080, OTHER_UID)}))
    monkeypatch.setattr(cli, "forwarded_ports", lambda device_port, device: [port] if device_port == 8080 else [])
    started = time.monotonic()
    assert _ready("--package", PACKAGE) == exitcodes.NOT_REACHABLE
    assert time.monotonic() - started >= 0.2
    assert other_server.requests == 0
    cause = _cause(capsys)
    assert cause.startswith(PACKAGE + " is not running: start it."), cause
    assert " Port 8080 of the device is held by a process with uid {}, not by {}".format(OTHER_UID, PACKAGE) in cause


def test_ready_says_that_another_server_answers_when_adb_finds_no_cause(other_server, monkeypatch, capsys):
    # Without adb's forward, a command connects to the app's port on this computer, where
    # another server answers. The app's uid listens on that port of the device.
    port = other_server.server_address[1]
    FakeAdb(monkeypatch, dict(HEALTHY, **{RUN_AS: _prefs(port), PROC_NET: _app_listens(port)}))
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
    outputs = dict(HEALTHY, **{LOGCAT: lambda earlier: _app_line(new if earlier >= 2 else old), PROC_NET: listening((old, APP_UID), (new, APP_UID))})
    FakeAdb(monkeypatch, outputs)
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


def test_a_command_with_the_token_of_the_apps_last_process_reads_the_prefs_again_after_a_401(
    server, monkeypatch, capsys
):
    port = server.server_address[1]
    adb = FakeAdb(
        monkeypatch,
        dict(HEALTHY, **{RUN_AS: lambda earlier: _prefs(port, "old" if earlier < 1 else TOKEN), PROC_NET: _app_listens(port)}),
    )
    assert cli.main(["meta", "--package", PACKAGE, "--json"]) == exitcodes.OK
    assert json.loads(capsys.readouterr().out)["libraryVersion"] == "0.1.0"
    assert adb.count(RUN_AS) == 2
    assert adb.count(LOGCAT_PIDS) == 0


def test_a_command_with_a_401_for_a_discovered_token_gets_the_cause_from_adb(server, monkeypatch, capsys):
    port = server.server_address[1]
    FakeAdb(monkeypatch, dict(HEALTHY, **{RUN_AS: _prefs(port, "old"), DUMPSYS: _resumed("com.other.app")}))
    assert cli.main(["meta", "--package", PACKAGE]) == exitcodes.NOT_REACHABLE
    assert capsys.readouterr().err.startswith("error: {} runs, but com.other.app is in the foreground".format(PACKAGE))


def test_a_command_with_a_401_for_the_token_flag_gets_no_cause_from_adb(server, monkeypatch, capsys):
    adb = FakeAdb(monkeypatch, dict(HEALTHY, **{DUMPSYS: _resumed("com.other.app")}))
    argv = ["meta", "--package", PACKAGE, "--port", str(server.server_address[1]), "--token", "wrong"]
    assert cli.main(argv) == exitcodes.ERROR
    assert capsys.readouterr().err.startswith("error: 401 unauthorized")
    assert "adb get-state" not in adb.calls


# ── a discovered token goes only to a server of the app that owns it ──────────

PM_LIST = "adb shell pm list packages -U"
# A process of another app: it logged a ready line, and it can listen on a port.
OTHER_PID = 6666
OTHER_STAT = "adb shell stat -c %u /proc/{} 2>/dev/null".format(OTHER_PID)


def _packages(*lines):
    return "".join(line + "\n" for line in lines)


def _two_apps(port, listener=OTHER_UID, packages=None):
    """adb's answers while ``listener`` holds the app's port, right after the app started:
    the app runs in the foreground, and its prefs have its token and its port."""
    if packages is None:
        packages = _packages("package:{} uid:{}".format(PACKAGE, APP_UID), "package:{} uid:{}".format(OTHER, OTHER_UID))
    return dict(HEALTHY, **{RUN_AS: _prefs(port), PROC_NET: _app_listens(port, listener), PM_LIST: packages})


def _held_message(port, other=OTHER, other_uid=OTHER_UID):
    return (
        "port {} of the device is held by {} (uid {}), not by {} (uid {}), so the CLI did not send it the token of "
        "{}.".format(port, other, other_uid, PACKAGE, APP_UID, PACKAGE)
    )


def test_with_package_the_token_does_not_go_to_another_app_on_the_apps_port(server, monkeypatch, capsys):
    port = server.server_address[1]
    FakeAdb(monkeypatch, _two_apps(port))
    assert cli.main(["meta", "--package", PACKAGE]) == exitcodes.NOT_REACHABLE
    err = capsys.readouterr().err
    assert err.startswith("error: " + _held_message(port)), err
    assert "`lustro ready --package {}` waits for that.".format(PACKAGE) in err
    assert "its own serverPort" in err
    assert server.tokens == []


def test_with_package_the_token_goes_to_the_apps_own_server(server, monkeypatch, capsys):
    port = server.server_address[1]
    adb = FakeAdb(monkeypatch, _two_apps(port, listener=APP_UID))
    assert cli.main(["meta", "--package", PACKAGE, "--json"]) == exitcodes.OK
    assert json.loads(capsys.readouterr().out)["libraryVersion"] == "0.1.0"
    assert server.tokens == ["Bearer " + TOKEN]
    # The uids come in the adb call that reads the prefs.
    assert adb.count(RUN_AS) == 1
    assert adb.count(PROC_NET) == 1
    assert PM_LIST not in adb.calls


def test_with_package_a_process_with_the_apps_name_is_not_the_app(server, monkeypatch, capsys):
    # Another app can name its process as the app, with android:process: pidof then finds
    # it too. The app's uid comes from run-as, and pidof does not run.
    port = server.server_address[1]
    outputs = dict(_two_apps(port), **{PIDS: "4242 {}\n{} {}\n".format(APP_UID, OTHER_PID, OTHER_UID)})
    adb = FakeAdb(monkeypatch, outputs)
    assert cli.main(["meta", "--package", PACKAGE]) == exitcodes.NOT_REACHABLE
    assert capsys.readouterr().err.startswith("error: " + _held_message(port))
    assert server.tokens == []
    assert PIDS not in adb.calls


def test_with_package_the_line_of_a_process_with_the_apps_name_is_not_the_apps(server, monkeypatch, capsys):
    # A build that is not debuggable: run-as fails, and the CLI keeps the lines of the
    # processes that pidof finds. One of them is another app's, which logged a line with its
    # own token for the app's port: its uid is not the uid that listens there.
    port = server.server_address[1]
    outputs = dict(
        _two_apps(port, listener=APP_UID),
        **{
            RUN_AS: None,
            PIDS: "4242 {}\n{} {}\n".format(APP_UID, OTHER_PID, OTHER_UID),
            LOGCAT: _app_line(port) + _app_line(port, "x", pid=OTHER_PID),
        }
    )
    FakeAdb(monkeypatch, outputs)
    assert cli.main(["meta", "--package", PACKAGE]) == exitcodes.NOT_REACHABLE
    err = capsys.readouterr().err
    assert err.startswith(
        "error: port {} of the device is held by {} (uid {}), not by {} (uid {})".format(
            port, PACKAGE, APP_UID, PACKAGE, OTHER_UID
        )
    ), err
    assert server.tokens == []


def test_with_package_the_line_of_the_apps_process_goes_to_its_server(server, monkeypatch, capsys):
    port = server.server_address[1]
    outputs = dict(_two_apps(port, listener=APP_UID), **{RUN_AS: None, LOGCAT: _app_line(port)})
    adb = FakeAdb(monkeypatch, outputs)
    assert cli.main(["meta", "--package", PACKAGE, "--json"]) == exitcodes.OK
    assert server.tokens == ["Bearer " + TOKEN]
    # The read of the prefs, the PIDs with their uids, and the log: as before, three adb calls.
    assert (adb.count(RUN_AS), adb.count(PIDS), adb.count(LOGCAT), adb.count(PIDOF)) == (1, 1, 1, 0)


def test_with_package_no_token_goes_to_a_port_where_nothing_listens(server, monkeypatch, capsys):
    port = server.server_address[1]
    FakeAdb(monkeypatch, dict(_two_apps(port), **{PROC_NET: TCP_HEADER}))
    assert cli.main(["meta", "--package", PACKAGE]) == exitcodes.NOT_REACHABLE
    err = capsys.readouterr().err
    assert err.startswith(
        "error: connection_failed: nothing listens on port {} of the device, so the CLI did not send the token of {} "
        "there".format(port, PACKAGE)
    ), err
    assert server.tokens == []


def test_with_package_no_token_goes_out_when_adb_can_not_tell_who_listens(server, monkeypatch, capsys):
    port = server.server_address[1]
    FakeAdb(monkeypatch, dict(_two_apps(port), **{PROC_NET: "cat: /proc/net/tcp: Permission denied\n"}))
    assert cli.main(["meta", "--package", PACKAGE]) == exitcodes.NOT_REACHABLE
    err = capsys.readouterr().err
    assert err.startswith("error: token_withheld: adb can't tell which app listens on port {}".format(port)), err
    assert server.tokens == []


def test_another_app_on_the_port_comes_after_the_cause_from_adb(server, monkeypatch, capsys):
    # The app is in the background, and another app binds its port.
    port = server.server_address[1]
    FakeAdb(monkeypatch, dict(_two_apps(port), **{DUMPSYS: _resumed("com.android.launcher3")}))
    assert cli.main(["meta", "--package", PACKAGE]) == exitcodes.NOT_REACHABLE
    err = capsys.readouterr().err
    assert err.startswith("error: {} runs, but com.android.launcher3 is in the foreground".format(PACKAGE)), err
    assert " Port {} of the device is held by {} (uid {})".format(port, OTHER, OTHER_UID) in err
    assert server.tokens == []


def test_a_uid_that_pm_does_not_list_is_named_by_its_number(server, monkeypatch, capsys):
    port = server.server_address[1]
    FakeAdb(monkeypatch, _two_apps(port, packages=_packages("package:{} uid:{}".format(PACKAGE, APP_UID))))
    assert cli.main(["meta", "--package", PACKAGE]) == exitcodes.NOT_REACHABLE
    assert "held by a process with uid {}, not by {}".format(OTHER_UID, PACKAGE) in capsys.readouterr().err


def test_the_name_of_the_other_app_comes_from_pm_also_after_an_error_for_another_user(server, monkeypatch, capsys):
    # A device with a second user that the shell can't access: pm prints the error, and
    # then the packages of the other users, with a uid for each user that has the app.
    port = server.server_address[1]
    packages = _packages(
        "java.lang.SecurityException: Shell does not have permission to access user 150",
        "package:{} uid:{},9510213".format(PACKAGE, APP_UID),
        "package:{} uid:{},9510217".format(OTHER, OTHER_UID),
    )
    FakeAdb(monkeypatch, _two_apps(port, packages=packages))
    assert cli.main(["meta", "--package", PACKAGE]) == exitcodes.NOT_REACHABLE
    assert "held by {} (uid {})".format(OTHER, OTHER_UID) in capsys.readouterr().err


DUMPSYS_PACKAGES = "adb shell dumpsys package packages"


def test_the_name_of_the_other_app_comes_from_dumpsys_where_pm_has_no_uids(server, monkeypatch, capsys):
    # pm of API 24 and 25 has no -U. The app ID in dumpsys is the uid in user 0, and the
    # uid in user 10 is 10 * 100000 more.
    port = server.server_address[1]
    dumpsys = (
        "Packages:\n"
        "  Package [{app}] (2f1a):\n    userId={app_uid}\n    pkg=Package{{1 {app}}}\n"
        "  Package [{other}] (3b2c):\n    userId={other_uid}\n    sharedUser=null\n"
    ).format(app=PACKAGE, app_uid=APP_UID, other=OTHER, other_uid=OTHER_UID)
    outputs = dict(
        _two_apps(port, listener=1000000 + OTHER_UID, packages="Error: Unknown option: -U\n"),
        **{DUMPSYS_PACKAGES: dumpsys}
    )
    FakeAdb(monkeypatch, outputs)
    assert cli.main(["meta", "--package", PACKAGE]) == exitcodes.NOT_REACHABLE
    assert "held by {} (uid {})".format(OTHER, 1000000 + OTHER_UID) in capsys.readouterr().err


@pytest.mark.parametrize("given", [["--token", "wrong"], []])
def test_a_token_that_the_user_gives_is_sent_without_a_check(server, given, monkeypatch, capsys):
    port = server.server_address[1]
    if not given:
        monkeypatch.setenv("LUSTRO_TOKEN", "wrong")
    adb = FakeAdb(monkeypatch, _two_apps(port))
    assert cli.main(["meta", "--package", PACKAGE] + given) == exitcodes.ERROR
    assert capsys.readouterr().err.startswith("error: 401 unauthorized")
    assert server.tokens == ["Bearer wrong"]
    assert PM_LIST not in adb.calls


def test_a_port_that_the_user_gives_gets_no_check(server, monkeypatch, capsys):
    # --port is a local port, such as the one of a script's own forward.
    port = server.server_address[1]
    adb = FakeAdb(monkeypatch, _two_apps(port))
    assert cli.main(["meta", "--package", PACKAGE, "--port", str(port)]) == exitcodes.OK
    assert server.tokens == ["Bearer " + TOKEN]
    assert adb.count(PROC_NET) == 1


def test_ready_waits_while_another_app_holds_the_port_and_names_it_at_the_timeout(server, monkeypatch, capsys):
    port = server.server_address[1]
    FakeAdb(monkeypatch, _two_apps(port))
    started = time.monotonic()
    assert _ready("--package", PACKAGE) == exitcodes.NOT_REACHABLE
    assert time.monotonic() - started >= 0.2
    err = capsys.readouterr().err
    assert err.startswith("error: the app's server did not answer within 0.2 s: " + _held_message(port)), err
    assert "lustro ready" not in err
    assert server.tokens == []


def test_ready_answers_once_the_app_binds_the_port_after_the_other_app(server, monkeypatch, capsys):
    # The other app's server holds the port on the first tries, and then the app's own
    # server binds it.
    port = server.server_address[1]
    net = lambda earlier: _app_listens(port, OTHER_UID if earlier < 3 else APP_UID)  # noqa: E731
    FakeAdb(monkeypatch, dict(_two_apps(port), **{PROC_NET: net}))
    assert cli.main(["ready", "--timeout", "5", "--package", PACKAGE]) == exitcodes.OK
    assert capsys.readouterr().out == "http://127.0.0.1:{}\n".format(port)
    assert server.tokens == ["Bearer " + TOKEN]


def test_an_mcp_tool_names_the_other_app_and_sends_no_token(server, monkeypatch):
    port = server.server_address[1]
    FakeAdb(monkeypatch, _two_apps(port))
    args = cli.build_parser().parse_args(["mcp", "--package", PACKAGE])
    for name, default in cli.GLOBAL_DEFAULTS.items():
        setattr(args, name, getattr(args, name, default))
    result = mcp.Server(args)._tools_call({"name": "get_schema", "arguments": {}})
    assert result["isError"] is True
    assert result["content"][0]["text"].startswith("error: " + _held_message(port))
    assert server.tokens == []


# Without --package: the owner of a line is the process that logged it, and the owner of
# the prefs' token is the app in the foreground.


def _other_line(port, token="x", host="127.0.0.1"):
    """A ready line that another app logged, which any app can do."""
    return "11-21 11:00:00.000  {}  {} I LustroToken: Lustro ready endpoint=http://{}:{} token={}\n".format(
        OTHER_PID, OTHER_PID, host, port, token
    )


def _foreground_app(app_port, other_port, listeners):
    """The app in the foreground, with its prefs for ``app_port``, after another app logged a
    line for ``other_port``. ``listeners`` are the ``(port, uid)`` that listen."""
    return dict(
        HEALTHY,
        **{
            LOGCAT: _app_line(app_port) + _other_line(other_port),
            OTHER_STAT: "{}\n".format(OTHER_UID),
            RUN_AS: _prefs(app_port),
            PROC_NET: listening(*listeners),
            PM_LIST: _packages(
                "package:{} uid:{}".format(PACKAGE, APP_UID), "package:{} uid:{}".format(OTHER, OTHER_UID)
            ),
        }
    )


def test_the_line_of_another_app_does_not_get_the_token_of_the_app_in_the_foreground_after_a_401(
    server, monkeypatch, capsys
):
    # Another app logs a line for its own port, and its server answers 401 there. Before,
    # the CLI then sent the token of the app in the foreground to it.
    port = server.server_address[1]
    FakeAdb(monkeypatch, _foreground_app(8080, port, [(8080, APP_UID), (port, OTHER_UID)]))
    assert cli.main(["meta"]) == exitcodes.ERROR
    assert capsys.readouterr().err.startswith("error: 401 unauthorized")
    assert server.tokens == ["Bearer x"]


def test_the_line_of_another_app_for_the_apps_port_gives_way_to_the_prefs_of_the_app(server, monkeypatch, capsys):
    # The line names the port where the app in the foreground listens: the token of that
    # app, from its prefs, goes to its own server.
    port = server.server_address[1]
    adb = FakeAdb(monkeypatch, _foreground_app(port, port, [(port, APP_UID)]))
    assert cli.main(["meta", "--json"]) == exitcodes.OK
    assert server.tokens == ["Bearer " + TOKEN]
    assert adb.count(RUN_AS) == 1


def test_a_line_whose_process_has_ended_gives_way_to_the_prefs_of_the_app(server, monkeypatch, capsys):
    port = server.server_address[1]
    FakeAdb(monkeypatch, dict(_foreground_app(port, port, [(port, APP_UID)]), **{OTHER_STAT: ""}))
    assert cli.main(["meta", "--json"]) == exitcodes.OK
    assert server.tokens == ["Bearer " + TOKEN]


def test_the_line_of_another_app_on_the_apps_port_without_prefs_sends_no_token(server, monkeypatch, capsys):
    # A build that is not debuggable: run-as fails, and the line of another process names
    # the app's port.
    port = server.server_address[1]
    outputs = dict(_foreground_app(port, port, [(port, APP_UID)]), **{RUN_AS: None})
    FakeAdb(monkeypatch, outputs)
    assert cli.main(["meta"]) == exitcodes.NOT_REACHABLE
    err = capsys.readouterr().err
    assert err.startswith(
        "error: port {} of the device is held by {} (uid {}), not by {} (uid {})".format(
            port, PACKAGE, APP_UID, OTHER, OTHER_UID
        )
    ), err
    assert server.tokens == []


def test_the_line_of_the_app_in_the_foreground_needs_one_more_adb_call(server, monkeypatch, capsys):
    port = server.server_address[1]
    adb = FakeAdb(monkeypatch, dict(HEALTHY, **{LOGCAT: _app_line(port), PROC_NET: _app_listens(port)}))
    assert cli.main(["meta", "--json"]) == exitcodes.OK
    assert server.tokens == ["Bearer " + TOKEN]
    assert adb.count(STAT) == 1
    assert adb.count(PROC_NET) == 1
    assert RUN_AS not in adb.calls
    assert DUMPSYS not in adb.calls


def test_open_does_not_put_the_token_of_the_app_in_the_foreground_into_the_url_of_another_app(
    monkeypatch, capsys
):
    # The line of another app names its own port, where it listens.
    FakeAdb(monkeypatch, _foreground_app(8080, 9999, [(8080, APP_UID), (9999, OTHER_UID)]))
    assert cli.main(["open", "--print-only", "--no-forward"]) == exitcodes.OK
    assert capsys.readouterr().out == "http://localhost:9999/#lustro_token=x\n"


def test_open_with_the_line_of_another_app_on_a_port_where_nothing_listens_opens_the_app(monkeypatch, capsys):
    FakeAdb(monkeypatch, _foreground_app(8080, 9999, [(8080, APP_UID)]))
    assert cli.main(["open", "--print-only", "--no-forward"]) == exitcodes.OK
    assert capsys.readouterr().out == "http://localhost:8080/#lustro_token={}\n".format(TOKEN)


def test_open_does_not_put_the_token_of_the_prefs_into_the_url_of_a_host_that_they_do_not_name(monkeypatch, capsys):
    # The line names a host on the network, which the computer reaches without adb: the
    # prefs of the app in the foreground name loopback.
    outputs = dict(
        _foreground_app(8080, 9999, [(8080, APP_UID), (9999, OTHER_UID)]),
        **{LOGCAT: _app_line(8080) + _other_line(9999, host="192.168.1.50")}
    )
    FakeAdb(monkeypatch, outputs)
    assert cli.main(["open", "--print-only"]) == exitcodes.OK
    assert capsys.readouterr().out == "http://192.168.1.50:9999/#lustro_token=x\n"


def test_open_with_package_and_another_app_on_the_port_prints_no_url(monkeypatch, capsys):
    FakeAdb(monkeypatch, _two_apps(8080))
    assert cli.main(["open", "--print-only", "--no-forward", "--package", PACKAGE]) == exitcodes.NOT_REACHABLE
    out, err = capsys.readouterr()
    assert out == ""
    assert err.startswith("error: " + _held_message(8080)), err


def test_open_with_the_token_flag_needs_no_check(monkeypatch, capsys):
    adb = FakeAdb(monkeypatch, _two_apps(8080))
    argv = ["open", "--print-only", "--no-forward", "--package", PACKAGE, "--token", "mine"]
    assert cli.main(argv) == exitcodes.OK
    assert capsys.readouterr().out == "http://localhost:8080/#lustro_token=mine\n"
    assert PM_LIST not in adb.calls


@pytest.mark.parametrize(
    "line, port, uids",
    [
        # 127.0.0.1:8080 in tcp, as a server bound to IPv4 only shows.
        ("   0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10217 0 1 1", 8080, [10217]),
        # 0.0.0.0:8080 in tcp6 as an IPv4-mapped address, in lower case.
        (
            "   0: 0000000000000000ffff000000000000:1f90 00000000000000000000000000000000:0000 0A "
            "00000000:00000000 00:00000000 00000000 10217 0 1 1",
            8080,
            [10217],
        ),
        # A connection on the port, which does not listen.
        ("   0: 0100007F:1F90 0100007F:9C40 01 00000000:00000000 00:00000000 00000000 10213 0 1 1", 8080, []),
        # Another port.
        ("   0: 0100007F:1F91 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10217 0 1 1", 8080, []),
    ],
)
def test_the_uids_that_listen_on_a_port(line, port, uids):
    assert discovery.parse_listening_uids(TCP_HEADER + line + "\n", port) == uids


def test_an_error_response_exits_1(server, capsys):
    server.status = 404
    server.meta = {"error": "not_found", "message": "no route"}
    assert cli.main(["meta", "--port", str(server.server_address[1]), "--token", TOKEN]) == exitcodes.ERROR


# ── the protocol version ───────────────────────────────────────────────────────


def test_version_prints_the_protocol_that_the_cli_implements_and_the_revision(capsys):
    assert cli.main(["--version"]) == exitcodes.OK
    assert capsys.readouterr().out == "lustro {} (protocol {}, revision {})\n".format(
        lustro_cli.__version__, lustro_cli.PROTOCOL_VERSION, lustro_cli.REVISION
    )


def test_the_revision_is_a_whole_number_from_1():
    assert type(lustro_cli.REVISION) is int and lustro_cli.REVISION >= 1


@pytest.mark.parametrize("argv", [["--version", "--json"], ["--json", "--version"]])
def test_version_with_json_prints_an_object_in_either_order(argv, capsys):
    assert cli.main(argv) == exitcodes.OK
    assert json.loads(capsys.readouterr().out) == {
        "version": lustro_cli.__version__,
        "protocol": lustro_cli.PROTOCOL_VERSION,
        "revision": lustro_cli.REVISION,
    }


def test_no_command_is_a_usage_error(capsys):
    with pytest.raises(SystemExit) as excinfo:
        cli.main([])
    assert excinfo.value.code == 2
    assert "the following arguments are required: <command>" in capsys.readouterr().err


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

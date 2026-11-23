"""Tests for the LustroToken log-line parser and endpoint resolution."""

from __future__ import annotations

import adb_parts
import pytest
from adb_parts import listening
from fake_apk import APP_ENTRIES, RUNTIME_ENTRIES, apk_bytes, tail_of

from lustro_cli import discovery, exitcodes
from lustro_cli.discovery import DiscoveryError, Endpoint, parse_ready_line, resolve

APP_UID = 10213
OTHER_UID = 10217


def _plain(endpoint):
    """The endpoint without its owner, which these tests do not compare."""
    return None if endpoint is None else endpoint._replace(owner=None)


def _app(prefs=None, uid=APP_UID, net=None):
    return discovery.AppRead(uid=uid, prefs=prefs, net=net)


def _status(uid=APP_UID):
    """The status of the run-as process that reads the prefs, which has the app's uid."""
    return "Name:\tcat\nUmask:\t0077\nUid:\t{0}\t{0}\t{0}\t{0}\nGid:\t{0}\t{0}\t{0}\t{0}\n".format(uid)


def test_parse_plain_ready_line():
    line = "Lustro ready endpoint=http://127.0.0.1:8080 token=AbC123-_xyz"
    ep = parse_ready_line(line)
    assert ep == Endpoint("127.0.0.1", 8080, "AbC123-_xyz", "http")
    assert ep.base_url == "http://127.0.0.1:8080"


def test_parse_ready_line_with_logcat_prefix():
    line = (
        "06-06 14:22:07.512  1234  1250 I LustroToken: "
        "Lustro ready endpoint=http://127.0.0.1:8080 token=tok_abc"
    )
    ep = parse_ready_line(line)
    assert ep.token == "tok_abc"
    assert ep.port == 8080


def test_parse_ready_line_uses_most_recent_and_fallback_port():
    dump = "\n".join(
        [
            "Lustro ready endpoint=http://127.0.0.1:8080 token=old",
            "some other log line",
            "Lustro ready endpoint=http://127.0.0.1:54321 token=new",
        ]
    )
    ep = parse_ready_line(dump)
    # Last line wins → fallback (OS-assigned) port and rotated token.
    assert ep.port == 54321
    assert ep.token == "new"


def test_parse_ready_line_lan_host():
    line = "Lustro ready endpoint=http://192.168.1.50:8080 token=t"
    ep = parse_ready_line(line)
    assert ep.host == "192.168.1.50"


def test_parse_ready_line_with_lan_urls_after_the_token():
    # With LAN exposure the app adds the device's network URLs after the token.
    line = "Lustro ready endpoint=http://0.0.0.0:8080 token=AbC-_1 lan=http://192.168.1.50:8080,http://10.0.0.2:8080"
    ep = parse_ready_line(line)
    assert (ep.host, ep.port, ep.token) == ("0.0.0.0", 8080, "AbC-_1")


def test_parse_ready_line_none_when_absent():
    assert parse_ready_line("no lustro here") is None


def test_resolve_prefers_explicit_flags(monkeypatch):
    # Even if logcat would return something, explicit flags win and adb isn't called.
    monkeypatch.setattr(
        discovery, "discover_from_logcat", lambda device=None, package=None: Endpoint("9.9.9.9", 1, "x")
    )
    ep = resolve(host="10.0.0.5", port=9000, token="explicit", env={})
    assert ep == Endpoint("10.0.0.5", 9000, "explicit")


def test_resolve_uses_env_token_with_default_host_port(monkeypatch):
    monkeypatch.setattr(discovery, "discover_from_logcat", lambda device=None, package=None: None)
    ep = resolve(env={"LUSTRO_TOKEN": "envtok"})
    assert ep.token == "envtok"
    assert ep.host == "127.0.0.1"
    assert ep.port == 8080


def test_resolve_fills_from_logcat(monkeypatch):
    monkeypatch.setattr(
        discovery,
        "discover_from_logcat",
        lambda device=None, package=None: Endpoint("127.0.0.1", 54321, "logtok"),
    )
    ep = resolve(env={})
    assert ep.port == 54321
    assert ep.token == "logtok"


def test_resolve_env_token_but_logcat_host_port(monkeypatch):
    # Token from env, but host/port still come from the log line.
    monkeypatch.setattr(
        discovery,
        "discover_from_logcat",
        lambda device=None, package=None: Endpoint("127.0.0.1", 7777, "logtok"),
    )
    ep = resolve(env={"LUSTRO_TOKEN": "envtok"})
    assert ep.token == "envtok"
    assert ep.port == 7777


def test_resolve_raises_clear_error_when_no_token(monkeypatch):
    monkeypatch.setattr(discovery, "discover_from_logcat", lambda device=None, package=None: None)
    monkeypatch.setattr(discovery, "_run_status", lambda cmd, timeout=10.0: (0, "device\n"))
    with pytest.raises(DiscoveryError) as excinfo:
        resolve(env={})
    assert excinfo.value.exit_code == exitcodes.NOT_REACHABLE
    msg = str(excinfo.value)
    assert "LUSTRO_TOKEN" in msg
    assert "LustroToken" in msg
    # A device that drops Info logs drops the ready line: the message says what to do.
    assert "log.tag" in msg
    assert "setprop log.tag.LustroToken I" in msg


def test_resolve_with_package_takes_the_endpoint_from_the_prefs(monkeypatch):
    monkeypatch.setattr(
        discovery, "discover_from_logcat", lambda device=None, package=None, pids=None: pytest.fail("read the log")
    )
    monkeypatch.setattr(
        discovery, "read_app", lambda package, device=None: _app(discovery.Prefs("ratok", "127.0.0.1", 8080))
    )
    endpoint = resolve(env={}, package="com.example.app")
    assert _plain(endpoint) == Endpoint("127.0.0.1", 8080, "ratok")
    # The token goes only to a server of the app's uid.
    assert endpoint.owner.uid == APP_UID
    assert endpoint.owner.name == "com.example.app"


def test_discover_from_logcat_parses_subprocess_output(monkeypatch):
    out = "Lustro ready endpoint=http://127.0.0.1:8080 token=fromcmd\n"
    monkeypatch.setattr(discovery, "_run", lambda cmd, timeout=10.0: out)
    ep = discovery.discover_from_logcat()
    assert ep.token == "fromcmd"


def test_discover_from_logcat_handles_missing_adb(monkeypatch):
    monkeypatch.setattr(discovery, "_run", lambda cmd, timeout=10.0: None)
    assert discovery.discover_from_logcat() is None


# Verbatim shape of shared_prefs/lustro_debug.xml as written on a device.
_PREFS_XML = (
    "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
    "<map>\n"
    '    <string name="lustro_token">prefstok</string>\n'
    "</map>"
)


def test_parse_prefs_reads_the_token():
    assert discovery.parse_prefs(_PREFS_XML).token == "prefstok"


def test_parse_prefs_ignores_an_unrelated_token_key():
    # Only the runtime's own key counts; a plain "token" entry is somebody
    # else's preference and must not be handed out as the auth token.
    assert discovery.parse_prefs('<map><string name="token">notours</string></map>') is None


def test_parse_prefs_none_when_no_match():
    assert discovery.parse_prefs("<map/>") is None
    assert discovery.parse_prefs("run-as: package not debuggable: com.example.app") is None


# ── adb forward --list ─────────────────────────────────────────────────────────

# Forwards of two devices, as adb lists them, with one that isn't TCP.
_FORWARD_LIST = (
    "0A1B2C3D4E5F tcp:18080 tcp:8080\n"
    "emulator-5554 tcp:18181 tcp:8080\n"
    "emulator-5554 localabstract:chrome tcp:8080\n"
    "emulator-5554 tcp:9222 localabstract:chrome_devtools_remote\n"
    "emulator-5554 tcp:18282 tcp:8080\n"
    "emulator-5554 tcp:19000 tcp:9000\r\n"
)


def test_parse_forwards_keeps_the_tcp_forwards_to_the_port_in_order():
    assert discovery.parse_forwards(_FORWARD_LIST, 8080) == [
        ("0A1B2C3D4E5F", 18080),
        ("emulator-5554", 18181),
        ("emulator-5554", 18282),
    ]
    assert discovery.parse_forwards(_FORWARD_LIST, 9000) == [("emulator-5554", 19000)]
    assert discovery.parse_forwards("", 8080) == []


def _fake_adb_run(monkeypatch, outputs, state=(0, "device\n")):
    """Answer each adb command from ``outputs``, keyed by its arguments; record them.
    ``adb get-state`` answers ``state``, its exit status and output: a device. A
    command with an exit status that is not in ``outputs`` exits 1 with no output,
    as ``pm path`` does for a package that is not installed. An output that is a
    tuple is the exit status and the output. ``tail -c`` through ``adb exec-out``
    reads the bytes in ``outputs`` at the path of the file."""
    calls = []

    def run(cmd, timeout=10.0):
        parts = adb_parts.split(cmd)
        if parts is not None:
            return adb_parts.join(run(part) for part in parts)
        calls.append(cmd)
        out = outputs.get(" ".join(cmd))
        return out[1] if isinstance(out, tuple) else out

    def run_status(cmd, timeout=10.0):
        calls.append(cmd)
        if cmd[-1] == "get-state":
            return state
        out = outputs.get(" ".join(cmd))
        if isinstance(out, tuple):
            return out
        return (1, "") if out is None else (0, out)

    def run_bytes(cmd, timeout=10.0):
        calls.append(cmd)
        return tail_of(outputs, cmd)

    monkeypatch.setattr(discovery, "_run", run)
    monkeypatch.setattr(discovery, "_run_bytes", run_bytes)
    monkeypatch.setattr(discovery, "_run_status", run_status)
    return calls


def test_forwarded_ports_lists_the_forwards_of_the_selected_device_in_order(monkeypatch):
    calls = _fake_adb_run(
        monkeypatch, {"adb forward --list": _FORWARD_LIST, "adb get-serialno": "emulator-5554\n"}
    )
    assert discovery.forwarded_ports(8080) == [18181, 18282]
    assert calls == [["adb", "forward", "--list"], ["adb", "get-serialno"]]


def test_forwarded_ports_asks_adb_for_the_serial_of_the_device_flag(monkeypatch):
    # adb -s also takes qualifiers such as model:X, and the list shows serials.
    calls = _fake_adb_run(
        monkeypatch,
        {"adb forward --list": _FORWARD_LIST, "adb -s model:sdk_gphone64_arm64 get-serialno": "0A1B2C3D4E5F\n"},
    )
    assert discovery.forwarded_ports(8080, "model:sdk_gphone64_arm64") == [18080]
    assert calls[-1] == ["adb", "-s", "model:sdk_gphone64_arm64", "get-serialno"]


def test_forwarded_ports_ignores_the_forwards_of_other_devices(monkeypatch):
    _fake_adb_run(monkeypatch, {"adb forward --list": _FORWARD_LIST, "adb get-serialno": "emulator-5556\n"})
    assert discovery.forwarded_ports(8080) == []


def test_forwarded_ports_without_a_forward_to_the_port_asks_for_no_serial(monkeypatch):
    calls = _fake_adb_run(monkeypatch, {"adb forward --list": _FORWARD_LIST})
    assert discovery.forwarded_ports(7000) == []
    assert calls == [["adb", "forward", "--list"]]


def test_forwarded_ports_is_empty_when_adb_can_not_select_a_device(monkeypatch):
    # adb get-serialno fails with more than one device and no ANDROID_SERIAL.
    _fake_adb_run(monkeypatch, {"adb forward --list": _FORWARD_LIST})
    assert discovery.forwarded_ports(8080) == []


def test_forwarded_ports_is_empty_without_adb(monkeypatch):
    _fake_adb_run(monkeypatch, {})
    assert discovery.forwarded_ports(8080) == []


# ── the app's prefs, when the ready line is missing ───────────────────────────

_PREFS_WITH_ENDPOINT = (
    "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
    "<map>\n"
    '    <string name="lustro_token">prefstok</string>\n'
    '    <string name="lustro_host">127.0.0.1</string>\n'
    '    <int name="lustro_port" value="8081" />\n'
    "</map>"
)


def test_parse_prefs_reads_the_endpoint_the_app_stored():
    assert discovery.parse_prefs(_PREFS_WITH_ENDPOINT) == discovery.Prefs("prefstok", "127.0.0.1", 8081)


def test_read_app_reads_the_prefs_the_uid_and_the_sockets_in_one_adb_call(monkeypatch):
    calls = []

    def run(cmd, timeout=10.0):
        calls.append(cmd)
        return adb_parts.join([_PREFS_WITH_ENDPOINT + "\n" + _status(), listening((8081, APP_UID))])

    monkeypatch.setattr(discovery, "_run", run)
    read = discovery.read_app("com.example.app", "emulator-5554")
    assert read == _app(discovery.Prefs("prefstok", "127.0.0.1", 8081), net=listening((8081, APP_UID)))
    assert len(calls) == 1
    assert calls[0][:4] == ["adb", "-s", "emulator-5554", "shell"]
    # The cat that reads the prefs runs with the app's uid, and reads its own status too.
    assert [" ".join(part[4:]) for part in adb_parts.split(calls[0])] == [
        "run-as com.example.app cat shared_prefs/lustro_debug.xml /proc/self/status 2>/dev/null",
        "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null",
    ]


def test_read_app_of_an_app_without_prefs_has_its_uid_and_no_token(monkeypatch):
    monkeypatch.setattr(discovery, "_run", lambda cmd, timeout=10.0: adb_parts.join([_status(), ""]))
    assert discovery.read_app("com.example.app") == _app()


def test_read_app_of_a_build_that_is_not_debuggable_has_no_uid_and_no_prefs(monkeypatch):
    monkeypatch.setattr(discovery, "_run", lambda cmd, timeout=10.0: adb_parts.join(["", listening()]))
    assert discovery.read_app("com.example.app") == _app(uid=None, net=listening())


def test_app_process_uids_has_the_uid_of_each_process(monkeypatch):
    calls = _fake_adb_run(monkeypatch, {_PIDS: "4242 10213\n5151\n"})
    assert discovery.app_process_uids("com.example.app") == {"4242": APP_UID, "5151": None}
    assert calls == [["adb", "shell", _PIDS[len("adb shell "):]]]
    _fake_adb_run(monkeypatch, {})
    assert discovery.app_process_uids("com.example.app") is None


@pytest.mark.parametrize("out", [None, "error: closed\n"])
def test_read_app_is_none_when_adb_fails(monkeypatch, out):
    monkeypatch.setattr(discovery, "_run", lambda cmd, timeout=10.0: out)
    assert discovery.read_app("com.example.app") is None


@pytest.mark.parametrize(
    "dumpsys",
    [
        "  topResumedActivity=ActivityRecord{164975770 u0 io.example.app/io.example.app.MainActivity t132 d0}\n",
        "    mResumedActivity: ActivityRecord{3c1d2e u0 io.example.app/.MainActivity t7}\n",
        "  ResumedActivity: ActivityRecord{136447847 u0 io.example.app/io.example.app.MainActivity t137 d0}\n",
    ],
)
def test_foreground_package_reads_the_resumed_activity(monkeypatch, dumpsys):
    calls = []

    def run(cmd, timeout=10.0):
        calls.append(cmd)
        return dumpsys

    monkeypatch.setattr(discovery, "_run", run)
    assert discovery.foreground_package("emulator-5554") == "io.example.app"
    assert calls[0][:3] == ["adb", "-s", "emulator-5554"]


def test_foreground_package_is_none_when_adb_cannot_tell(monkeypatch):
    monkeypatch.setattr(discovery, "_run", lambda cmd, timeout=10.0: None)
    assert discovery.foreground_package() is None


def test_resolve_reads_the_prefs_of_the_foreground_app_when_the_log_has_no_line(monkeypatch):
    monkeypatch.setattr(discovery, "discover_from_logcat", lambda device=None, package=None, pids=None: None)
    monkeypatch.setattr(discovery, "foreground_package", lambda device=None: "com.example.app")
    seen = []

    def read_app(package, device=None):
        seen.append(package)
        return _app(discovery.Prefs("prefstok", "127.0.0.1", 8081))

    monkeypatch.setattr(discovery, "read_app", read_app)
    endpoint = resolve(env={})
    assert _plain(endpoint) == Endpoint("127.0.0.1", 8081, "prefstok")
    assert seen == ["com.example.app"]
    # The owner of the token is the app in the foreground.
    assert (endpoint.owner.uid, endpoint.owner.name) == (APP_UID, "com.example.app")


def test_resolve_with_package_reads_its_prefs_and_not_those_of_the_foreground_app(monkeypatch):
    monkeypatch.setattr(discovery, "discover_from_logcat", lambda device=None, package=None, pids=None: None)
    monkeypatch.setattr(discovery, "foreground_package", lambda device=None: pytest.fail("asked for the foreground app"))
    seen = []
    monkeypatch.setattr(
        discovery, "read_app", lambda package, device=None: seen.append(package) or _app(discovery.Prefs("t", "h", 1))
    )
    resolve(env={}, package="com.example.app")
    assert seen == ["com.example.app"]


_FG_RUN_AS = "adb shell run-as com.example.app cat shared_prefs/lustro_debug.xml /proc/self/status 2>/dev/null"
_PROC_NET = "adb shell cat /proc/net/tcp /proc/net/tcp6 2>/dev/null"


def test_token_from_prefs(monkeypatch):
    monkeypatch.setattr(discovery, "foreground_package", lambda device=None: "com.example.app")
    _fake_adb_run(monkeypatch, {_FG_RUN_AS: _PREFS_WITH_ENDPOINT})
    assert discovery.token_from_prefs() == "prefstok"
    monkeypatch.setattr(discovery, "foreground_package", lambda device=None: None)
    assert discovery.token_from_prefs() is None


def _found_by_another_app(host="127.0.0.1", port=8081):
    """An endpoint that a line of another app's process gave."""
    return Endpoint(host, port, "x", owner=discovery.Owner(uid=OTHER_UID, pid="6666"))


@pytest.mark.parametrize(
    "endpoint, listener, token",
    [
        # The app in the foreground listens on the line's port: its server gets its token.
        (_found_by_another_app(), APP_UID, "prefstok"),
        # Another app listens there: it does not.
        (_found_by_another_app(), OTHER_UID, None),
        # A host on the network gets it only when the prefs name that host and port.
        (_found_by_another_app("192.168.1.50"), APP_UID, None),
        # On loopback, the app's uid on the port is enough: its server can bind another
        # port than the prefs name, as with bindFallback.
        (_found_by_another_app(port=9999), APP_UID, "prefstok"),
        # A token that the user gave, or a port, needs no check.
        (Endpoint("127.0.0.1", 9999, "x"), OTHER_UID, "prefstok"),
    ],
)
def test_token_from_prefs_for_an_endpoint_goes_only_to_the_server_of_the_app(monkeypatch, endpoint, listener, token):
    monkeypatch.setattr(discovery, "foreground_package", lambda device=None: "com.example.app")
    outputs = {_FG_RUN_AS: _PREFS_WITH_ENDPOINT + "\n" + _status(), _PROC_NET: listening((endpoint.port, listener))}
    _fake_adb_run(monkeypatch, outputs)
    assert discovery.token_from_prefs(endpoint=endpoint) == token


def test_token_from_prefs_for_a_host_on_the_network_that_the_prefs_name(monkeypatch):
    prefs = _PREFS_WITH_ENDPOINT.replace("127.0.0.1", "192.168.1.50") + "\n" + _status()
    _fake_adb_run(monkeypatch, {_FG_RUN_AS: prefs, _PROC_NET: listening((8081, APP_UID))})
    endpoint = _found_by_another_app("192.168.1.50")
    assert discovery.token_from_prefs(package="com.example.app", endpoint=endpoint) == "prefstok"


def test_resolve_takes_the_port_from_the_prefs_for_a_token_from_the_environment(monkeypatch):
    monkeypatch.setattr(discovery, "discover_from_logcat", lambda device=None, package=None, pids=None: None)
    monkeypatch.setattr(discovery, "foreground_package", lambda device=None: "com.example.app")
    monkeypatch.setattr(discovery, "read_app", lambda package, device=None: _app(discovery.Prefs("prefstok", "127.0.0.1", 8081)))
    # A token that the user gives needs no check, so the endpoint has no owner.
    assert resolve(env={"LUSTRO_TOKEN": "envtok"}) == Endpoint("127.0.0.1", 8081, "envtok")
    # Flags that give all three need no prefs.
    monkeypatch.setattr(discovery, "read_app", lambda package, device=None: pytest.fail("read the prefs"))
    assert resolve(host="127.0.0.1", port=9000, token="t", env={}) == Endpoint("127.0.0.1", 9000, "t")


# ── the cause, when there is no token ─────────────────────────────────────────

_APK = "/data/app/~~Xy==/com.example.app-Ab==/base.apk"
_PM_PATH = "package:{}\r\n".format(_APK)
_TAIL = "adb exec-out tail -c 65557 '{}'".format(_APK)
_WITH_RUNTIME = apk_bytes(APP_ENTRIES + RUNTIME_ENTRIES)
_NO_RUNTIME = apk_bytes(APP_ENTRIES)
_PIDOF = "adb shell pidof com.example.app || true"
_DUMPSYS = "adb shell dumpsys activity activities | grep ResumedActivity"
_RESUMED = "  topResumedActivity=ActivityRecord{{1a2b u0 {}/.MainActivity t12}}\n"


class _Tail:
    """``read_tail`` for :func:`discovery.zip_entry_names`: the end of ``data``, with the
    size of each read."""

    def __init__(self, data):
        self.data = data
        self.sizes = []

    def __call__(self, size):
        self.sizes.append(size)
        return self.data[-size:]


@pytest.mark.parametrize("comment", [b"", b"a comment", b"PK\x05\x06 in a comment"])
def test_the_entry_names_come_from_the_central_directory_in_one_read(comment):
    read = _Tail(apk_bytes(APP_ENTRIES + RUNTIME_ENTRIES, comment=comment))
    assert discovery.zip_entry_names(read) == APP_ENTRIES + RUNTIME_ENTRIES
    assert read.sizes == [65557]


def test_a_central_directory_larger_than_64_kb_takes_a_second_read():
    names = APP_ENTRIES + ["res/drawable/icon_{:05d}_with_a_long_name.png".format(n) for n in range(2000)]
    data = apk_bytes(names + RUNTIME_ENTRIES)
    read = _Tail(data)
    assert discovery.zip_entry_names(read) == names + RUNTIME_ENTRIES
    eocd = len(data) - 22
    size = int.from_bytes(data[eocd + 12 : eocd + 16], "little")
    assert size > 65557
    assert read.sizes == [65557, size + 22]


def test_a_file_smaller_than_the_first_read_is_read_whole():
    data = apk_bytes(RUNTIME_ENTRIES)
    assert len(data) < 65557
    assert discovery.zip_entry_names(_Tail(data)) == RUNTIME_ENTRIES


@pytest.mark.parametrize(
    "data",
    [
        b"",
        b"tail: base.apk: Permission denied\n",
        apk_bytes(APP_ENTRIES)[:-1],  # the EOCD is not at the end
        b"PK\x05\x06" + b"x" * 13,  # shorter than an EOCD
        b"xPK\x05\x06" + b"\0" * 10,
    ],
)
def test_what_is_not_a_zip_gives_no_names(data):
    assert discovery.zip_entry_names(_Tail(data)) is None


def test_a_central_directory_that_is_not_where_the_eocd_says_gives_no_names():
    data = bytearray(apk_bytes(APP_ENTRIES))
    eocd = len(data) - 22
    size = int.from_bytes(data[eocd + 12 : eocd + 16], "little")
    data[eocd + 12 : eocd + 16] = (size - 1).to_bytes(4, "little")
    assert discovery.zip_entry_names(_Tail(bytes(data))) is None


@pytest.mark.parametrize("size", [0xFFFFFFFF, 64 * 1024 * 1024])
def test_a_central_directory_of_zip64_or_of_a_damaged_eocd_is_not_read(size):
    data = bytearray(apk_bytes(APP_ENTRIES))
    eocd = len(data) - 22
    data[eocd + 12 : eocd + 16] = size.to_bytes(4, "little")
    read = _Tail(bytes(data))
    assert discovery.zip_entry_names(read) is None
    assert read.sizes == [65557]


def test_a_second_read_that_ends_early_gives_no_names():
    names = ["res/raw/file_{:05d}_with_a_long_name.bin".format(n) for n in range(2000)]
    data = apk_bytes(names)
    assert discovery.zip_entry_names(lambda size: data[-size:][1:] if size > 65557 else data[-size:]) is None


def test_the_runtime_check_reads_the_end_of_the_apk_on_the_selected_device(monkeypatch):
    calls = _fake_adb_run(monkeypatch, {_APK: _WITH_RUNTIME})
    assert discovery.apk_has_runtime(_APK, "emulator-5554") is True
    assert calls == [["adb", "-s", "emulator-5554", "exec-out", "tail -c 65557 '{}'".format(_APK)]]
    _fake_adb_run(monkeypatch, {_APK: _NO_RUNTIME})
    assert discovery.apk_has_runtime(_APK) is False
    _fake_adb_run(monkeypatch, {})
    assert discovery.apk_has_runtime(_APK) is None


def _no_token(monkeypatch, outputs, state=(0, "device\n"), **kwargs):
    calls = _fake_adb_run(monkeypatch, outputs, state)
    with pytest.raises(DiscoveryError) as excinfo:
        resolve(env={}, **kwargs)
    return excinfo.value, calls


def test_without_adb_the_cause_is_no_device(monkeypatch):
    error, _ = _no_token(monkeypatch, {}, state=(127, ""))
    assert error.exit_code == exitcodes.NO_DEVICE
    assert str(error).startswith("adb is not installed, or not on the PATH")
    assert "--token" in str(error)


@pytest.mark.parametrize(
    "said",
    ["error: no devices/emulators found", "error: device 'emulator-5554' not found", "error: device unauthorized."],
)
def test_a_device_that_adb_can_not_use_is_no_device(monkeypatch, said):
    error, calls = _no_token(monkeypatch, {}, state=(1, said + "\n"), package="com.example.app")
    assert error.exit_code == exitcodes.NO_DEVICE
    assert str(error).startswith("no device that adb can use ({}):".format(said))
    assert not any("pm" in cmd for cmd in calls)


@pytest.mark.parametrize(
    "said",
    [
        "* daemon not running; starting now at tcp:5037\n* daemon started successfully\n",
        "adb server version (41) doesn't match this client (39); killing...\n* daemon started successfully\n",
    ],
)
def test_the_lines_that_adb_prints_about_its_server_do_not_hide_the_device(monkeypatch, said):
    error, _ = _no_token(monkeypatch, {}, state=(0, said + "device\n"), package="com.example.app")
    assert error.exit_code == exitcodes.NOT_INSTALLED


def test_more_than_one_device_with_none_selected_is_a_usage_error(monkeypatch):
    error, _ = _no_token(monkeypatch, {}, state=(1, "error: more than one device/emulator\n"))
    assert error.exit_code == exitcodes.USAGE
    assert "--device SERIAL" in str(error)
    assert "ANDROID_SERIAL" in str(error)


def test_the_device_flag_goes_to_get_state(monkeypatch):
    _, calls = _no_token(monkeypatch, {}, state=(1, "error: device 'x' not found\n"), device="x")
    assert calls[-1] == ["adb", "-s", "x", "get-state"]


def test_a_package_that_is_not_installed(monkeypatch):
    error, _ = _no_token(monkeypatch, {}, package="com.example.app")
    assert error.exit_code == exitcodes.NOT_INSTALLED
    assert str(error).startswith("com.example.app is not installed on the device")


@pytest.mark.parametrize(
    "answer",
    [
        (1, "Error: Could not access the Package Manager.  Is the system running?\n"),  # the device boots
        (-1, ""),  # adb did not finish in time
        (255, ""),  # adb failed
    ],
)
def test_a_package_query_that_fails_is_not_a_package_that_is_not_installed(monkeypatch, answer):
    # A script installs a build for exit 4, so a query that says nothing must not give it.
    error, calls = _no_token(monkeypatch, {"adb shell pm path com.example.app": answer}, package="com.example.app")
    assert error.exit_code == exitcodes.NOT_REACHABLE
    assert "not installed" not in str(error)
    assert not any("exec-out" in cmd for cmd in calls)


def test_an_app_without_a_base_apk_gets_no_runtime_check(monkeypatch):
    outputs = {
        "adb shell pm path com.example.app": "package:/system/app/Example/Example.apk\n",
        "adb shell pidof com.example.app || true": "",
    }
    error, calls = _no_token(monkeypatch, outputs, package="com.example.app")
    assert str(error).startswith("com.example.app is not running")
    assert not any("exec-out" in cmd for cmd in calls)


def test_a_known_app_without_the_runtime_is_a_build_without_lustro(monkeypatch):
    error, calls = _no_token(
        monkeypatch, {"adb shell pm path com.example.app": _PM_PATH, _APK: _NO_RUNTIME}, package="com.example.app"
    )
    message = str(error)
    assert error.exit_code == exitcodes.NO_RUNTIME
    assert message.startswith("the installed build of com.example.app does not contain the Lustro runtime")
    # Both causes: the no-op artifacts, and a build from before the app added Lustro.
    assert "-noop artifacts" in message
    assert "from before the app added Lustro" in message
    assert "Install a debug build" in message
    assert "logcat" not in message
    assert " ".join(calls[-1]) == _TAIL


def test_a_known_app_that_is_not_running(monkeypatch):
    error, _ = _no_token(
        monkeypatch,
        {"adb shell pm path com.example.app": _PM_PATH, _APK: _WITH_RUNTIME, _PIDOF: ""},
        package="com.example.app",
    )
    assert error.exit_code == exitcodes.NOT_REACHABLE
    assert str(error).startswith("com.example.app is not running: start it")


def test_a_known_app_in_the_background(monkeypatch):
    error, _ = _no_token(
        monkeypatch,
        {
            "adb shell pm path com.example.app": _PM_PATH,
            _APK: _WITH_RUNTIME,
            _PIDOF: "4242\n",
            _DUMPSYS: _RESUMED.format("com.android.launcher3"),
        },
        package="com.example.app",
    )
    assert error.exit_code == exitcodes.NOT_REACHABLE
    assert str(error).startswith("com.example.app runs, but com.android.launcher3 is in the foreground")


def test_a_known_app_in_the_foreground_gets_the_message_for_a_missing_ready_line(monkeypatch):
    error, _ = _no_token(
        monkeypatch,
        {
            "adb shell pm path com.example.app": _PM_PATH,
            _APK: _WITH_RUNTIME,
            _PIDOF: "4242\n",
            _DUMPSYS: _RESUMED.format("com.example.app"),
        },
        package="com.example.app",
    )
    assert error.exit_code == exitcodes.NOT_REACHABLE
    assert "LustroToken" in str(error)
    assert "no-op" not in str(error)


def test_the_app_in_the_foreground_is_checked_and_named(monkeypatch):
    error, _ = _no_token(
        monkeypatch,
        {_DUMPSYS: _RESUMED.format("com.example.app"), "adb shell pm path com.example.app": _PM_PATH, _APK: _NO_RUNTIME},
    )
    message = str(error)
    assert message.startswith("the app in the foreground, com.example.app, does not contain the Lustro runtime")
    assert "from before the app added Lustro" in message
    assert "--package <id>" in message
    # That app can be the launcher, or another app than the user's.
    assert error.exit_code == exitcodes.NOT_REACHABLE


@pytest.mark.parametrize(
    "outputs",
    [
        {"adb shell pm path com.example.app": _PM_PATH},  # tail failed
        {"adb shell pm path com.example.app": _PM_PATH, _APK: b"tail: base.apk: Permission denied\n"},
    ],
)
def test_when_adb_can_not_tell_the_message_is_the_one_for_a_stopped_server(monkeypatch, outputs):
    error, _ = _no_token(monkeypatch, outputs, package="com.example.app")
    assert error.exit_code == exitcodes.NOT_REACHABLE
    assert "no-op" not in str(error)


def test_without_a_known_app_there_is_no_check(monkeypatch):
    error, calls = _no_token(monkeypatch, {})
    assert error.exit_code == exitcodes.NOT_REACHABLE
    assert "LustroToken" in str(error)
    assert not any("pm" in cmd for cmd in calls)


def test_without_the_app_checks_resolve_makes_no_adb_call_for_the_cause(monkeypatch):
    calls = _fake_adb_run(monkeypatch, {"adb shell pm path com.example.app": _PM_PATH, _APK: _NO_RUNTIME})
    with pytest.raises(DiscoveryError) as excinfo:
        resolve(env={}, package="com.example.app", check_app=False)
    assert excinfo.value.exit_code == exitcodes.NOT_REACHABLE
    assert not any(cmd[-1] == "get-state" or "pm" in cmd for cmd in calls)


def test_diagnose_without_adb_required_ignores_a_computer_without_adb(monkeypatch):
    _fake_adb_run(monkeypatch, {}, state=(127, ""))
    assert discovery.diagnose(package="com.example.app", adb_required=False) is None
    assert discovery.diagnose(package="com.example.app").exit_code == exitcodes.NO_DEVICE


def test_a_found_token_needs_no_check(monkeypatch):
    calls = _fake_adb_run(monkeypatch, {_PIDS: "4242 10213\n", _LOGCAT_PIDS: _line(4242, "t")})
    assert resolve(env={}, package="com.example.app").token == "t"
    assert not any("pm" in cmd or "exec-out" in cmd for cmd in calls)


# ── --package: only the ready line of the app's current process ───────────────

# The dump has the PID of each line.
_LOGCAT = "adb logcat -d -v threadtime -s LustroToken"
_LOGCAT_PIDS = _LOGCAT
_PIDS = "adb shell for p in $(pidof com.example.app); do echo $p $(stat -c %u /proc/$p 2>/dev/null); done"


def _stat(pid):
    return "adb shell stat -c %u /proc/{} 2>/dev/null".format(pid)


def _ready(token, port=8080):
    return "I LustroToken: Lustro ready endpoint=http://127.0.0.1:{} token={}\n".format(port, token)


def _line(pid, token, port=8080):
    """A ready line in the ``threadtime`` format, which starts with the date, the time,
    the PID, and the thread ID."""
    return "11-21 10:59:08.064  {}  {} {}".format(pid, pid + 31, _ready(token, port))


def test_without_package_the_last_ready_line_counts_whatever_app_logged_it(monkeypatch):
    outputs = {
        _LOGCAT: _line(3000, "first") + _line(3100, "other", 8081),
        _stat(3100): "{}\n".format(OTHER_UID),
        _PROC_NET: listening((8081, OTHER_UID)),
    }
    calls = _fake_adb_run(monkeypatch, outputs)
    endpoint = resolve(env={})
    assert _plain(endpoint) == Endpoint("127.0.0.1", 8081, "other")
    # Its owner is the process that logged it: one adb call reads its uid and the sockets.
    assert endpoint.owner == discovery.Owner(uid=OTHER_UID, pid="3100", net=listening((8081, OTHER_UID)))
    assert not any("pidof" in " ".join(cmd) for cmd in calls)
    assert not any("run-as" in " ".join(cmd) for cmd in calls)


def test_the_ready_line_of_another_app_is_not_used_for_a_package_that_is_not_installed(monkeypatch):
    # Another app logged the last line; the named app is not installed, so it has no process.
    calls = _fake_adb_run(monkeypatch, {_LOGCAT: _ready("other"), _LOGCAT_PIDS: _line(3000, "other"), _PIDOF: ""})
    with pytest.raises(DiscoveryError) as excinfo:
        resolve(env={}, package="com.example.app")
    assert excinfo.value.exit_code == exitcodes.NOT_INSTALLED
    assert not any(cmd[1:3] == ["logcat", "-d"] for cmd in calls)


def test_the_ready_line_of_the_apps_current_process_is_used(monkeypatch):
    # The buffer ends with another app's line; the PID keeps the app's own.
    calls = _fake_adb_run(
        monkeypatch,
        {
            _PIDS: "4242 10213\n",
            _LOGCAT_PIDS: "--------- beginning of main\n" + _line(4242, "current", 8082) + _line(3000, "other", 8081),
        },
    )
    endpoint = resolve(env={}, package="com.example.app")
    assert _plain(endpoint) == Endpoint("127.0.0.1", 8082, "current")
    # One adb call gives the PIDs, and the uid of the process that logged the line.
    assert ["adb", "shell", "pidof com.example.app || true"] not in calls
    assert (endpoint.owner.uid, endpoint.owner.pid) == (APP_UID, "4242")


def test_the_ready_line_of_an_earlier_process_of_the_app_is_not_used(monkeypatch):
    # The earlier process logged a line with its token; the current one logged none, as on a
    # device that drops Info logs. The prefs have the current token.
    _fake_adb_run(
        monkeypatch,
        {
            _PIDS: "5151 10213\n",
            _LOGCAT_PIDS: _line(4242, "earlier"),
            _RUN_AS: _PREFS_WITH_ENDPOINT,
        },
    )
    assert _plain(resolve(env={}, package="com.example.app")) == Endpoint("127.0.0.1", 8081, "prefstok")


def test_a_pid_that_is_the_thread_id_of_another_line_does_not_match(monkeypatch):
    # 4273 is the thread ID of the line of process 4242.
    _fake_adb_run(monkeypatch, {_PIDOF: "4273\n", _LOGCAT_PIDS: _line(4242, "other")})
    assert discovery.discover_from_logcat(package="com.example.app") is None


def test_the_package_filter_reads_the_selected_device(monkeypatch):
    calls = _fake_adb_run(
        monkeypatch,
        {
            "adb -s emulator-5554 shell pidof com.example.app || true": "4242\n",
            "adb -s emulator-5554 logcat -d -v threadtime -s LustroToken": _line(4242, "t"),
        },
    )
    assert resolve(env={}, device="emulator-5554", package="com.example.app").token == "t"
    assert calls[-1] == ["adb", "-s", "emulator-5554", "logcat", "-d", "-v", "threadtime", "-s", "LustroToken"]


@pytest.mark.parametrize("pids", ["4242 5151\n", "5151 4242\n"])
def test_with_two_processes_of_the_app_the_last_ready_line_in_the_log_counts(monkeypatch, pids):
    # While an old process exits, pidof can print both, in any order. The newer process
    # logged after the old one, and its PID can be lower after the PIDs wrap.
    calls = _fake_adb_run(
        monkeypatch, {_PIDOF: pids, _LOGCAT_PIDS: _line(5151, "old", 8081) + _line(4242, "new", 8082)}
    )
    assert _plain(discovery.discover_from_logcat(package="com.example.app")) == Endpoint("127.0.0.1", 8082, "new")
    assert sum(1 for cmd in calls if cmd[1] == "logcat") == 1


def test_the_package_filter_reads_no_log_when_adb_can_not_tell_the_process(monkeypatch):
    calls = _fake_adb_run(monkeypatch, {_LOGCAT: _ready("other"), _LOGCAT_PIDS: _line(3000, "other")})
    assert discovery.discover_from_logcat(package="com.example.app") is None
    assert not any(cmd[1:3] == ["logcat", "-d"] for cmd in calls)


# ── --package: the app's prefs before the log ─────────────────────────────────

_RUN_AS = _FG_RUN_AS
# The commands of the one adb call that reads the app's prefs.
_APP_READ = [
    ["adb", "shell", "run-as com.example.app cat shared_prefs/lustro_debug.xml /proc/self/status 2>/dev/null"],
    ["adb", "shell", "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null"],
]


def _reads_the_log(calls):
    return any(cmd[1:2] == ["logcat"] or " ".join(cmd) == _PIDOF for cmd in calls)


def test_with_package_the_prefs_come_first_and_the_log_is_not_read(monkeypatch):
    # The log has the app's line with another port and token: the prefs win, and the CLI
    # runs no logcat, whose dump takes seconds on a device with full log buffers.
    calls = _fake_adb_run(
        monkeypatch, {_RUN_AS: _PREFS_WITH_ENDPOINT, _PIDS: "4242 10213\n", _LOGCAT_PIDS: _line(4242, "logtok", 8082)}
    )
    assert _plain(resolve(env={}, package="com.example.app")) == Endpoint("127.0.0.1", 8081, "prefstok")
    assert calls == _APP_READ


@pytest.mark.parametrize(
    "flags, env",
    [({"token": "flagtok"}, {}), ({}, {"LUSTRO_TOKEN": "flagtok"})],
    ids=["--token", "LUSTRO_TOKEN"],
)
def test_with_package_and_a_given_token_the_prefs_give_the_host_and_the_port(monkeypatch, flags, env):
    calls = _fake_adb_run(
        monkeypatch, {_RUN_AS: _PREFS_WITH_ENDPOINT, _PIDS: "4242 10213\n", _LOGCAT_PIDS: _line(4242, "logtok", 8082)}
    )
    # A token that the user gives needs no check, so the endpoint has no owner.
    assert resolve(env=env, package="com.example.app", **flags) == Endpoint("127.0.0.1", 8081, "flagtok")
    assert not _reads_the_log(calls)


def test_with_package_the_prefs_read_goes_to_the_selected_device(monkeypatch):
    calls = _fake_adb_run(
        monkeypatch,
        {"adb -s emulator-5554 " + _FG_RUN_AS[len("adb "):]: _PREFS_WITH_ENDPOINT},
    )
    assert resolve(env={}, device="emulator-5554", package="com.example.app").token == "prefstok"
    assert calls == [cmd[:1] + ["-s", "emulator-5554"] + cmd[1:] for cmd in _APP_READ]


@pytest.mark.parametrize(
    "prefs",
    [None, "<map/>", '<map><string name="lustro_host">127.0.0.1</string><int name="lustro_port" value="8081" /></map>'],
    ids=["run-as fails", "no prefs", "no token"],
)
def test_with_package_the_log_of_the_current_process_gives_what_the_prefs_do_not(monkeypatch, prefs):
    # run-as fails for a build that is not debuggable, and an app that has not written
    # its token yet has none. The log still keeps only the lines of the current process.
    calls = _fake_adb_run(
        monkeypatch,
        {_RUN_AS: prefs, _PIDS: "4242 10213\n", _LOGCAT_PIDS: _line(4242, "current", 8082) + _line(3000, "other", 8083)},
    )
    endpoint = resolve(env={}, package="com.example.app")
    assert _plain(endpoint) == Endpoint("127.0.0.1", 8082, "current")
    assert [cmd for cmd in calls if "run-as" in " ".join(cmd)] == _APP_READ[:1]
    # The owner is the process that logged the line.
    assert (endpoint.owner.uid, endpoint.owner.pid, endpoint.owner.prefs) == (APP_UID, "4242", None)


def test_with_package_the_log_gives_the_endpoint_that_the_prefs_of_an_older_app_do_not_have(monkeypatch):
    # An app that predates storing the host and the port has only the token in its prefs.
    _fake_adb_run(monkeypatch, {_RUN_AS: _PREFS_XML, _PIDS: "4242 10213\n", _LOGCAT_PIDS: _line(4242, "prefstok", 8082)})
    assert _plain(resolve(env={}, package="com.example.app")) == Endpoint("127.0.0.1", 8082, "prefstok")


def test_with_package_a_token_only_in_the_prefs_gets_the_default_endpoint(monkeypatch):
    _fake_adb_run(monkeypatch, {_RUN_AS: _PREFS_XML, _PIDS: "4242 10213\n", _LOGCAT_PIDS: ""})
    assert _plain(resolve(env={}, package="com.example.app")) == Endpoint("127.0.0.1", 8080, "prefstok")


def test_with_package_the_prefs_of_an_app_that_does_not_run_are_used(monkeypatch):
    # They keep the endpoint of the app's last process: the connection then fails, and
    # diagnose() gives the cause. The log is not read, and pidof does not run.
    calls = _fake_adb_run(monkeypatch, {_RUN_AS: _PREFS_WITH_ENDPOINT, _PIDOF: "", _LOGCAT: _line(3000, "other")})
    assert _plain(resolve(env={}, package="com.example.app")) == Endpoint("127.0.0.1", 8081, "prefstok")
    assert not _reads_the_log(calls)


def test_without_package_the_log_comes_before_the_prefs(monkeypatch):
    outputs = {
        _LOGCAT: _line(4242, "logtok", 8082),
        _stat(4242): "{}\n".format(APP_UID),
        _PROC_NET: listening((8082, APP_UID)),
        _RUN_AS: _PREFS_WITH_ENDPOINT,
        _DUMPSYS: _RESUMED.format("com.example.app"),
    }
    calls = _fake_adb_run(monkeypatch, outputs)
    assert _plain(resolve(env={})) == Endpoint("127.0.0.1", 8082, "logtok")
    assert calls == [
        ["adb", "logcat", "-d", "-v", "threadtime", "-s", "LustroToken"],
        ["adb", "shell", "stat -c %u /proc/4242 2>/dev/null"],
        ["adb", "shell", "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null"],
    ]


def test_without_package_a_token_from_the_user_reads_no_uid(monkeypatch):
    calls = _fake_adb_run(monkeypatch, {_LOGCAT: _line(4242, "logtok", 8082)})
    assert resolve(env={}, token="mine") == Endpoint("127.0.0.1", 8082, "mine")
    assert calls == [["adb", "logcat", "-d", "-v", "threadtime", "-s", "LustroToken"]]


def test_parse_prefs_keeps_a_missing_host_and_port_unknown():
    assert discovery.parse_prefs(_PREFS_XML) == discovery.Prefs("prefstok", None, None)


# ── withheld(): the server on the port must be the owner's ─────────────────────


def _owner(**fields):
    return discovery.Owner(**dict({"uid": APP_UID, "name": "com.example.app"}, **fields))


def test_a_server_of_the_owners_uid_gets_the_token():
    assert discovery.withheld(_owner(), "127.0.0.1", 8080, listening((8080, APP_UID))) is None
    # A server bound to all addresses is reached through the forward too.
    assert discovery.withheld(_owner(), "0.0.0.0", 8080, listening((8080, APP_UID))) is None


def test_another_uid_on_the_port_holds_it():
    error = discovery.withheld(_owner(), "127.0.0.1", 8080, listening((8080, OTHER_UID)))
    assert isinstance(error, discovery.PortHeld)
    assert (error.port, error.holder_uid) == (8080, OTHER_UID)
    assert error.describe().startswith(
        "port 8080 of the device is held by a process with uid 10217, not by com.example.app (uid 10213), so the CLI "
        "did not send it the token of com.example.app."
    )
    assert "waits for that" not in error.describe(waits=False)


def test_two_sockets_on_the_port_must_both_be_the_owners():
    # Such as one on loopback and one on an address of the device on a network.
    error = discovery.withheld(_owner(), "127.0.0.1", 8080, listening((8080, APP_UID), (8080, OTHER_UID)))
    assert isinstance(error, discovery.PortHeld)


def test_nothing_on_the_port_is_a_connection_that_fails():
    error = discovery.withheld(_owner(), "127.0.0.1", 8080, listening((8081, APP_UID)))
    assert not isinstance(error, discovery.TokenWithheld)
    assert error.error == "connection_failed"
    assert str(error).startswith(
        "connection_failed: nothing listens on port 8080 of the device, so the CLI did not send the token of "
        "com.example.app there"
    )


@pytest.mark.parametrize(
    "owner, net, start",
    [
        (_owner(), None, "token_withheld: adb can't tell which app listens on port 8080 of the device"),
        (_owner(uid=None), listening((8080, APP_UID)), "token_withheld: adb can't tell the uid of com.example.app"),
        (
            discovery.Owner(pid="4242"),
            listening((8080, APP_UID)),
            "token_withheld: the process that logged the last LustroToken line, PID 4242, has ended",
        ),
    ],
)
def test_what_adb_can_not_tell_withholds_the_token(owner, net, start):
    error = discovery.withheld(owner, "127.0.0.1", 8080, net)
    assert isinstance(error, discovery.TokenWithheld)
    assert str(error).startswith(start), str(error)


def test_a_token_from_the_prefs_goes_to_a_host_on_the_network_only_when_they_name_it():
    prefs = discovery.Prefs("t", "192.168.1.50", 8080)
    net = listening((8080, APP_UID))
    assert discovery.withheld(_owner(prefs=prefs), "192.168.1.50", 8080, net) is None
    error = discovery.withheld(_owner(prefs=prefs), "192.168.1.60", 8080, net)
    assert isinstance(error, discovery.TokenWithheld)
    assert str(error).startswith(
        "token_withheld: the prefs of com.example.app name 192.168.1.50:8080, not 192.168.1.60:8080"
    )
    # A token from a line goes there when the process that logged it listens on the port.
    assert discovery.withheld(_owner(), "192.168.1.60", 8080, net) is None


def test_the_guard_uses_the_sockets_from_discovery_first_and_then_reads_them_again(monkeypatch):
    reads = []
    monkeypatch.setattr(discovery, "read_net", lambda device=None: reads.append(device) or listening((8080, OTHER_UID)))
    endpoint = Endpoint("127.0.0.1", 8080, "t", owner=_owner(net=listening((8080, APP_UID))))
    guard = discovery.TokenGuard(endpoint, "emulator-5554")
    guard()
    assert reads == []
    with pytest.raises(discovery.PortHeld):
        guard()
    assert reads == ["emulator-5554"]

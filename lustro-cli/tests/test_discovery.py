"""Tests for the LustroToken log-line parser and endpoint resolution."""

from __future__ import annotations

import pytest

from lustro_cli import discovery
from lustro_cli.discovery import DiscoveryError, Endpoint, parse_ready_line, resolve


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


def test_parse_ready_line_none_when_absent():
    assert parse_ready_line("no lustro here") is None


def test_resolve_prefers_explicit_flags(monkeypatch):
    # Even if logcat would return something, explicit flags win and adb isn't called.
    monkeypatch.setattr(
        discovery, "discover_from_logcat", lambda device=None: Endpoint("9.9.9.9", 1, "x")
    )
    ep = resolve(host="10.0.0.5", port=9000, token="explicit", env={})
    assert ep == Endpoint("10.0.0.5", 9000, "explicit")


def test_resolve_uses_env_token_with_default_host_port(monkeypatch):
    monkeypatch.setattr(discovery, "discover_from_logcat", lambda device=None: None)
    ep = resolve(env={"LUSTRO_TOKEN": "envtok"})
    assert ep.token == "envtok"
    assert ep.host == "127.0.0.1"
    assert ep.port == 8080


def test_resolve_fills_from_logcat(monkeypatch):
    monkeypatch.setattr(
        discovery,
        "discover_from_logcat",
        lambda device=None: Endpoint("127.0.0.1", 54321, "logtok"),
    )
    ep = resolve(env={})
    assert ep.port == 54321
    assert ep.token == "logtok"


def test_resolve_env_token_but_logcat_host_port(monkeypatch):
    # Token from env, but host/port still come from the log line.
    monkeypatch.setattr(
        discovery,
        "discover_from_logcat",
        lambda device=None: Endpoint("127.0.0.1", 7777, "logtok"),
    )
    ep = resolve(env={"LUSTRO_TOKEN": "envtok"})
    assert ep.token == "envtok"
    assert ep.port == 7777


def test_resolve_raises_clear_error_when_no_token(monkeypatch):
    monkeypatch.setattr(discovery, "discover_from_logcat", lambda device=None: None)
    with pytest.raises(DiscoveryError) as excinfo:
        resolve(env={})
    msg = str(excinfo.value)
    assert "LUSTRO_TOKEN" in msg
    assert "LustroToken" in msg
    # A device that drops Info logs drops the ready line: the message says what to do.
    assert "log.tag" in msg
    assert "setprop log.tag.LustroToken I" in msg


def test_resolve_run_as_fallback(monkeypatch):
    monkeypatch.setattr(discovery, "discover_from_logcat", lambda device=None: None)
    monkeypatch.setattr(
        discovery,
        "discover_from_run_as",
        lambda package, device=None: Endpoint("127.0.0.1", 8080, "ratok"),
    )
    ep = resolve(env={}, package="com.example.app")
    assert ep.token == "ratok"


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


def test_discover_from_run_as_parses_prefs(monkeypatch):
    monkeypatch.setattr(discovery, "_run", lambda cmd, timeout=10.0: _PREFS_XML)
    ep = discovery.discover_from_run_as("com.example.app")
    assert ep.token == "prefstok"


def test_discover_from_run_as_ignores_an_unrelated_token_key(monkeypatch):
    # Only the runtime's own key counts; a plain "token" entry is somebody
    # else's preference and must not be handed out as the auth token.
    xml = '<map><string name="token">notours</string></map>'
    monkeypatch.setattr(discovery, "_run", lambda cmd, timeout=10.0: xml)
    assert discovery.discover_from_run_as("com.example.app") is None


def test_discover_from_run_as_none_when_no_match(monkeypatch):
    monkeypatch.setattr(discovery, "_run", lambda cmd, timeout=10.0: "<map/>")
    assert discovery.discover_from_run_as("com.example.app") is None


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


def _fake_adb_run(monkeypatch, outputs):
    """Answer each adb command from ``outputs``, keyed by its arguments; record them."""
    calls = []

    def run(cmd, timeout=10.0):
        calls.append(cmd)
        return outputs.get(" ".join(cmd))

    monkeypatch.setattr(discovery, "_run", run)
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


def test_discover_from_run_as_reads_the_endpoint_the_app_stored(monkeypatch):
    monkeypatch.setattr(discovery, "_run", lambda cmd, timeout=10.0: _PREFS_WITH_ENDPOINT)
    assert discovery.discover_from_run_as("com.example.app") == Endpoint("127.0.0.1", 8081, "prefstok")


def test_discover_from_run_as_defaults_the_endpoint_of_an_app_that_stored_none(monkeypatch):
    monkeypatch.setattr(discovery, "_run", lambda cmd, timeout=10.0: _PREFS_XML)
    assert discovery.discover_from_run_as("com.example.app") == Endpoint("127.0.0.1", 8080, "prefstok")


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
    monkeypatch.setattr(discovery, "discover_from_logcat", lambda device=None: None)
    monkeypatch.setattr(discovery, "foreground_package", lambda device=None: "com.example.app")
    seen = []

    def run_as(package, device=None):
        seen.append(package)
        return Endpoint("127.0.0.1", 8081, "prefstok")

    monkeypatch.setattr(discovery, "discover_from_run_as", run_as)
    assert resolve(env={}) == Endpoint("127.0.0.1", 8081, "prefstok")
    assert seen == ["com.example.app"]


def test_resolve_prefers_package_over_the_foreground_app(monkeypatch):
    monkeypatch.setattr(discovery, "discover_from_logcat", lambda device=None: None)
    monkeypatch.setattr(discovery, "foreground_package", lambda device=None: "com.other.app")
    seen = []
    monkeypatch.setattr(
        discovery, "discover_from_run_as", lambda package, device=None: seen.append(package) or Endpoint("h", 1, "t")
    )
    resolve(env={}, package="com.example.app")
    assert seen == ["com.example.app"]


def test_token_from_prefs(monkeypatch):
    monkeypatch.setattr(discovery, "foreground_package", lambda device=None: "com.example.app")
    monkeypatch.setattr(discovery, "_run", lambda cmd, timeout=10.0: _PREFS_WITH_ENDPOINT)
    assert discovery.token_from_prefs() == "prefstok"
    monkeypatch.setattr(discovery, "foreground_package", lambda device=None: None)
    monkeypatch.setattr(discovery, "_run", lambda cmd, timeout=10.0: None)
    assert discovery.token_from_prefs() is None


def test_resolve_takes_the_port_from_the_prefs_for_a_token_from_the_environment(monkeypatch):
    monkeypatch.setattr(discovery, "discover_from_logcat", lambda device=None: None)
    monkeypatch.setattr(discovery, "foreground_package", lambda device=None: "com.example.app")
    monkeypatch.setattr(discovery, "discover_from_run_as", lambda package, device=None: Endpoint("127.0.0.1", 8081, "prefstok"))
    assert resolve(env={"LUSTRO_TOKEN": "envtok"}) == Endpoint("127.0.0.1", 8081, "envtok")
    # Flags that give all three need no prefs.
    monkeypatch.setattr(discovery, "discover_from_run_as", lambda package, device=None: pytest.fail("read the prefs"))
    assert resolve(host="127.0.0.1", port=9000, token="t", env={}) == Endpoint("127.0.0.1", 9000, "t")


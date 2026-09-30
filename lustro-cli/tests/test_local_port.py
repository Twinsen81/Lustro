"""The local port: ``lustro open --local-port``, and the adb forward that the
other commands connect through when the app's local port differs from its port
on the device."""

from __future__ import annotations

import pytest

from lustro_cli import cli
from lustro_cli.discovery import Endpoint

TOKEN = "tok"


class _Adb:
    """Stands in for the adb forward list and for ``adb forward``."""

    def __init__(self, monkeypatch, forwarded=(), chosen=51234, error=None):
        self.forwarded = list(forwarded)
        self.chosen = chosen
        self.error = error
        self.lookups = []
        self.forwards = []
        monkeypatch.setattr(cli, "forwarded_ports", self.forwarded_ports)
        monkeypatch.setattr(cli, "_adb_forward", self.forward)

    def forwarded_ports(self, device_port, device):
        self.lookups.append((device_port, device))
        return self.forwarded

    def forward(self, local_port, device_port, device):
        self.forwards.append((local_port, device_port, device))
        if self.error:
            return local_port, self.error
        return (self.chosen if local_port == 0 else local_port), None


@pytest.fixture
def app(monkeypatch):
    """The app listens on 127.0.0.1:8080 on the device."""
    monkeypatch.setattr(cli, "_build_endpoint", lambda args: Endpoint("127.0.0.1", 8080, TOKEN))


def open_url(argv, capsys):
    assert cli.main(argv) == 0
    return capsys.readouterr().out.strip()


# ── lustro open ────────────────────────────────────────────────────────────────


def test_open_forwards_the_local_port_to_the_app_port(app, monkeypatch, capsys):
    adb = _Adb(monkeypatch)
    url = open_url(["open", "--local-port", "18080", "--print-only"], capsys)
    assert adb.forwards == [(18080, 8080, None)]
    assert url == "http://localhost:18080/#lustro_token=tok"


def test_open_local_port_0_prints_the_port_that_adb_chose(app, monkeypatch, capsys):
    adb = _Adb(monkeypatch, chosen=51234)
    url = open_url(["--device", "emulator-5554", "open", "--local-port", "0", "--print-only"], capsys)
    assert adb.forwards == [(0, 8080, "emulator-5554")]
    assert url == "http://localhost:51234/#lustro_token=tok"


def test_open_json_reports_the_local_port(app, monkeypatch, capsys):
    _Adb(monkeypatch, chosen=51234)
    assert cli.main(["--json", "open", "--local-port", "0", "--print-only"]) == 0
    out = capsys.readouterr().out
    assert '"port":51234' in out
    assert '"url":"http://localhost:51234/#lustro_token=tok"' in out


def test_open_keeps_a_forward_to_the_app_that_is_there_already(app, monkeypatch, capsys):
    adb = _Adb(monkeypatch, forwarded=[18181])
    assert open_url(["open", "--print-only"], capsys) == "http://localhost:18181/#lustro_token=tok"
    assert adb.lookups == [(8080, None)]
    assert adb.forwards == []


def test_open_local_port_0_keeps_a_forward_to_the_app_too(app, monkeypatch, capsys):
    adb = _Adb(monkeypatch, forwarded=[18181])
    assert open_url(["open", "--local-port", "0", "--print-only"], capsys) == "http://localhost:18181/#lustro_token=tok"
    assert adb.forwards == []


def test_open_local_port_that_already_forwards_to_the_app_runs_no_forward(app, monkeypatch, capsys):
    # With --no-rebind, a second forward of the same port would fail.
    adb = _Adb(monkeypatch, forwarded=[18080])
    assert open_url(["open", "--local-port", "18080", "--print-only"], capsys) == "http://localhost:18080/#lustro_token=tok"
    assert adb.forwards == []


def test_open_local_port_that_is_not_the_first_forward_to_the_app_runs_no_forward(app, monkeypatch, capsys):
    adb = _Adb(monkeypatch, forwarded=[18080, 18181])
    assert open_url(["open", "--local-port", "18181", "--print-only"], capsys) == "http://localhost:18181/#lustro_token=tok"
    assert adb.forwards == []


def test_open_keeps_the_first_of_the_forwards_to_the_app(app, monkeypatch, capsys):
    adb = _Adb(monkeypatch, forwarded=[18080, 18181])
    assert open_url(["open", "--print-only"], capsys) == "http://localhost:18080/#lustro_token=tok"
    assert adb.forwards == []


def test_open_local_port_forwards_even_when_another_port_forwards_to_the_app(app, monkeypatch, capsys):
    adb = _Adb(monkeypatch, forwarded=[18181])
    assert open_url(["open", "--local-port", "18080", "--print-only"], capsys) == "http://localhost:18080/#lustro_token=tok"
    assert adb.forwards == [(18080, 8080, None)]


def test_open_without_a_forward_forwards_the_app_port(app, monkeypatch, capsys):
    adb = _Adb(monkeypatch)
    assert open_url(["open", "--print-only"], capsys) == "http://localhost:8080/#lustro_token=tok"
    assert adb.forwards == [(8080, 8080, None)]


def test_open_forwards_to_the_port_that_port_names(monkeypatch, capsys):
    # --port is the app's port, as the LustroToken line would give it.
    monkeypatch.setattr(cli, "_build_endpoint", lambda args: Endpoint("127.0.0.1", args.port, TOKEN))
    adb = _Adb(monkeypatch)
    url = open_url(["--port", "9000", "open", "--local-port", "18181", "--print-only"], capsys)
    assert adb.lookups == [(9000, None)]
    assert adb.forwards == [(18181, 9000, None)]
    assert url == "http://localhost:18181/#lustro_token=tok"


def test_open_for_an_app_on_all_interfaces_forwards_too(monkeypatch, capsys):
    monkeypatch.setattr(cli, "_build_endpoint", lambda args: Endpoint("0.0.0.0", 8080, TOKEN))
    adb = _Adb(monkeypatch)
    assert open_url(["open", "--local-port", "18080", "--print-only"], capsys) == "http://localhost:18080/#lustro_token=tok"
    assert adb.forwards == [(18080, 8080, None)]


def test_open_hints_at_local_port_when_the_forward_fails(app, monkeypatch, capsys):
    _Adb(monkeypatch, error="adb: error: cannot rebind existing socket")
    assert cli.main(["open", "--local-port", "18080", "--print-only"]) == 1
    out, err = capsys.readouterr()
    assert out == ""
    assert "error: adb forward tcp:18080 tcp:8080 failed: adb: error: cannot rebind existing socket" in err
    assert "If local port 18080 is taken, pass --local-port 0" in err


def test_open_local_port_0_that_fails_gives_no_hint_about_a_taken_port(app, monkeypatch, capsys):
    _Adb(monkeypatch, error="adb: error: device offline")
    assert cli.main(["open", "--local-port", "0", "--print-only"]) == 1
    err = capsys.readouterr().err
    assert err == "error: adb forward tcp:0 tcp:8080 failed: adb: error: device offline\n"


def test_open_no_forward_prints_the_local_port(app, monkeypatch, capsys):
    adb = _Adb(monkeypatch)
    url = open_url(["open", "--no-forward", "--local-port", "18181", "--print-only"], capsys)
    assert url == "http://localhost:18181/#lustro_token=tok"
    assert adb.lookups == []
    assert adb.forwards == []


def test_open_no_forward_rejects_local_port_0(app, monkeypatch, capsys):
    _Adb(monkeypatch)
    assert cli.main(["open", "--no-forward", "--local-port", "0", "--print-only"]) == 2
    assert "--local-port 0 lets adb choose the port" in capsys.readouterr().err


def test_open_local_port_fails_without_adb(app, monkeypatch, capsys):
    adb = _Adb(monkeypatch)

    def forward(local_port, device_port, device):
        raise FileNotFoundError("adb")

    monkeypatch.setattr(cli, "_adb_forward", forward)
    assert cli.main(["open", "--local-port", "18080", "--print-only"]) == 1
    out, err = capsys.readouterr()
    assert out == ""
    assert err == "error: adb is not installed, so --local-port can't forward a port\n"
    assert adb.lookups == [(8080, None)]


@pytest.mark.parametrize("value", ["-1", "65536", "x"])
def test_open_rejects_a_local_port_out_of_range(value, capsys):
    with pytest.raises(SystemExit) as excinfo:
        cli.main(["open", "--local-port", value])
    assert excinfo.value.code == 2
    assert "expected a port number from 0 to 65535" in capsys.readouterr().err


# ── the other commands ─────────────────────────────────────────────────────────


@pytest.fixture
def base_urls(monkeypatch):
    """The base URL of each client that a command builds."""
    urls = []

    class _Client:
        def __init__(self, base_url, token):
            urls.append(base_url)

        def get(self, path, params=None):
            return {}

    monkeypatch.setattr(cli, "LustroClient", _Client)
    return urls


def test_commands_connect_through_the_forward_to_the_app(app, monkeypatch, base_urls):
    adb = _Adb(monkeypatch, forwarded=[18080, 18181])
    assert cli.main(["--device", "emulator-5554", "meta"]) == 0
    assert base_urls == ["http://127.0.0.1:18080"]
    assert adb.lookups == [(8080, "emulator-5554")]


def test_commands_connect_to_the_app_port_without_a_forward(app, monkeypatch, base_urls):
    _Adb(monkeypatch)
    assert cli.main(["meta"]) == 0
    assert base_urls == ["http://127.0.0.1:8080"]


def test_commands_use_the_loopback_for_an_app_on_all_interfaces(monkeypatch, base_urls):
    monkeypatch.setattr(cli, "_build_endpoint", lambda args: Endpoint("0.0.0.0", 8080, TOKEN))
    _Adb(monkeypatch, forwarded=[18080])
    assert cli.main(["meta"]) == 0
    assert base_urls == ["http://127.0.0.1:18080"]


def test_commands_use_port_as_it_is(monkeypatch, base_urls):
    # For a server on the computer, or a forward that adb doesn't list.
    monkeypatch.setattr(cli, "_build_endpoint", lambda args: Endpoint("127.0.0.1", args.port, TOKEN))
    adb = _Adb(monkeypatch, forwarded=[18080])
    assert cli.main(["--port", "18181", "net", "list"]) == 0
    assert base_urls == ["http://127.0.0.1:18181"]
    assert adb.lookups == []


def test_commands_look_for_no_forward_for_a_lan_host(monkeypatch, base_urls):
    monkeypatch.setattr(cli, "_build_endpoint", lambda args: Endpoint("192.168.1.50", 8080, TOKEN))
    adb = _Adb(monkeypatch, forwarded=[18080])
    assert cli.main(["meta"]) == 0
    assert base_urls == ["http://192.168.1.50:8080"]
    assert adb.lookups == []

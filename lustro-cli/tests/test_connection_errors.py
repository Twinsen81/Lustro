"""Connection failures: a server that closes without a response, a failed
``adb forward`` in ``lustro open``, and a reader that closes the pipe early."""

from __future__ import annotations

import json
import os
import socket
import struct
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from lustro_cli import cli
from lustro_cli.client import LustroClient, LustroError
from lustro_cli.discovery import Endpoint

TOKEN = "tok"


def _serve_raw(reply):
    """Accept connections on a free port and hand each to ``reply(conn)``, which
    answers the way a broken endpoint does. Returns the port."""
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.bind(("127.0.0.1", 0))
    listener.listen(8)

    def loop():
        while True:
            try:
                conn, _ = listener.accept()
            except OSError:
                return
            with conn:
                conn.recv(65536)
                reply(conn)

    threading.Thread(target=loop, daemon=True).start()
    return listener


def _close_without_response(conn):
    pass


def _reset(conn):
    conn.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))


def _cut_body(conn):
    conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"libr")


@pytest.fixture
def broken_server(request):
    listener = _serve_raw(request.param)
    try:
        yield "http://127.0.0.1:{}".format(listener.getsockname()[1])
    finally:
        listener.close()


@pytest.mark.parametrize("broken_server", [_close_without_response, _reset, _cut_body], indirect=True)
def test_a_connection_that_ends_without_a_response_is_connection_failed(broken_server):
    client = LustroClient(broken_server, TOKEN, timeout=5)
    with pytest.raises(LustroError) as excinfo:
        client.get("/api/v1/_meta")
    assert excinfo.value.error == "connection_failed"
    assert excinfo.value.hint


@pytest.mark.parametrize("broken_server", [_close_without_response], indirect=True)
def test_the_cli_reports_a_closed_connection_without_a_traceback(broken_server, capsys):
    port = broken_server.rsplit(":", 1)[1]
    assert cli.main(["meta", "--host", "127.0.0.1", "--port", port, "--token", TOKEN]) == 1
    err = capsys.readouterr().err
    assert err.startswith("error: connection_failed: could not reach http://127.0.0.1:{}/api/v1/_meta: ".format(port))
    assert "Remote end closed connection without response" in err
    assert "hint: " in err


# ── lustro open ────────────────────────────────────────────────────────────────


@pytest.fixture
def endpoint(monkeypatch):
    monkeypatch.setattr(cli, "_build_endpoint", lambda args: Endpoint("127.0.0.1", 8080, TOKEN))
    monkeypatch.setattr(cli, "forwarded_port", lambda device_port, device: None)


def test_open_fails_when_adb_forward_fails(endpoint, monkeypatch, capsys):
    calls = []

    def forward(local_port, device_port, device):
        calls.append((local_port, device_port, device))
        return local_port, "adb: error: cannot bind listener: Address already in use"

    monkeypatch.setattr(cli, "_adb_forward", forward)
    assert cli.main(["open", "--print-only"]) == 1
    out, err = capsys.readouterr()
    # The URL would reach whatever holds the port, so it isn't printed.
    assert out == ""
    assert "error: adb forward tcp:8080 tcp:8080 failed: adb: error: cannot bind listener" in err
    assert "hint: " in err
    assert "--local-port 0" in err
    assert calls == [(8080, 8080, None)]


def test_open_warns_and_goes_on_without_adb(endpoint, monkeypatch, capsys):
    def forward(local_port, device_port, device):
        raise FileNotFoundError("adb")

    monkeypatch.setattr(cli, "_adb_forward", forward)
    assert cli.main(["open", "--print-only"]) == 0
    out, err = capsys.readouterr()
    assert out.strip() == "http://localhost:8080/#lustro_token=tok"
    assert err == "warning: adb is not installed, so the port was not forwarded\n"


def test_open_does_not_forward_for_a_lan_host(monkeypatch, capsys):
    monkeypatch.setattr(cli, "_build_endpoint", lambda args: Endpoint("192.168.1.50", 8080, TOKEN))
    monkeypatch.setattr(cli, "forwarded_port", lambda device_port, device: pytest.fail("adb forward --list ran"))
    monkeypatch.setattr(cli, "_adb_forward", lambda *args: pytest.fail("adb forward ran"))
    assert cli.main(["open", "--print-only"]) == 0
    assert capsys.readouterr().out.strip() == "http://192.168.1.50:8080/#lustro_token=tok"


def test_open_no_forward_skips_adb(endpoint, monkeypatch, capsys):
    monkeypatch.setattr(cli, "forwarded_port", lambda device_port, device: pytest.fail("adb forward --list ran"))
    monkeypatch.setattr(cli, "_adb_forward", lambda *args: pytest.fail("adb forward ran"))
    assert cli.main(["open", "--no-forward", "--print-only"]) == 0


def _fake_adb(tmp_path, monkeypatch, script):
    adb = tmp_path / "adb"
    adb.write_text("#!/bin/sh\n" + script)
    adb.chmod(0o755)
    monkeypatch.setenv("PATH", str(tmp_path))


@pytest.mark.skipif(os.name == "nt", reason="uses a POSIX shell script as adb")
def test_adb_forward_returns_what_adb_reports(tmp_path, monkeypatch):
    _fake_adb(tmp_path, monkeypatch, 'echo "adb: error: more than one device/emulator" >&2\nexit 1\n')
    assert cli._adb_forward(8080, 8080, None) == (8080, "adb: error: more than one device/emulator")


@pytest.mark.skipif(os.name == "nt", reason="uses a POSIX shell script as adb")
def test_adb_forward_passes_the_device_and_both_ports(tmp_path, monkeypatch):
    args_file = tmp_path / "args"
    _fake_adb(tmp_path, monkeypatch, 'echo "$@" > "{}"\n'.format(args_file))
    assert cli._adb_forward(18080, 8080, "emulator-5554") == (18080, None)
    # --no-rebind: a local port that another device's forward holds stays with it.
    assert args_file.read_text().strip() == "-s emulator-5554 forward --no-rebind tcp:18080 tcp:8080"


@pytest.mark.skipif(os.name == "nt", reason="uses a POSIX shell script as adb")
def test_adb_forward_returns_the_local_port_that_adb_chose(tmp_path, monkeypatch):
    args_file = tmp_path / "args"
    _fake_adb(tmp_path, monkeypatch, 'echo "$@" > "{}"\necho 51234\n'.format(args_file))
    assert cli._adb_forward(0, 8080, None) == (51234, None)
    assert args_file.read_text().strip() == "forward --no-rebind tcp:0 tcp:8080"


@pytest.mark.skipif(os.name == "nt", reason="uses a POSIX shell script as adb")
def test_adb_forward_fails_when_adb_prints_no_local_port(tmp_path, monkeypatch):
    _fake_adb(tmp_path, monkeypatch, "exit 0\n")
    port, err = cli._adb_forward(0, 8080, None)
    assert port == 0
    assert err == "adb did not print the local port that it chose: ''"


def test_adb_forward_raises_when_adb_is_missing(tmp_path, monkeypatch):
    monkeypatch.setenv("PATH", str(tmp_path))
    with pytest.raises(FileNotFoundError):
        cli._adb_forward(8080, 8080, None)


# ── a reader that stops early ──────────────────────────────────────────────────


class _ListHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        items = [
            {"id": "tx_{}".format(n), "method": "GET", "url": "https://api.example.com/v1/items/{}".format(n)}
            for n in range(5000)
        ]
        body = json.dumps({"cursor": "c:1", "status": "reset", "items": items}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def test_a_reader_that_closes_the_pipe_early_gets_no_traceback():
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), _ListHandler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    try:
        port = str(httpd.server_address[1])
        code = "import sys; from lustro_cli.cli import main; sys.exit(main(sys.argv[1:]))"
        argv = ["--port", port, "--token", TOKEN, "--json", "net", "list", "--all"]
        proc = subprocess.Popen(
            [sys.executable, "-c", code] + argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE
        )
        # Read one line, then stop, as `head -n 1` does.
        assert json.loads(proc.stdout.readline())["id"] == "tx_0"
        proc.stdout.close()
        err = proc.stderr.read().decode("utf-8")
        proc.stderr.close()
        assert proc.wait(timeout=30) == 1
        assert err == ""
    finally:
        httpd.shutdown()
        httpd.server_close()


def test_a_reader_that_closed_the_pipe_before_any_output_gets_no_traceback():
    code = "import sys; from lustro_cli.cli import main; sys.exit(main(sys.argv[1:]))"
    argv = ["--host", "127.0.0.1", "--port", "8080", "--token", TOKEN, "open", "--no-forward", "--print-only"]
    proc = subprocess.Popen([sys.executable, "-c", code] + argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    # Closed before the CLI writes, as `true` or `head -c 0` does. The URL is
    # short, so it stays in the buffer until the command returns.
    proc.stdout.close()
    err = proc.stderr.read().decode("utf-8")
    proc.stderr.close()
    assert proc.wait(timeout=30) == 1
    assert err == ""

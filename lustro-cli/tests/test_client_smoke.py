"""Smoke test: drive LustroClient against a local http.server serving golden data."""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from lustro_cli import cli, wire
from lustro_cli.client import LustroClient, LustroError

TOKEN = "test-token-123"

# The signature and IHDR chunk of a 1x1 PNG: enough to tell bytes from text.
PNG_BYTES = bytes.fromhex("89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489")
# A UUID, as the Lustro runtime makes it, so that `net body` needs no list request.
IMAGE_TX = "c81f5e02-6f3a-4b7e-9d21-5a0c3e8b7f14"


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # silence
        pass

    def _auth_ok(self):
        return self.headers.get("Authorization") == "Bearer " + TOKEN

    def _send_json(self, status, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if not self._auth_ok():
            self._send_json(401, {"error": "unauthorized", "message": "missing token"})
            return
        if self.path == "/api/v1/_meta":
            self._send_json(200, wire.load_golden("meta.json"))
        elif self.path == "/api/v1/network/transactions/{}/body/response".format(IMAGE_TX):
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(PNG_BYTES)))
            self.end_headers()
            self.wfile.write(PNG_BYTES)
        elif self.path.startswith("/api/v1/network/transactions/"):
            tx_id = self.path.rsplit("/", 1)[-1]
            if tx_id == "tx_77e2c014":
                self._send_json(200, wire.load_golden("transaction.json"))
            elif tx_id == IMAGE_TX:
                self._send_json(200, dict(wire.load_golden("transaction-image.json"), id=IMAGE_TX))
            else:
                self._send_json(
                    404, wire.load_golden("error-envelope.json")
                )
        elif self.path.startswith("/api/v1/network/transactions"):
            self._send_json(200, wire.load_golden("cursor-reset.json"))
        elif self.path == "/api/v1/network/rules":
            self._send_json(200, wire.load_golden("rules-list.json"))
        else:
            self._send_json(404, {"error": "not_found", "message": "no route"})

    def do_POST(self):
        if not self._auth_ok():
            self._send_json(401, {"error": "unauthorized", "message": "missing token"})
            return
        length = int(self.headers.get("Content-Length", 0))
        if length:
            self.rfile.read(length)
        if self.path == "/api/v1/network/send":
            self._send_json(200, wire.load_golden("send-result.json"))
        elif self.path == "/api/v1/network/clear":
            self._send_json(200, {"status": "ok"})
        else:
            self._send_json(404, {"error": "not_found", "message": "no route"})


@pytest.fixture
def server():
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    host, port = httpd.server_address
    try:
        yield "http://{}:{}".format(host, port)
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=5)


def test_meta(server):
    client = LustroClient(server, TOKEN)
    meta = client.get("/api/v1/_meta")
    assert meta["protocolVersion"] == "1.5"
    assert meta["tabs"][0]["id"] == "network"


def test_transactions_cursor(server):
    client = LustroClient(server, TOKEN)
    data = client.get("/api/v1/network/transactions")
    assert data["status"] == "reset"
    assert data["cursor"] == "c:42"
    assert len(data["items"]) == 2


def test_transaction_detail(server):
    client = LustroClient(server, TOKEN)
    tx = client.get("/api/v1/network/transactions/tx_77e2c014")
    assert tx["id"] == "tx_77e2c014"
    assert tx["responseBody"] == '{"error":"boom"}'


def test_get_raw_returns_the_body_undecoded(server):
    client = LustroClient(server, TOKEN)
    body = client.get_raw("/api/v1/network/transactions/{}/body/response".format(IMAGE_TX))
    assert body.data == PNG_BYTES
    assert body.content_type == "image/png"


def test_get_raw_raises_the_error_envelope(server):
    client = LustroClient(server, TOKEN)
    with pytest.raises(LustroError) as excinfo:
        client.get_raw("/api/v1/network/transactions/tx_77e2c014/body/request")
    assert excinfo.value.status == 404


def test_net_body_saves_a_captured_image(server, tmp_path, capsys):
    host, port = server.rsplit("//", 1)[1].split(":")
    out = tmp_path / "avatar.png"
    argv = ["--host", host, "--port", port, "--token", TOKEN, "net", "body", IMAGE_TX, "-o", str(out)]
    assert cli.main(argv) == 0
    assert out.read_bytes() == PNG_BYTES
    assert capsys.readouterr().out.strip() == "saved {} bytes (image/png) to {}".format(len(PNG_BYTES), out)


def test_missing_transaction_raises_typed_error(server):
    client = LustroClient(server, TOKEN)
    with pytest.raises(LustroError) as excinfo:
        client.get("/api/v1/network/transactions/does-not-exist")
    err = excinfo.value
    assert err.status == 404
    assert err.error == "not_found"
    assert err.field == "id"


def test_unauthorized_raises_401(server):
    client = LustroClient(server, "wrong-token")
    with pytest.raises(LustroError) as excinfo:
        client.get("/api/v1/_meta")
    assert excinfo.value.status == 401
    assert excinfo.value.error == "unauthorized"


def test_a_401_retries_once_with_a_fresh_token(server):
    asked = []

    def refresh():
        asked.append(True)
        return TOKEN

    client = LustroClient(server, "stale-token", refresh_token=refresh)
    assert client.get("/api/v1/_meta")["protocolVersion"] == "1.5"
    assert client.token == TOKEN
    # The client asks once: a later 401 is an error.
    assert asked == [True]


def test_a_401_is_an_error_when_the_fresh_token_is_the_same(server):
    client = LustroClient(server, "stale-token", refresh_token=lambda: "stale-token")
    with pytest.raises(LustroError) as excinfo:
        client.get("/api/v1/_meta")
    assert excinfo.value.status == 401


def test_send_post(server):
    client = LustroClient(server, TOKEN)
    result = client.post(
        "/api/v1/network/send", json_body={"url": "https://x/y", "method": "GET"}
    )
    assert result["ok"] is True
    # The synchronous send path does not correlate the replay to a captured
    # transaction, so the server emits transactionId:null (schema allows null).
    assert result["transactionId"] is None


def test_query_params_encoded(server):
    client = LustroClient(server, TOKEN)
    # search param should be accepted and not break routing.
    data = client.get("/api/v1/network/transactions", params={"search": "orders", "cursor": None})
    assert data["status"] == "reset"


def test_connection_refused_is_typed():
    client = LustroClient("http://127.0.0.1:1", "tok", timeout=1)
    with pytest.raises(LustroError) as excinfo:
        client.get("/api/v1/_meta")
    assert excinfo.value.error == "connection_failed"

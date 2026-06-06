"""Smoke test: drive LustroClient against a local http.server serving golden data."""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from lustro_cli import wire
from lustro_cli.client import LustroClient, LustroError

TOKEN = "test-token-123"


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
        elif self.path.startswith("/api/v1/network/transactions/"):
            tx_id = self.path.rsplit("/", 1)[-1]
            if tx_id == "tx_77e2c014":
                self._send_json(200, wire.load_golden("transaction.json"))
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
    assert meta["protocolVersion"] == "1.0"
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


def test_send_post(server):
    client = LustroClient(server, TOKEN)
    result = client.post(
        "/api/v1/network/send", json_body={"url": "https://x/y", "method": "GET"}
    )
    assert result["ok"] is True
    assert result["transactionId"] == "tx_a1b2c3d4"


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

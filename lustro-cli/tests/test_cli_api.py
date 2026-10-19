"""`lustro api`: any request to any route, against a local server that echoes it."""

from __future__ import annotations

import io
import json
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from lustro_cli import cli, wire
from lustro_cli.client import LustroClient

TOKEN = "test-token-123"
SCRIPT = b"export function mount() {}\n"


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # silence
        pass

    def _send(self, status, body, content_type):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_json(self, status, obj):
        self._send(status, json.dumps(obj).encode("utf-8"), "application/json; charset=utf-8")

    def _handle(self):
        if self.headers.get("Authorization") != "Bearer " + TOKEN:
            self._send_json(401, {"error": "unauthorized", "message": "missing token"})
            return
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8") if length else None
        url = urllib.parse.urlsplit(self.path)
        if url.path == "/api/v1/echo":
            self._send_json(
                200,
                {
                    "method": self.command,
                    "path": url.path,
                    "query": urllib.parse.parse_qsl(url.query),
                    "contentType": self.headers.get("Content-Type"),
                    "body": body,
                },
            )
        elif url.path == "/api/v1/logs/_view.js":
            self._send(200, SCRIPT, "text/javascript")
        elif url.path == "/api/v1/network/clear":
            self.send_response(204)
            self.end_headers()
        else:
            self._send_json(404, wire.load_golden("error-envelope.json"))

    do_GET = _handle
    do_POST = _handle
    do_DELETE = _handle


@pytest.fixture
def lustro():
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    host, port = httpd.server_address

    def run(*argv):
        return cli.main(["--host", host, "--port", str(port), "--token", TOKEN, "api", *argv])

    try:
        yield run
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=5)


def echoed(capsys):
    return json.loads(capsys.readouterr().out)


def test_a_path_without_a_slash_is_under_api_v1(lustro, capsys):
    assert lustro("GET", "echo") == 0
    assert echoed(capsys)["path"] == "/api/v1/echo"


def test_params_repeat_and_join_the_query_in_the_path(lustro, capsys):
    assert lustro("get", "/api/v1/echo?cursor=c:1", "--param", "level=W", "--param", "level=E", "--param", "q=a=b") == 0
    reply = echoed(capsys)
    assert reply["method"] == "GET"
    assert reply["query"] == [["cursor", "c:1"], ["level", "W"], ["level", "E"], ["q", "a=b"]]


def test_body_is_sent_as_json(lustro, capsys):
    assert lustro("POST", "echo", "--body", '{"delayMs": 250}') == 0
    reply = echoed(capsys)
    assert reply["method"] == "POST"
    assert reply["contentType"] == "application/json"
    assert json.loads(reply["body"]) == {"delayMs": 250}


def test_input_reads_the_body_from_a_file(lustro, capsys, tmp_path):
    rules = tmp_path / "rules.json"
    rules.write_text('[{"id": "a", "urlPattern": "/a"}]', encoding="utf-8")
    assert lustro("POST", "echo", "--input", str(rules)) == 0
    assert json.loads(echoed(capsys)["body"]) == [{"id": "a", "urlPattern": "/a"}]


def test_input_dash_reads_the_body_from_stdin(lustro, capsys, monkeypatch):
    monkeypatch.setattr("sys.stdin", io.StringIO('{"id": "a"}'))
    assert lustro("DELETE", "echo", "--input", "-") == 0
    reply = echoed(capsys)
    assert reply["method"] == "DELETE"
    assert json.loads(reply["body"]) == {"id": "a"}


def test_without_a_body_nothing_is_sent(lustro, capsys):
    assert lustro("POST", "echo") == 0
    reply = echoed(capsys)
    assert reply["body"] is None
    assert reply["contentType"] is None


def test_json_prints_compact_when_stdout_is_not_a_terminal(lustro, capsys):
    assert lustro("GET", "echo") == 0
    out = capsys.readouterr().out
    assert out.count("\n") == 1
    assert out.startswith('{"method":"GET"')


def test_another_body_prints_as_it_is(lustro, capsysbinary):
    assert lustro("GET", "logs/_view.js") == 0
    assert capsysbinary.readouterr().out == SCRIPT


def test_an_empty_response_prints_nothing(lustro, capsys):
    assert lustro("POST", "network/clear") == 0
    assert capsys.readouterr().out == ""


def test_an_error_prints_the_envelope_on_stderr_and_exits_1(lustro, capsys):
    assert lustro("GET", "network/transactions/nope") == 1
    captured = capsys.readouterr()
    assert captured.out == ""
    envelope = wire.load_golden("error-envelope.json")
    assert captured.err.startswith("error: 404 {}: {}".format(envelope["error"], envelope["message"]))


def test_a_body_that_is_not_json_is_a_usage_error(lustro, capsys):
    assert lustro("POST", "echo", "--body", "{delayMs: 1}") == 2
    assert "--body is not JSON" in capsys.readouterr().err


def test_a_url_is_a_usage_error(lustro, capsys):
    assert lustro("GET", "http://127.0.0.1:8080/api/v1/_meta") == 2
    assert "not a URL" in capsys.readouterr().err


@pytest.mark.parametrize(
    "argv",
    [
        ["GET", "echo", "--param", "level"],
        ["GET", "echo", "--param", "=W"],
        ["GET/x", "echo"],
        ["POST", "echo", "--body", "{}", "--input", "-"],
    ],
)
def test_bad_arguments_exit_2(lustro, argv):
    with pytest.raises(SystemExit) as excinfo:
        lustro(*argv)
    assert excinfo.value.code == 2


def test_client_url_appends_params_to_a_query():
    client = LustroClient("http://127.0.0.1:8080")
    assert client._url("/api/v1/x?a=1", [("b", "2"), ("b", "3")]) == "http://127.0.0.1:8080/api/v1/x?a=1&b=2&b=3"
    assert client._url("/api/v1/x", {"a": "1", "b": None}) == "http://127.0.0.1:8080/api/v1/x?a=1"

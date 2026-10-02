"""Tests for the `lustro net ws` commands: the routes they call, and the rows,
JSON Lines, and payload files that they print or write."""

from __future__ import annotations

import json

import pytest

from lustro_cli import cli, wire
from lustro_cli.client import LustroClient, LustroError, RawBody
from lustro_cli.discovery import Endpoint

WS = "3d2a7f10-5b1c-4e2f-8a9d-0c1b2a3f4e5d"
EVENTS = "/api/v1/network/websockets/" + WS + "/events"


class _ScriptedClient(LustroClient):
    """Answers each request with the next scripted response, and records the
    request. When the script runs out, it raises ``then``."""

    def __init__(self):
        super().__init__("http://127.0.0.1:8080", "tok")
        self.calls = []
        self.responses = []
        self.then = LustroError("not_found", "WebSocket connection not found", status=404)
        self.raw = RawBody(b'{"type":"ready"}', "text/plain; charset=utf-8")

    def request(self, method, path, *, params=None, json_body=None):
        self.calls.append((method, path, {k: v for k, v in (params or {}).items() if v is not None}))
        if self.responses:
            return self.responses.pop(0)
        raise self.then

    def get_raw(self, path, params=None):
        self.calls.append(("GET", path, {}))
        return self.raw


@pytest.fixture
def client(monkeypatch):
    scripted = _ScriptedClient()
    monkeypatch.setattr(cli, "_build_client", lambda args: scripted)
    monkeypatch.setattr(cli, "_build_endpoint", lambda args: Endpoint("127.0.0.1", 8080, "tok"))
    return scripted


class _NoSleep:
    """Stands in for the ``time`` module in cli, so that --follow does not wait."""

    def __init__(self):
        self.sleeps = []

    def sleep(self, seconds):
        self.sleeps.append(seconds)


def _connections():
    return wire.load_golden("websockets-reset.json")


def test_ws_list_prints_one_row_for_each_connection(client, capsys):
    client.responses = [_connections()]
    assert cli.main(["net", "ws", "list"]) == 0
    assert client.calls == [("GET", "/api/v1/network/websockets", {})]
    assert capsys.readouterr().out.splitlines() == [
        "ws_3d2a7  14:22:17.098  open        sent=2 received=2  wss://chat.example.com/socket?token=%5BREDACTED%5D",
        "ws_91b04  14:21:40.310  closed 1000 sent=14 received=2210  wss://chat.example.com/socket?token=%5BREDACTED%5D",
        "ws_6a5f0  14:21:02.004  failed 403  sent=0 received=0  wss://feed.example.com/v2/stream",
    ]


def test_ws_list_json_prints_one_object_for_each_line_and_fields_keeps_keys(client, capsys):
    client.responses = [_connections(), _connections()]
    assert cli.main(["net", "ws", "list", "--json"]) == 0
    lines = capsys.readouterr().out.splitlines()
    assert [json.loads(line)["id"] for line in lines] == ["ws_3d2a7f10", "ws_91b04c2e", "ws_6a5f0e83"]

    assert cli.main(["net", "ws", "list", "--fields", "id,state"]) == 0
    assert json.loads(capsys.readouterr().out.splitlines()[0]) == {"id": "ws_3d2a7f10", "state": "open"}


def test_ws_list_rejects_a_field_that_no_connection_has(client, capsys):
    client.responses = [_connections()]
    assert cli.main(["net", "ws", "list", "--fields", "status"]) == 2
    assert "connections have no status" in capsys.readouterr().err


def test_ws_list_says_when_capture_is_paused(client, capsys):
    paused = _connections()
    paused["state"]["paused"] = True
    client.responses = [paused]
    assert cli.main(["net", "ws", "list"]) == 0
    assert "no message is recorded" in capsys.readouterr().err


def test_ws_get_takes_a_short_id(client, capsys):
    client.responses = [_connections(), wire.load_golden("websocket.json")]
    assert cli.main(["net", "ws", "get", "ws_3d"]) == 0
    assert client.calls[-1] == ("GET", "/api/v1/network/websockets/ws_3d2a7f10", {})
    assert json.loads(capsys.readouterr().out)["requestHeaders"]["Authorization"] == "[REDACTED]"


def test_an_ambiguous_connection_id_prints_the_matches(client, capsys):
    client.responses = [_connections()]
    assert cli.main(["net", "ws", "get", "ws_"]) == 2
    err = capsys.readouterr().err
    assert "3 connection ids start with ws_" in err
    assert "closed 1000" in err


def test_an_unknown_connection_id_names_the_list_command(client, capsys):
    client.responses = [_connections()]
    assert cli.main(["net", "ws", "get", "nope"]) == 1
    assert "lustro net ws list" in capsys.readouterr().err


def test_ws_events_prints_the_log_oldest_first(client, capsys):
    client.responses = [wire.load_golden("stream-reset.json")]
    assert cli.main(["net", "ws", "events", WS]) == 0
    assert client.calls == [("GET", EVENTS, {"limit": 50})]
    assert capsys.readouterr().out.splitlines() == [
        "    1  14:22:17.243  --  open 101",
        '    2  14:22:17.250  ->  text         36B  {"type":"auth","token":"[REDACTED]"}',
        '    3  14:22:17.391  <-  text         16B  {"type":"ready"}',
        "    4  14:22:18.004  ->  binary        4B  deadbeef",
        '    5  14:22:19.112  <-  text     300000B  {"type":"snapshot","items":[{"id":1,"na'
        "[... 299961 more bytes: lustro net ws payload 3d2a7f10 5] [truncated]",
    ]


def test_ws_events_marks_a_refused_send_and_shows_the_close(client, capsys):
    client.responses = [wire.load_golden("stream-delta.json")]
    assert cli.main(["net", "ws", "events", WS]) == 0
    assert capsys.readouterr().out.splitlines() == [
        " 1009  14:22:20.010  --  close sent 1000 bye",
        " 1010  14:22:20.019  ->  text          8B  too late [not sent]",
        " 1011  14:22:21.018  --  close received 1000 bye",
        " 1012  14:22:21.020  --  closed 1000 bye",
    ]


def test_ws_events_passes_its_filters_to_the_server(client):
    client.responses = [wire.load_golden("stream-unchanged.json"), wire.load_golden("stream-unchanged.json")]
    assert cli.main(["net", "ws", "events", WS, "--last", "5", "--sent", "--search", "auth"]) == 0
    assert client.calls[-1] == ("GET", EVENTS, {"limit": 5, "direction": "sent", "search": "auth"})
    assert cli.main(["net", "ws", "events", WS, "--all", "--received"]) == 0
    assert client.calls[-1] == ("GET", EVENTS, {"limit": cli.ALL_EVENTS, "direction": "received"})


def test_ws_events_json_prints_json_lines(client, capsys):
    client.responses = [wire.load_golden("stream-reset.json")]
    assert cli.main(["net", "ws", "events", WS, "--fields", "seq,kind,direction"]) == 0
    lines = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    assert lines[0] == {"seq": 1, "kind": "open", "direction": None}
    assert lines[1] == {"seq": 2, "kind": "message", "direction": "sent"}


def test_ws_events_follow_prints_only_what_is_new_and_says_what_it_missed(client, capsys, monkeypatch):
    clock = _NoSleep()
    monkeypatch.setattr(cli, "time", clock)
    client.responses = [
        wire.load_golden("stream-reset.json"),
        wire.load_golden("stream-unchanged.json"),
        wire.load_golden("stream-delta.json"),
    ]
    # The script runs out after the delta, and the connection is gone: the command stops.
    assert cli.main(["net", "ws", "events", WS, "--follow", "--interval", "0.5"]) == 1
    out, err = capsys.readouterr()
    assert [line.split()[0] for line in out.splitlines()] == ["1", "2", "3", "4", "5", "1009", "1010", "1011", "1012"]
    assert "evicted 3 events" in err
    assert "WebSocket connection not found" in err
    # Each poll after the first sends the last cursor back.
    assert [call[2].get("cursor") for call in client.calls] == [None, "c:5", "c:1004", "c:1004"]
    assert clock.sleeps == [0.5, 0.5, 0.5]


def test_ws_events_follow_says_when_the_log_started_again(client, capsys, monkeypatch):
    monkeypatch.setattr(cli, "time", _NoSleep())
    client.responses = [wire.load_golden("stream-unchanged.json"), wire.load_golden("stream-reset.json")]
    assert cli.main(["net", "ws", "events", WS, "--follow"]) == 1
    out, err = capsys.readouterr()
    assert len(out.splitlines()) == 5
    assert "the log started again" in err


def test_ws_payload_writes_the_text_to_stdout(client, capsysbinary):
    assert cli.main(["net", "ws", "payload", WS, "3"]) == 0
    assert client.calls == [("GET", EVENTS + "/3/payload", {})]
    assert capsysbinary.readouterr().out == b'{"type":"ready"}'


def test_ws_payload_saves_a_binary_payload_to_a_file(client, capsys, tmp_path):
    client.raw = RawBody(b"\xde\xad\xbe\xef", "application/octet-stream")
    out = tmp_path / "payload.bin"
    assert cli.main(["net", "ws", "payload", WS, "4", "-o", str(out), "--json"]) == 0
    assert out.read_bytes() == b"\xde\xad\xbe\xef"
    assert json.loads(capsys.readouterr().out) == {"path": str(out), "bytes": 4, "binary": True}


def test_ws_payload_does_not_write_bytes_to_a_terminal(client, capsys, monkeypatch):
    client.raw = RawBody(b"\xde\xad\xbe\xef", "application/octet-stream")
    monkeypatch.setattr("sys.stdout.isatty", lambda: True)
    assert cli.main(["net", "ws", "payload", WS, "4"]) == 2
    assert "binary" in capsys.readouterr().err

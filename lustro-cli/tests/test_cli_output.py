"""Tests for the CLI output that agents read: the list rows, filters, --fields,
JSON Lines, the cut bodies of net get, net state, net poll updates, and net wait."""

from __future__ import annotations

import json
import os
import sys

import pytest

from lustro_cli import cli
from lustro_cli.client import LustroClient
from lustro_cli.discovery import Endpoint


class _ScriptedClient(LustroClient):
    """Answers each request with the next scripted response, and records the
    request. A callable response is called first. When the script runs out, it
    answers "unchanged", or raises ``then`` when that is set."""

    def __init__(self):
        super().__init__("http://127.0.0.1:8080", "tok")
        self.calls = []
        self.responses = []
        self.then = None

    def request(self, method, path, *, params=None, json_body=None):
        self.calls.append((method, path, dict(params or {}), json_body))
        if self.responses:
            response = self.responses.pop(0)
            return response() if callable(response) else response
        if self.then is not None:
            raise self.then
        return {"cursor": "c:end", "status": "unchanged"}


@pytest.fixture
def client(monkeypatch):
    scripted = _ScriptedClient()
    monkeypatch.setattr(cli, "_build_client", lambda args: scripted)
    monkeypatch.setattr(cli, "_build_endpoint", lambda args: Endpoint("127.0.0.1", 8080, "tok"))
    return scripted


class _FakeClock:
    """Stands in for the ``time`` module in cli: a sleep moves the clock on at once."""

    def __init__(self):
        self.now = 1000.0
        self.sleeps = []

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds


@pytest.fixture
def clock(monkeypatch):
    fake = _FakeClock()
    monkeypatch.setattr(cli, "time", fake)
    return fake


def transaction(n, **fields):
    """A list item as the server sends it: complete, unless ``fields`` change that."""
    tx = {
        "id": "tx_{}".format(n),
        "timestamp": "14:22:{:02d}.000".format(n % 60),
        "startedAt": 1790605327000 + n,
        "completedAt": 1790605327100 + n,
        "method": "GET",
        "url": "https://api.example.com/v1/items/{}".format(n),
        "protocol": "h2",
        "statusCode": 200,
        "durationMs": 100,
        "categories": [],
        "isMocked": False,
        "requestContentType": None,
        "responseContentType": "application/json",
        "requestBodyBytes": 0,
        "responseBodyBytes": 10,
        "responseComplete": True,
        "error": None,
    }
    tx.update(fields)
    return tx


def in_flight(n, **fields):
    values = dict(completedAt=None, statusCode=None, durationMs=None, responseComplete=False)
    values.update(fields)
    return transaction(n, **values)


def envelope(items, *, status="reset", cursor="c:1", paused=False):
    """A transactions poll response. ``items`` are oldest first, and the envelope
    lists them newest first, as the server does."""
    return {
        "cursor": cursor,
        "status": status,
        "state": {"paused": paused, "overwriteMode": False, "throttleDelayMs": 0, "captureFilter": None},
        "items": list(reversed(items)),
    }


def lines(text):
    return text.splitlines()


def json_lines(text):
    return [json.loads(line) for line in text.splitlines()]


# ── global flags ───────────────────────────────────────────────────────────────


def test_global_flags_work_after_the_command(client, monkeypatch, capsys):
    seen = {}

    def build_client(args):
        seen.update(vars(args))
        return client

    monkeypatch.setattr(cli, "_build_client", build_client)
    client.responses = [envelope([transaction(1)])]
    argv = ["net", "list", "--json", "--host", "10.0.0.2", "--port", "9000", "--token", "t", "--device", "S1"]
    assert cli.main(argv + ["--package", "com.example"]) == 0
    assert (seen["host"], seen["port"], seen["token"], seen["device"], seen["package"]) == (
        "10.0.0.2",
        9000,
        "t",
        "S1",
        "com.example",
    )
    assert json_lines(capsys.readouterr().out) == [transaction(1)]


def test_a_flag_before_the_command_survives_the_subcommand_parser(client, capsys):
    client.responses = [envelope([transaction(1)])]
    assert cli.main(["--json", "net", "list"]) == 0
    assert json_lines(capsys.readouterr().out) == [transaction(1)]


def test_global_flags_default_when_not_given(client, monkeypatch):
    seen = {}

    def build_client(args):
        seen.update(vars(args))
        return client

    monkeypatch.setattr(cli, "_build_client", build_client)
    cli.main(["meta"])
    assert {name: seen[name] for name in cli.GLOBAL_DEFAULTS} == cli.GLOBAL_DEFAULTS


# ── net list ───────────────────────────────────────────────────────────────────


def test_list_rows_start_with_the_id_and_show_the_duration(client, capsys):
    client.responses = [envelope([transaction(1, durationMs=184, url="https://api.example.com/v1/orders")])]
    assert cli.main(["net", "list"]) == 0
    assert lines(capsys.readouterr().out) == [
        "tx_1  14:22:01.000  GET     200   184ms  https://api.example.com/v1/orders"
    ]


def test_list_rows_mark_in_flight_failed_mocked_and_streaming_requests(client, capsys):
    client.responses = [
        envelope(
            [
                in_flight(1),
                transaction(2, statusCode=None, error="java.net.UnknownHostException"),
                transaction(3, isMocked=True, statusCode=500),
                transaction(4, completedAt=None, responseComplete=False),
            ]
        )
    ]
    cli.main(["net", "list"])
    assert lines(capsys.readouterr().out) == [
        "tx_4  14:22:04.000  GET     200   100ms  https://api.example.com/v1/items/4 [streaming]",
        "tx_3  14:22:03.000  GET     500   100ms  https://api.example.com/v1/items/3 [mock]",
        "tx_2  14:22:02.000  GET     ERR   100ms  https://api.example.com/v1/items/2",
        "tx_1  14:22:01.000  GET     ...       -  https://api.example.com/v1/items/1",
    ]


def test_list_prints_the_newest_50_and_says_how_many_it_left_out(client, capsys):
    client.responses = [envelope([transaction(n) for n in range(1000)])]
    assert cli.main(["net", "list"]) == 0
    out, err = capsys.readouterr()
    rows = lines(out)
    assert len(rows) == 50
    assert rows[0].startswith("tx_999 ") and rows[-1].startswith("tx_950 ")
    assert err == "showing the newest 50 of 1000, use --all for every row\n"


def test_list_last_and_all(client, capsys):
    client.responses = [envelope([transaction(n) for n in range(100)])] * 2
    cli.main(["net", "list", "--last", "3"])
    out, err = capsys.readouterr()
    assert [row.split()[0] for row in lines(out)] == ["tx_99", "tx_98", "tx_97"]
    assert "showing the newest 3 of 100" in err

    cli.main(["net", "list", "--all"])
    out, err = capsys.readouterr()
    assert len(lines(out)) == 100
    assert err == ""


def test_list_last_and_all_exclude_each_other(client):
    with pytest.raises(SystemExit) as excinfo:
        cli.main(["net", "list", "--last", "3", "--all"])
    assert excinfo.value.code == 2


def test_list_last_rejects_zero(client, capsys):
    with pytest.raises(SystemExit):
        cli.main(["net", "list", "--last", "0"])
    assert "expected a whole number of 1 or more" in capsys.readouterr().err


def _ids(out):
    return [row.split()[0] for row in lines(out)]


@pytest.mark.parametrize(
    "flags, expected",
    [
        (["--status", "404"], ["tx_3"]),
        (["--status", "4xx"], ["tx_4", "tx_3"]),
        (["--status", "5XX"], ["tx_5"]),
        (["--errors"], ["tx_6", "tx_5", "tx_4", "tx_3"]),
        (["--method", "post"], ["tx_2"]),
        (["--url", "/ORDERS"], ["tx_2", "tx_1"]),
        (["--url", "/orders", "--method", "GET"], ["tx_1"]),
        (["--errors", "--status", "5xx"], ["tx_5"]),
    ],
)
def test_list_filters(client, capsys, flags, expected):
    client.responses = [
        envelope(
            [
                transaction(1, url="https://api.example.com/v1/orders"),
                transaction(2, method="POST", url="https://api.example.com/v1/orders"),
                transaction(3, statusCode=404),
                transaction(4, statusCode=401),
                transaction(5, statusCode=503),
                transaction(6, statusCode=None, error="timeout"),
                in_flight(7),
            ]
        )
    ]
    assert cli.main(["net", "list"] + flags) == 0
    assert _ids(capsys.readouterr().out) == expected


def test_list_client_filters_combine_with_the_server_search(client, capsys):
    client.responses = [envelope([transaction(1, statusCode=500), transaction(2)])]
    cli.main(["net", "list", "--search", "orders", "--errors"])
    assert client.calls[-1][2] == {"search": "orders"}
    assert _ids(capsys.readouterr().out) == ["tx_1"]


def test_list_footer_counts_the_matching_transactions(client, capsys):
    client.responses = [envelope([transaction(n, statusCode=500 if n % 2 else 200) for n in range(200)])]
    cli.main(["net", "list", "--errors", "--last", "10"])
    assert capsys.readouterr().err == "showing the newest 10 of 100, use --all for every row\n"


@pytest.mark.parametrize("value", ["40", "6xx", "4x", "abc", "4000"])
def test_status_rejects_what_is_not_a_code_or_a_class(client, capsys, value):
    with pytest.raises(SystemExit) as excinfo:
        cli.main(["net", "list", "--status", value])
    assert excinfo.value.code == 2
    assert "expected a status code such as 404, or a class such as 4xx" in capsys.readouterr().err


def test_list_json_prints_one_compact_object_per_line(client, capsys):
    client.responses = [envelope([transaction(1), transaction(2)])]
    assert cli.main(["net", "list", "--json"]) == 0
    out = capsys.readouterr().out
    assert lines(out) == [
        json.dumps(transaction(2), separators=(",", ":")),
        json.dumps(transaction(1), separators=(",", ":")),
    ]


def test_fields_keep_only_the_named_keys_in_order(client, capsys):
    client.responses = [envelope([transaction(1, statusCode=404)])]
    assert cli.main(["net", "list", "--json", "--fields", "url, statusCode,id"]) == 0
    assert lines(capsys.readouterr().out) == [
        '{"url":"https://api.example.com/v1/items/1","statusCode":404,"id":"tx_1"}'
    ]


def test_fields_print_json_lines_without_the_json_flag(client, capsys):
    client.responses = [envelope([transaction(1)])]
    cli.main(["net", "list", "--fields", "id"])
    assert capsys.readouterr().out == '{"id":"tx_1"}\n'


def test_fields_combine_with_filters_and_last(client, capsys):
    client.responses = [envelope([transaction(n, statusCode=500 if n % 2 else 200) for n in range(10)])]
    cli.main(["net", "list", "--errors", "--last", "2", "--fields", "id,statusCode"])
    out, err = capsys.readouterr()
    assert json_lines(out) == [{"id": "tx_9", "statusCode": 500}, {"id": "tx_7", "statusCode": 500}]
    assert "showing the newest 2 of 5" in err


def test_fields_reject_a_key_no_transaction_has(client, capsys):
    client.responses = [envelope([transaction(1)])]
    assert cli.main(["net", "list", "--fields", "id,status"]) == 2
    out, err = capsys.readouterr()
    assert out == ""
    assert "error: --fields: transactions have no status. They have: id,timestamp," in err
    assert "statusCode" in err


def test_fields_accept_a_key_that_only_the_server_knows(client, capsys):
    client.responses = [envelope([transaction(1, priority="high")])]
    assert cli.main(["net", "list", "--fields", "id,priority"]) == 0
    assert capsys.readouterr().out == '{"id":"tx_1","priority":"high"}\n'


def test_list_notes_on_stderr_when_capture_is_paused(client, capsys):
    client.responses = [envelope([transaction(1)], paused=True)]
    cli.main(["net", "list", "--json"])
    out, err = capsys.readouterr()
    assert json_lines(out) == [transaction(1)]
    assert "capture is paused" in err


# ── net get ────────────────────────────────────────────────────────────────────


def _detail(request_body=None, response_body=None):
    tx = transaction(1, requestHeaders={}, responseHeaders={"Content-Type": "application/json"})
    tx.update(requestBody=request_body, responseBody=response_body)
    return tx


def test_get_cuts_each_body_at_2_kb_and_names_the_command_for_the_rest(client, capsys):
    client.responses = [_detail(request_body="q" * 3000, response_body="r" * 20299)]
    assert cli.main(["net", "get", "tx_1"]) == 0
    tx = json.loads(capsys.readouterr().out)
    assert tx["requestBody"] == "q" * 2048 + "[... 952 more bytes: lustro net body tx_1 request]"
    assert tx["responseBody"] == "r" * 2048 + "[... 18251 more bytes: lustro net body tx_1 response]"


def test_get_keeps_a_body_that_fits(client, capsys):
    client.responses = [_detail(response_body="r" * 2048)]
    cli.main(["net", "get", "tx_1"])
    assert json.loads(capsys.readouterr().out)["responseBody"] == "r" * 2048


def test_get_cuts_at_a_character_boundary_and_counts_bytes(client, capsys):
    # "é" is 2 bytes in UTF-8, so byte 5 is inside the third one.
    client.responses = [_detail(response_body="ééééé")]
    cli.main(["net", "get", "tx_1", "--max-body", "5"])
    body = json.loads(capsys.readouterr().out)["responseBody"]
    assert body == "éé[... 6 more bytes: lustro net body tx_1 response]"


def test_get_max_body_zero_keeps_only_the_marker(client, capsys):
    client.responses = [_detail(response_body="abc")]
    cli.main(["net", "get", "tx_1", "--max-body", "0"])
    assert json.loads(capsys.readouterr().out)["responseBody"] == "[... 3 more bytes: lustro net body tx_1 response]"


def test_get_no_body_leaves_the_bodies_out(client, capsys):
    client.responses = [_detail(request_body="q", response_body="r" * 5000)]
    cli.main(["net", "get", "tx_1", "--no-body"])
    tx = json.loads(capsys.readouterr().out)
    assert "requestBody" not in tx and "responseBody" not in tx
    assert tx["responseHeaders"] == {"Content-Type": "application/json"}


def test_get_full_prints_the_bodies_whole(client, capsys):
    client.responses = [_detail(response_body="r" * 5000)]
    cli.main(["net", "get", "tx_1", "--full"])
    assert json.loads(capsys.readouterr().out)["responseBody"] == "r" * 5000


def test_get_body_options_exclude_each_other(client):
    with pytest.raises(SystemExit):
        cli.main(["net", "get", "tx_1", "--full", "--no-body"])


def test_json_is_compact_for_a_pipe_and_indented_for_a_terminal(client, capsys, monkeypatch):
    client.responses = [_detail(response_body="{}")] * 2
    cli.main(["net", "get", "tx_1"])
    piped = capsys.readouterr().out
    assert len(lines(piped)) == 1
    assert '"id":"tx_1"' in piped

    monkeypatch.setattr(cli.sys.stdout, "isatty", lambda: True)
    cli.main(["net", "get", "tx_1", "--json"])
    terminal = capsys.readouterr().out
    assert '\n  "id": "tx_1",\n' in terminal
    assert json.loads(terminal) == json.loads(piped)


# ── net poll ───────────────────────────────────────────────────────────────────


def test_poll_prints_an_in_flight_request_again_when_it_finishes(client, clock, capsys):
    client.responses = [
        envelope([transaction(1), in_flight(2)]),
        envelope([transaction(1), in_flight(2)], status="unchanged"),
        envelope([transaction(1), transaction(2, durationMs=300)], status="delta", cursor="c:2"),
        envelope([transaction(1), transaction(2, durationMs=300), transaction(3)], status="delta", cursor="c:3"),
    ]
    client.then = KeyboardInterrupt
    assert cli.main(["net", "poll"]) == 0
    assert lines(capsys.readouterr().out) == [
        "tx_1  14:22:01.000  GET     200   100ms  https://api.example.com/v1/items/1",
        "tx_2  14:22:02.000  GET     ...       -  https://api.example.com/v1/items/2",
        "tx_2  14:22:02.000  GET     200   300ms  https://api.example.com/v1/items/2 [update]",
        "tx_3  14:22:03.000  GET     200   100ms  https://api.example.com/v1/items/3",
    ]
    # The cursor goes back to the server.
    assert [call[2].get("cursor") for call in client.calls] == [None, "c:1", "c:1", "c:2", "c:3"]


def test_poll_prints_a_failed_request_again(client, clock, capsys):
    client.responses = [
        envelope([in_flight(1)]),
        envelope([transaction(1, statusCode=None, error="timeout")], status="delta", cursor="c:2"),
    ]
    client.then = KeyboardInterrupt
    cli.main(["net", "poll"])
    assert [row.split()[3] for row in lines(capsys.readouterr().out)] == ["...", "ERR"]


def test_poll_json_prints_the_finished_request_as_a_later_line(client, clock, capsys):
    client.responses = [
        envelope([in_flight(1)]),
        envelope([transaction(1)], status="delta", cursor="c:2"),
    ]
    client.then = KeyboardInterrupt
    cli.main(["net", "poll", "--json", "--fields", "id,statusCode"])
    assert json_lines(capsys.readouterr().out) == [
        {"id": "tx_1", "statusCode": None},
        {"id": "tx_1", "statusCode": 200},
    ]


def test_poll_filters_print_a_request_once_it_matches(client, clock, capsys):
    client.responses = [
        envelope([in_flight(1), in_flight(2)]),
        envelope([transaction(1, statusCode=500), transaction(2)], status="delta", cursor="c:2"),
    ]
    client.then = KeyboardInterrupt
    cli.main(["net", "poll", "--errors"])
    assert lines(capsys.readouterr().out) == [
        "tx_1  14:22:01.000  GET     500   100ms  https://api.example.com/v1/items/1"
    ]


def test_poll_once_prints_the_list_oldest_first(client, capsys):
    client.responses = [envelope([transaction(1), transaction(2), transaction(3)])]
    assert cli.main(["net", "poll", "--once"]) == 0
    assert _ids(capsys.readouterr().out) == ["tx_1", "tx_2", "tx_3"]


def test_poll_after_a_reset_prints_only_what_is_new_or_finished(client, clock, capsys):
    client.responses = [
        envelope([transaction(1), in_flight(2)]),
        envelope([transaction(2), transaction(3)], status="reset", cursor="c:9"),
    ]
    client.then = KeyboardInterrupt
    cli.main(["net", "poll"])
    assert _ids(capsys.readouterr().out) == ["tx_1", "tx_2", "tx_2", "tx_3"]


# ── net wait ───────────────────────────────────────────────────────────────────


def test_wait_ignores_requests_that_finished_before_it_started(client, clock, capsys):
    orders = "https://api.example.com/v1/orders"
    client.responses = [
        envelope([transaction(1, url=orders)]),
        envelope([transaction(1, url=orders)], status="unchanged"),
        envelope([transaction(1, url=orders), transaction(2, url=orders)], status="delta", cursor="c:2"),
    ]
    assert cli.main(["net", "wait", "--url", "/orders"]) == 0
    assert _ids(capsys.readouterr().out) == ["tx_2"]


def test_wait_matches_a_request_that_was_in_flight_when_it_started(client, clock, capsys):
    client.responses = [
        envelope([in_flight(1)]),
        envelope([transaction(1, statusCode=500)], status="delta", cursor="c:2"),
    ]
    assert cli.main(["net", "wait", "--errors", "--json"]) == 0
    assert json_lines(capsys.readouterr().out) == [transaction(1, statusCode=500)]


def test_wait_skips_new_requests_that_do_not_match(client, clock, capsys):
    client.responses = [
        envelope([]),
        envelope([transaction(1)], status="delta", cursor="c:2"),
        envelope([transaction(1), transaction(2, method="POST")], status="delta", cursor="c:3"),
    ]
    assert cli.main(["net", "wait", "--method", "POST", "--fields", "id,method"]) == 0
    assert json_lines(capsys.readouterr().out) == [{"id": "tx_2", "method": "POST"}]


def test_wait_prints_the_request_that_finished_first(client, clock, capsys):
    client.responses = [
        envelope([]),
        envelope([transaction(1, completedAt=500), transaction(2, completedAt=400)], status="delta", cursor="c:2"),
    ]
    cli.main(["net", "wait"])
    assert _ids(capsys.readouterr().out) == ["tx_2"]


def test_wait_exits_1_at_the_timeout(client, clock, capsys):
    client.responses = [envelope([])]
    assert cli.main(["net", "wait", "--timeout", "3"]) == 1
    out, err = capsys.readouterr()
    assert out == ""
    assert err == "error: no matching request finished within 3 s\n"
    assert sum(clock.sleeps) == pytest.approx(3)
    assert clock.sleeps[0] == 0.5


def test_wait_timeout_names_a_match_that_finished_before_it_started(client, clock, capsys):
    client.responses = [envelope([transaction(1, statusCode=500), transaction(2, statusCode=502)])]
    assert cli.main(["net", "wait", "--errors", "--timeout", "1"]) == 1
    err = capsys.readouterr().err
    assert "wait ignores requests that finished before it started, and 2 of them match (newest: tx_2)" in err
    assert "give the action after --" in err


def test_wait_timeout_after_an_action_has_no_hint_about_the_action(client, clock, capsys):
    client.responses = [envelope([transaction(1, statusCode=500)])]
    assert cli.main(["net", "wait", "--errors", "--timeout", "1", "--", sys.executable, "-c", "pass"]) == 1
    assert capsys.readouterr().err == "error: no matching request finished within 1 s\n"


def test_wait_keeps_polling_through_a_connection_error(client, clock, capsys):
    from lustro_cli.client import LustroError

    def fail():
        raise LustroError("connection_failed", "closed")

    client.responses = [envelope([]), fail, fail, envelope([transaction(1)], status="delta", cursor="c:2")]
    assert cli.main(["net", "wait"]) == 0
    out, err = capsys.readouterr()
    assert _ids(out) == ["tx_1"]
    assert err.count("poll error: connection_failed: closed") == 1


def test_wait_runs_the_action_after_the_first_poll(client, clock, capsys, tmp_path):
    marker = tmp_path / "tapped"

    def after_the_action():
        # The request exists only once the action has run.
        if marker.exists():
            return envelope([transaction(1), transaction(2)], status="delta", cursor="c:2")
        return envelope([transaction(1)], status="unchanged")

    client.responses = [envelope([transaction(1)]), after_the_action]
    action = [sys.executable, "-c", "import pathlib, sys; pathlib.Path(sys.argv[1]).touch()", str(marker)]
    assert cli.main(["net", "wait", "--"] + action) == 0
    assert _ids(capsys.readouterr().out) == ["tx_2"]


def test_wait_reports_an_action_that_fails(client, clock, capsys):
    client.responses = [envelope([])]
    action = [sys.executable, "-c", "import sys; print('no such button'); sys.exit(3)"]
    assert cli.main(["net", "wait", "--"] + action) == 2
    out, err = capsys.readouterr()
    assert out == ""
    assert "no such button" in err
    assert "exited with status 3" in err
    # It stops without polling again.
    assert len(client.calls) == 1


def test_wait_reports_an_action_it_cannot_run(client, clock, capsys, tmp_path):
    client.responses = [envelope([])]
    assert cli.main(["net", "wait", "--", str(tmp_path / "missing-tool")]) == 2
    assert "could not run" in capsys.readouterr().err


def test_wait_with_a_bad_fields_key_fails_before_the_action(client, clock, capsys, tmp_path):
    marker = tmp_path / "tapped"
    client.responses = [envelope([transaction(1)])]
    action = [sys.executable, "-c", "import pathlib, sys; pathlib.Path(sys.argv[1]).touch()", str(marker)]
    assert cli.main(["net", "wait", "--fields", "nope", "--"] + action) == 2
    assert not marker.exists()


@pytest.mark.skipif(os.name == "nt", reason="uses a POSIX shell")
def test_wait_passes_the_action_arguments_unparsed(client, clock, tmp_path):
    marker = tmp_path / "args"
    client.responses = [envelope([]), envelope([transaction(1)], status="delta", cursor="c:2")]
    action = ["sh", "-c", 'printf "%s|" "$@" > "$0"', str(marker), "--json", "-n", "x y"]
    assert cli.main(["net", "wait", "--timeout", "5", "--"] + action) == 0
    assert marker.read_text() == "--json|-n|x y|"

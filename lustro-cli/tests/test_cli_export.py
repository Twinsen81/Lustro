"""Tests for ``lustro net export``: the HAR export route, id batches, and the file it writes."""

from __future__ import annotations

import json

import pytest

from lustro_cli import cli, wire
from lustro_cli.client import LustroClient
from lustro_cli.discovery import Endpoint

EXPORT = "/api/v1/network/transactions/_/export"
TRANSACTIONS = "/api/v1/network/transactions"


def full_id(n):
    return "3f2a9c1d-5b7e-4c8a-9d6f-{:012d}".format(n)


def entry(tx_id, started="2026-09-28T14:22:07.512Z"):
    return {"startedDateTime": started, "time": 1, "_lustro": {"id": tx_id}}


def started_at(n):
    return "2026-09-28T14:{:02d}:{:02d}.000Z".format(22 + n // 60, n % 60)


def har(*entries):
    return {"log": {"version": "1.2", "creator": {"name": "Lustro", "version": "0.1.0"}, "entries": list(entries)}}


class _ExportServer(LustroClient):
    """Answers the list route with ``listed``, and the export route with an entry
    for each requested id that the app still has, as the server does."""

    def __init__(self, listed=()):
        super().__init__("http://127.0.0.1:8080", "tok")
        self.calls = []
        self.listed = list(listed)
        self.known = {tx_id: entry(tx_id, started_at(i)) for i, tx_id in enumerate(listed)}
        self.everything = har(*self.known.values())

    def request(self, method, path, *, params=None, json_body=None):
        self.calls.append((method, path, dict(params or {})))
        if path == TRANSACTIONS:
            return {"cursor": "c:1", "status": "reset", "items": [{"id": tx_id} for tx_id in self.listed]}
        assert path == EXPORT
        if "ids" not in (params or {}):
            return json.loads(json.dumps(self.everything))
        ids = params["ids"].split(",")
        found = sorted((self.known[i] for i in ids if i in self.known), key=lambda e: e["startedDateTime"])
        return har(*found)


@pytest.fixture
def server(monkeypatch):
    holder = {}

    def build(args):
        return holder["client"]

    def install(listed=()):
        holder["client"] = _ExportServer(listed)
        return holder["client"]

    monkeypatch.setattr(cli, "_build_client", build)
    monkeypatch.setattr(cli, "_build_endpoint", lambda args: Endpoint("127.0.0.1", 8080, "tok"))
    return install


def exports(client):
    return [call for call in client.calls if call[1] == EXPORT]


def test_export_without_ids_saves_every_transaction(server, tmp_path, capsys):
    client = server([full_id(1), full_id(2)])
    out = tmp_path / "all.har"
    assert cli.main(["net", "export", "--har", str(out)]) == 0
    assert client.calls == [("GET", EXPORT, {"format": "har"})]
    assert json.loads(out.read_text("utf-8")) == client.everything
    assert "saved 2 transactions" in capsys.readouterr().out


def test_the_file_is_indented_as_devtools_write_one(server, tmp_path):
    server([full_id(1)])
    out = tmp_path / "all.har"
    cli.main(["net", "export", "--har", str(out)])
    text = out.read_text("utf-8")
    assert text.startswith('{\n  "log": {\n    "version": "1.2"')
    assert text.endswith("}\n")


def test_full_ids_need_no_list_request(server, tmp_path):
    client = server([full_id(1), full_id(2), full_id(3)])
    cli.main(["net", "export", "--har", str(tmp_path / "x.har"), "--ids", full_id(3), full_id(1)])
    assert client.calls == [("GET", EXPORT, {"format": "har", "ids": full_id(3) + "," + full_id(1)})]


def test_ids_take_commas_and_starts_and_one_list_request_resolves_them(server, tmp_path):
    client = server([full_id(1), full_id(2), "tx_other"])
    out = tmp_path / "x.har"
    assert cli.main(["net", "export", "--har", str(out), "--ids", full_id(1) + ",tx_o", full_id(2)]) == 0
    assert [call[1] for call in client.calls] == [TRANSACTIONS, EXPORT]
    assert exports(client)[0][2]["ids"] == ",".join([full_id(1), "tx_other", full_id(2)])


def test_an_ambiguous_start_exits_2(server, tmp_path, capsys):
    server([full_id(1), full_id(2)])
    assert cli.main(["net", "export", "--har", str(tmp_path / "x.har"), "--ids", "3f2a"]) == 2
    assert "2 transaction ids start with 3f2a" in capsys.readouterr().err


def test_many_ids_go_in_batches_and_join_oldest_first(server, tmp_path):
    ids = [full_id(n) for n in range(120)]
    client = server(ids)
    out = tmp_path / "many.har"
    # Newest first, as a list prints them.
    assert cli.main(["net", "export", "--har", str(out), "--ids", ",".join(reversed(ids))]) == 0
    batches = [call[2]["ids"].split(",") for call in exports(client)]
    assert [len(batch) for batch in batches] == [50, 50, 20]
    entries = json.loads(out.read_text("utf-8"))["log"]["entries"]
    assert [e["_lustro"]["id"] for e in entries] == ids


def test_ids_the_app_no_longer_has_are_left_out_with_a_warning(server, tmp_path, capsys):
    server([full_id(1)])
    out = tmp_path / "x.har"
    assert cli.main(["net", "export", "--har", str(out), "--ids", full_id(1), full_id(9)]) == 0
    err = capsys.readouterr().err
    assert "no longer has 1 of the transactions" in err
    assert full_id(9) in err
    assert len(json.loads(out.read_text("utf-8"))["log"]["entries"]) == 1


def test_a_dash_writes_the_har_to_stdout_and_nothing_else(server, capsysbinary):
    client = server([full_id(1)])
    assert cli.main(["net", "export", "--har", "-"]) == 0
    assert json.loads(capsysbinary.readouterr().out.decode("utf-8")) == client.everything


def test_json_prints_a_summary(server, tmp_path, capsys):
    server([full_id(1), full_id(2)])
    out = tmp_path / "x.har"
    cli.main(["--json", "net", "export", "--har", str(out), "--ids", full_id(2), full_id(7)])
    summary = json.loads(capsys.readouterr().out)
    assert summary == {"path": str(out), "entries": 1, "bytes": out.stat().st_size, "missing": [full_id(7)]}


def test_an_unwritable_file_exits_2(server, tmp_path, capsys):
    server([full_id(1)])
    out = tmp_path / "missing" / "x.har"
    assert cli.main(["net", "export", "--har", str(out)]) == 2
    assert "could not write HAR file" in capsys.readouterr().err


def test_a_response_that_is_not_har_exits_1(server, tmp_path, capsys):
    client = server()
    client.everything = {"status": "ok"}
    assert cli.main(["net", "export", "--har", str(tmp_path / "x.har")]) == 1
    assert "not a HAR document" in capsys.readouterr().err


def test_har_is_required(server, capsys):
    server()
    with pytest.raises(SystemExit):
        cli.main(["net", "export"])


def test_the_golden_export_round_trips_unchanged(server, tmp_path):
    client = server()
    client.everything = wire.load_golden("export-har.json")
    out = tmp_path / "golden.har"
    cli.main(["net", "export", "--har", str(out)])
    assert json.loads(out.read_text("utf-8")) == wire.load_golden("export-har.json")

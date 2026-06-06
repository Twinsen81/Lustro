"""Tests that CLI subcommands map to the correct wire-protocol routes."""

from __future__ import annotations

import pytest

from lustro_cli import cli
from lustro_cli.client import LustroClient
from lustro_cli.discovery import Endpoint


class _RecordingClient(LustroClient):
    """Captures requests instead of issuing them; returns a canned body."""

    def __init__(self):
        super().__init__("http://127.0.0.1:8080", "tok")
        self.calls = []

    def request(self, method, path, *, params=None, json_body=None):
        self.calls.append((method, path, dict(params or {}), json_body))
        return {"status": "ok"}


@pytest.fixture
def rec(monkeypatch):
    client = _RecordingClient()
    monkeypatch.setattr(cli, "_build_client", lambda args: client)
    monkeypatch.setattr(
        cli, "_build_endpoint", lambda args: Endpoint("127.0.0.1", 8080, "tok")
    )
    return client


def run(argv):
    return cli.main(argv)


def test_meta_route(rec):
    assert run(["meta"]) == 0
    assert rec.calls[-1][:2] == ("GET", "/api/v1/_meta")


def test_schema_shared_route(rec):
    run(["schema"])
    assert rec.calls[-1][:2] == ("GET", "/api/v1/_schema")


def test_schema_tab_route(rec):
    run(["schema", "network"])
    assert rec.calls[-1][:2] == ("GET", "/api/v1/network/_schema")


def test_net_list_route(rec):
    run(["net", "list", "--search", "orders"])
    method, path, params, _ = rec.calls[-1]
    assert (method, path) == ("GET", "/api/v1/network/transactions")
    assert params["search"] == "orders"


def test_net_poll_once_route(rec):
    run(["net", "poll", "--once"])
    method, path, _, _ = rec.calls[-1]
    assert (method, path) == ("GET", "/api/v1/network/transactions")


def test_net_get_route(rec):
    run(["net", "get", "tx_1"])
    assert rec.calls[-1][:2] == ("GET", "/api/v1/network/transactions/tx_1")


def test_net_clear_route(rec):
    run(["net", "clear"])
    assert rec.calls[-1][:2] == ("POST", "/api/v1/network/clear")


def test_net_pause_route(rec):
    run(["net", "pause"])
    assert rec.calls[-1][:2] == ("POST", "/api/v1/network/pause")


def test_net_overwrite_route(rec):
    run(["net", "overwrite"])
    assert rec.calls[-1][:2] == ("POST", "/api/v1/network/overwrite-mode")


def test_net_throttle_route(rec):
    run(["net", "throttle", "250"])
    method, path, _, body = rec.calls[-1]
    assert (method, path) == ("POST", "/api/v1/network/throttle")
    assert body == {"delayMs": 250}


def test_mock_list_route(rec):
    run(["mock", "list"])
    assert rec.calls[-1][:2] == ("GET", "/api/v1/network/rules")


def test_mock_add_route(rec):
    run(
        [
            "mock",
            "add",
            "--url-pattern",
            "/v1/orders",
            "--id",
            "orders-500",
            "--method",
            "GET",
            "--status",
            "500",
            "--body",
            "{}",
            "--header",
            "Content-Type: application/json",
        ]
    )
    method, path, _, body = rec.calls[-1]
    assert (method, path) == ("POST", "/api/v1/network/rules")
    assert body["id"] == "orders-500"
    assert body["urlPattern"] == "/v1/orders"
    assert body["method"] == "GET"
    assert body["statusCode"] == 500
    assert body["responseHeaders"] == {"Content-Type": "application/json"}


def test_mock_delete_route(rec):
    run(["mock", "delete", "r1"])
    method, path, _, body = rec.calls[-1]
    assert (method, path) == ("POST", "/api/v1/network/rules/delete")
    assert body == {"id": "r1"}


def test_mock_toggle_route(rec):
    run(["mock", "toggle", "r1"])
    method, path, _, body = rec.calls[-1]
    assert (method, path) == ("POST", "/api/v1/network/rules/toggle")
    assert body == {"id": "r1"}


def test_mock_sync_route(rec, tmp_path):
    f = tmp_path / "rules.json"
    f.write_text('[{"urlPattern":"/a","statusCode":200}]', encoding="utf-8")
    run(["mock", "sync", str(f)])
    method, path, _, body = rec.calls[-1]
    assert (method, path) == ("POST", "/api/v1/network/rules/_/sync")
    assert body == [{"urlPattern": "/a", "statusCode": 200}]


def test_send_route(rec):
    run(
        [
            "send",
            "--url",
            "https://api.example.com/v1/orders",
            "--method",
            "POST",
            "--header",
            "X-Test: 1",
            "--body",
            "hi",
        ]
    )
    method, path, _, body = rec.calls[-1]
    assert (method, path) == ("POST", "/api/v1/network/send")
    assert body["url"] == "https://api.example.com/v1/orders"
    assert body["method"] == "POST"
    assert body["headers"] == {"X-Test": "1"}
    assert body["body"] == "hi"


def test_open_prints_bootstrap_url(rec, capsys):
    run(["open", "--no-forward", "--print-only"])
    out = capsys.readouterr().out
    assert "#lustro_token=tok" in out
    assert "8080" in out


def test_all_openapi_paths_are_covered():
    """Every route in the bundled OpenAPI doc is reachable from some CLI command."""
    from lustro_cli import wire

    openapi = wire.load_openapi()
    declared = set(openapi["paths"].keys())
    # Routes the CLI invokes (network ops). transactions/{id} -> concrete id form.
    covered = {
        "/api/v1/network/transactions",
        "/api/v1/network/transactions/{id}",
        "/api/v1/network/clear",
        "/api/v1/network/rules",
        "/api/v1/network/rules/_/sync",
        "/api/v1/network/rules/delete",
        "/api/v1/network/rules/toggle",
        "/api/v1/network/pause",
        "/api/v1/network/overwrite-mode",
        "/api/v1/network/throttle",
        "/api/v1/network/send",
    }
    assert declared == covered, declared.symmetric_difference(covered)

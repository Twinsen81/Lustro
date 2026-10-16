"""Regression tests for single-shot polling and URL-fragment encoding.

C1: ``net poll --once`` against an unreachable/erroring server must make a single
attempt and exit (non-zero), not loop forever — connection failures surface as
``LustroError`` and were previously swallowed by ``sleep; continue``, bypassing
the ``--once`` break.

C4: ``open`` must percent-encode the token in the URL fragment so a token with
``#``/``&``/``%``/``=`` cannot corrupt it.
"""

from __future__ import annotations

import threading

import pytest

from lustro_cli import cli
from lustro_cli.client import LustroClient, LustroError
from lustro_cli.discovery import Endpoint


class _RaisingClient(LustroClient):
    """A client whose every request raises a transport-level LustroError."""

    def __init__(self):
        super().__init__("http://127.0.0.1:8080", "tok")
        self.attempts = 0

    def request(self, method, path, *, params=None, json_body=None):
        self.attempts += 1
        raise LustroError(
            "connection_failed",
            "could not reach http://127.0.0.1:8080{}".format(path),
        )


@pytest.fixture
def raising_client(monkeypatch):
    client = _RaisingClient()
    monkeypatch.setattr(cli, "_build_client", lambda args: client)
    monkeypatch.setattr(
        cli, "_build_endpoint", lambda args: Endpoint("127.0.0.1", 8080, "tok")
    )
    return client


def _run_with_watchdog(argv, timeout=5.0):
    """Run cli.main(argv) on a worker thread; fail loudly if it does not return.

    Guards the regression: a hang would otherwise stall the suite indefinitely.
    """
    result = {}

    def target():
        result["rc"] = cli.main(argv)

    t = threading.Thread(target=target, daemon=True)
    t.start()
    t.join(timeout)
    assert not t.is_alive(), "net poll --once hung on the error path (C1 regression)"
    return result["rc"]


def test_poll_once_exits_nonzero_on_error(raising_client, capsys):
    rc = _run_with_watchdog(["net", "poll", "--once"])
    assert rc != 0
    # Exactly one attempt: --once is honored on the error path, no retry loop.
    assert raising_client.attempts == 1
    err = capsys.readouterr().err
    assert "poll error" in err
    assert "connection_failed" in err


def test_open_percent_encodes_token_fragment(monkeypatch, capsys):
    nasty_token = "a#b&c%d=e/f"
    monkeypatch.setattr(
        cli, "_build_endpoint", lambda args: Endpoint("127.0.0.1", 8080, nasty_token)
    )
    rc = cli.main(["open", "--no-forward", "--print-only"])
    assert rc == 0
    out = capsys.readouterr().out.strip()
    # The raw special characters must not leak into the fragment...
    assert "#lustro_token=" in out
    fragment = out.split("#lustro_token=", 1)[1]
    assert "#" not in fragment
    assert "&" not in fragment
    assert "=" not in fragment
    assert "/" not in fragment
    # ...they must be percent-encoded instead.
    import urllib.parse

    assert fragment == urllib.parse.quote(nasty_token, safe="")
    assert urllib.parse.unquote(fragment) == nasty_token


class _SearchRejectingClient(LustroClient):
    """A client whose every request gets the app's 400 for a search that can't run."""

    def __init__(self):
        super().__init__("http://127.0.0.1:8080", "tok")
        self.attempts = 0

    def request(self, method, path, *, params=None, json_body=None):
        self.attempts += 1
        raise LustroError(
            "bad_request",
            "The regex /feat(/ is not valid: Unclosed group",
            status=400,
            field="search",
        )


@pytest.mark.parametrize(
    "argv",
    [
        ["net", "poll", "--search", "/feat(/"],
        ["net", "wait", "--search", "/feat(/", "--timeout", "1"],
        ["net", "list", "--search", "/feat(/"],
    ],
)
def test_a_rejected_search_exits_2_without_polling_again(monkeypatch, capsys, argv):
    client = _SearchRejectingClient()
    monkeypatch.setattr(cli, "_build_client", lambda args: client)
    rc = _run_with_watchdog(argv)
    assert rc == 2
    assert client.attempts == 1
    assert "The regex /feat(/ is not valid: Unclosed group (field: search)" in capsys.readouterr().err

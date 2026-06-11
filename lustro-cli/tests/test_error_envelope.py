"""Tests for error-envelope parsing into LustroError."""

from __future__ import annotations

import io
import json

import urllib.error

from lustro_cli import wire
from lustro_cli.client import LustroError, _error_from_http


def test_from_envelope_full():
    env = wire.load_golden("error-envelope.json")
    err = LustroError.from_envelope(env, status=404)
    assert err.error == "not_found"
    assert err.message.startswith("No transaction")
    assert err.status == 404
    assert err.code == "transaction_not_found"
    assert err.field == "id"
    assert err.hint


def test_from_envelope_minimal():
    err = LustroError.from_envelope({"error": "bad_request", "message": "boom"})
    assert err.error == "bad_request"
    assert err.message == "boom"
    assert err.code is None
    assert err.field is None
    assert err.hint is None


def test_str_includes_status_field_and_hint():
    err = LustroError(
        "not_found",
        "missing",
        status=404,
        field="id",
        hint="check ids",
    )
    text = str(err)
    assert "404" in text
    assert "not_found" in text
    assert "missing" in text
    assert "id" in text
    assert "check ids" in text


def _http_error(status, body):
    raw = body.encode("utf-8") if isinstance(body, str) else body
    return urllib.error.HTTPError(
        url="http://localhost:8080/api/v1/network/transactions/x",
        code=status,
        msg="error",
        hdrs=None,
        fp=io.BytesIO(raw),
    )


def test_error_from_http_parses_envelope():
    body = json.dumps(
        {"error": "unauthorized", "message": "missing token", "hint": "send bearer"}
    )
    err = _error_from_http(_http_error(401, body))
    assert err.error == "unauthorized"
    assert err.status == 401
    assert err.message == "missing token"
    assert err.hint == "send bearer"


def test_error_from_http_synthesizes_from_status_when_not_json():
    err = _error_from_http(_http_error(503, "Service Unavailable"))
    assert err.error == "unavailable"
    assert err.status == 503


def test_error_from_http_unknown_status():
    err = _error_from_http(_http_error(418, ""))
    assert err.error == "http_error"
    assert err.status == 418


def test_error_from_http_maps_known_statuses():
    expected = {
        400: "bad_request",
        401: "unauthorized",
        403: "forbidden",
        404: "not_found",
        405: "method_not_allowed",
        413: "payload_too_large",
        500: "internal_error",
        503: "unavailable",
        504: "timeout",
    }
    for status, error_type in expected.items():
        err = _error_from_http(_http_error(status, ""))
        assert err.error == error_type, status

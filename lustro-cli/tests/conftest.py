"""Shared test fixtures for the lustro CLI contract tests."""

from __future__ import annotations

import json

import pytest

from lustro_cli import cli, discovery, wire

# Map each golden fixture to the JSON Schema / OpenAPI component it must satisfy.
# (component name resolves against network.openapi.json #/components/schemas/...)
GOLDEN_SCHEMA_FILES = {
    "meta.json": ("schema", "meta.schema.json"),
    "error-envelope.json": ("schema", "error-envelope.schema.json"),
    "cursor-reset.json": ("schema", "cursor-envelope.schema.json"),
    "cursor-delta.json": ("schema", "cursor-envelope.schema.json"),
    "cursor-unchanged.json": ("schema", "cursor-envelope.schema.json"),
    "websockets-reset.json": ("schema", "cursor-envelope.schema.json"),
    "stream-reset.json": ("schema", "stream-envelope.schema.json"),
    "stream-delta.json": ("schema", "stream-envelope.schema.json"),
    "stream-unchanged.json": ("schema", "stream-envelope.schema.json"),
    "logs-reset.json": ("schema", "stream-envelope.schema.json"),
    "logs-delta.json": ("schema", "stream-envelope.schema.json"),
    "logs-unchanged.json": ("schema", "stream-envelope.schema.json"),
    "prefs-reset.json": ("schema", "cursor-envelope.schema.json"),
    "prefs-delta.json": ("schema", "cursor-envelope.schema.json"),
    "prefs-unchanged.json": ("schema", "cursor-envelope.schema.json"),
    "sqlite-rows.json": ("schema", "pagination.schema.json"),
}

# Golden fixtures validated against an OpenAPI component schema.
GOLDEN_OPENAPI_COMPONENTS = {
    "cursor-reset.json": "TransactionCursorEnvelope",
    "cursor-delta.json": "TransactionCursorEnvelope",
    "cursor-unchanged.json": "TransactionCursorEnvelope",
    "transaction.json": "Transaction",
    "transaction-image.json": "Transaction",
    "send-result.json": None,  # validated against the inline sendRequest 200 schema
    "senders-list.json": None,  # validated against the inline listSenders 200 schema
    "rules-list.json": None,  # validated against the inline listMockRules 200 schema
    "error-envelope.json": "ErrorEnvelope",
    "export-har.json": "HarDocument",
    "export-har-websocket.json": "HarDocument",
    "websockets-reset.json": "WebSocketCursorEnvelope",
    "websocket.json": "WebSocketConnection",
    "stream-reset.json": "WebSocketEventStreamEnvelope",
    "stream-delta.json": "WebSocketEventStreamEnvelope",
    "stream-unchanged.json": "WebSocketEventStreamEnvelope",
}


@pytest.fixture
def openapi():
    return wire.load_openapi()


@pytest.fixture
def golden():
    def _load(name):
        return wire.load_golden(name)

    return _load


@pytest.fixture(autouse=True)
def _no_adb(monkeypatch):
    """Discovery and the commands' forward run adb, which a test must not reach:
    a forward would stay on the computer's adb server. A test that needs their
    output patches ``discovery._run`` or ``cli._adb_forward`` itself. Without
    adb, a command connects to the app's port on the computer."""
    monkeypatch.setattr(discovery, "_run", lambda cmd, timeout=10.0: None)

    def no_adb(local_port, device_port, device):
        raise FileNotFoundError("adb")

    monkeypatch.setattr(cli, "_adb_forward", no_adb)

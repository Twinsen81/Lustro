"""Validate every golden fixture against its JSON Schema / OpenAPI component."""

from __future__ import annotations

import pytest
from jsonschema import Draft202012Validator
from jsonschema.validators import validator_for

from lustro_cli import wire

# ── shared JSON Schema validation ─────────────────────────────────────────────

SHARED_SCHEMA_CASES = [
    ("meta.json", "meta.schema.json"),
    ("error-envelope.json", "error-envelope.schema.json"),
    ("cursor-reset.json", "cursor-envelope.schema.json"),
    ("cursor-delta.json", "cursor-envelope.schema.json"),
    ("cursor-unchanged.json", "cursor-envelope.schema.json"),
]


@pytest.mark.parametrize("fixture, schema_file", SHARED_SCHEMA_CASES)
def test_fixture_matches_shared_schema(fixture, schema_file):
    schema = wire.load_schema(schema_file)
    instance = wire.load_golden(fixture)
    cls = validator_for(schema)
    cls.check_schema(schema)
    cls(schema).validate(instance)


def test_all_shared_schemas_are_valid_draft202012():
    for name in (
        "error-envelope.schema.json",
        "cursor-envelope.schema.json",
        "pagination.schema.json",
        "meta.schema.json",
    ):
        Draft202012Validator.check_schema(wire.load_schema(name))


# ── OpenAPI component validation ──────────────────────────────────────────────


def _component_validator(openapi, component_name):
    """Build a validator for a named component, resolving local $refs against the
    OpenAPI document. OpenAPI 3.1 schemas are JSON Schema 2020-12 compatible."""
    schema = dict(openapi["components"]["schemas"][component_name])
    schema["components"] = openapi["components"]
    # Use 2020-12 (OpenAPI 3.1 aligns with it).
    return Draft202012Validator(schema)


OPENAPI_COMPONENT_CASES = [
    ("cursor-reset.json", "TransactionCursorEnvelope"),
    ("cursor-delta.json", "TransactionCursorEnvelope"),
    ("cursor-unchanged.json", "TransactionCursorEnvelope"),
    ("transaction.json", "Transaction"),
    ("error-envelope.json", "ErrorEnvelope"),
]


@pytest.mark.parametrize("fixture, component", OPENAPI_COMPONENT_CASES)
def test_fixture_matches_openapi_component(fixture, component):
    openapi = wire.load_openapi()
    validator = _component_validator(openapi, component)
    validator.validate(wire.load_golden(fixture))


def _inline_response_schema(openapi, path, method, status):
    op = openapi["paths"][path][method]
    schema = op["responses"][status]["content"]["application/json"]["schema"]
    schema = dict(schema)
    schema["components"] = openapi["components"]
    return Draft202012Validator(schema)


def test_send_result_matches_inline_schema():
    openapi = wire.load_openapi()
    validator = _inline_response_schema(
        openapi, "/api/v1/network/send", "post", "200"
    )
    validator.validate(wire.load_golden("send-result.json"))


def test_rules_list_matches_inline_schema():
    openapi = wire.load_openapi()
    validator = _inline_response_schema(
        openapi, "/api/v1/network/rules", "get", "200"
    )
    validator.validate(wire.load_golden("rules-list.json"))


def test_every_golden_transaction_matches_transaction_component():
    """The transactions inside the cursor fixtures must each be valid Transactions."""
    openapi = wire.load_openapi()
    validator = _component_validator(openapi, "Transaction")
    for fixture in ("cursor-reset.json", "cursor-delta.json"):
        data = wire.load_golden(fixture)
        for tx in data["items"]:
            validator.validate(tx)


def test_every_mock_rule_matches_mockrule_component():
    openapi = wire.load_openapi()
    validator = _component_validator(openapi, "MockRule")
    for rule in wire.load_golden("rules-list.json")["items"]:
        validator.validate(rule)

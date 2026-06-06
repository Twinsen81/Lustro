"""Cross-check the THREE bundled error-envelope representations for drift.

The error envelope is defined in three places that must stay in lock-step:

1. the standalone JSON Schema ``error-envelope.schema.json``,
2. the served bundle ``_schema.json`` under ``$defs.errorEnvelope``, and
3. the OpenAPI ``ErrorEnvelope`` component in ``network.openapi.json``.

All three are vendored into ``lustro_cli/wire/`` (and shipped in the wheel), so
these assertions guard the copies that the installed package actually carries
against future divergence.
"""

from __future__ import annotations

from lustro_cli import wire

# The fixed, machine-readable error identifiers the envelope's ``error`` field
# may take (mirrors docs/AGENTS.md "Failure modes").
EXPECTED_ERROR_ENUM = {
    "bad_request",
    "unauthorized",
    "forbidden",
    "not_found",
    "method_not_allowed",
    "payload_too_large",
    "internal_error",
    "unavailable",
    "timeout",
}


def _standalone_envelope():
    return wire.load_schema("error-envelope.schema.json")


def _served_envelope():
    return wire.load_schema("_schema.json")["$defs"]["errorEnvelope"]


def _openapi_envelope():
    return wire.load_openapi()["components"]["schemas"]["ErrorEnvelope"]


def _all_envelopes():
    return {
        "error-envelope.schema.json": _standalone_envelope(),
        "_schema.json#/$defs/errorEnvelope": _served_envelope(),
        "network.openapi.json#/components/schemas/ErrorEnvelope": _openapi_envelope(),
    }


def test_all_three_envelopes_are_vendored_and_loadable():
    envelopes = _all_envelopes()
    assert len(envelopes) == 3
    for name, schema in envelopes.items():
        assert isinstance(schema, dict) and schema, name


def test_required_keys_agree():
    required = {name: set(schema.get("required", [])) for name, schema in _all_envelopes().items()}
    distinct = {frozenset(v) for v in required.values()}
    assert len(distinct) == 1, required
    # And it's the documented minimal pair.
    assert next(iter(distinct)) == frozenset({"error", "message"})


def test_property_names_agree():
    props = {name: set(schema.get("properties", {})) for name, schema in _all_envelopes().items()}
    distinct = {frozenset(v) for v in props.values()}
    assert len(distinct) == 1, props
    assert next(iter(distinct)) == frozenset({"error", "message", "code", "field", "hint"})


def test_additional_properties_is_false_everywhere():
    for name, schema in _all_envelopes().items():
        assert schema.get("additionalProperties") is False, name


def test_error_enum_value_set_agrees():
    for name, schema in _all_envelopes().items():
        enum = schema.get("properties", {}).get("error", {}).get("enum")
        assert enum is not None, "{}: error property has no enum".format(name)
        assert set(enum) == EXPECTED_ERROR_ENUM, name

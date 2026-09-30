#!/usr/bin/env python3
"""Checks the output of one ``lustro`` command against the wire schemas the CLI ships.

``cli-e2e.sh`` pipes each command's stdout into one check::

    cli_e2e_check.py CHECK [RULES_FILE] < output

A check exits 0 when the output matches and 1, with the reason, when it does not.
The schemas come from the installed ``lustro_cli`` package, so the checks test
what the CLI ships. RULES_FILE is the mock rule file that the script synced; the
checks after the sync compare the output with it.
"""

from __future__ import annotations

import json
import re
import sys
from typing import Any, Callable, Dict, List

from jsonschema import Draft202012Validator
from jsonschema.exceptions import ValidationError

from lustro_cli import wire

OPENAPI = wire.load_openapi()


class CheckFailed(Exception):
    pass


def expect(condition: bool, message: str) -> None:
    if not condition:
        raise CheckFailed(message)


def _resolving(schema: Dict[str, Any]) -> Draft202012Validator:
    # OpenAPI 3.1 schemas are JSON Schema 2020-12. Their $refs point into the
    # document's components, so each schema carries a copy of them.
    return Draft202012Validator(dict(schema, components=OPENAPI["components"]))


def component(name: str) -> Draft202012Validator:
    return _resolving(OPENAPI["components"]["schemas"][name])


def response(path: str, method: str) -> Draft202012Validator:
    """Validates the 200 JSON response of the operation at ``path``."""
    ok = OPENAPI["paths"][path][method]["responses"]["200"]
    return _resolving(ok["content"]["application/json"]["schema"])


def shared(name: str) -> Draft202012Validator:
    return Draft202012Validator(wire.load_schema(name))


def check_open(text: str, rules: List[dict]) -> None:
    url = text.strip()
    expect(
        re.fullmatch(r"http://localhost:\d+/#lustro_token=\S+", url) is not None,
        "not a console URL with a token: {!r}".format(url),
    )


def check_meta(text: str, rules: List[dict]) -> None:
    meta = json.loads(text)
    shared("meta.schema.json").validate(meta)
    expect(any(tab["id"] == "network" for tab in meta["tabs"]), "the network tab is not listed")
    shipped = OPENAPI["info"]["version"]
    expect(
        meta["protocolVersion"] == shipped,
        "the app speaks protocol {} and the CLI ships {}".format(meta["protocolVersion"], shipped),
    )


def check_schema_network(text: str, rules: List[dict]) -> None:
    expect(json.loads(text) == OPENAPI, "the app serves a Network schema that differs from the CLI's copy")


def check_sync(text: str, rules: List[dict]) -> None:
    result = json.loads(text)
    response("/api/v1/network/rules/_/sync", "post").validate(result)
    expect(result["count"] == len(rules), "synced {} rules, the file has {}".format(result["count"], len(rules)))


def check_rules(text: str, rules: List[dict]) -> None:
    listed = json.loads(text)
    response("/api/v1/network/rules", "get").validate(listed)
    ids = [rule["id"] for rule in listed["items"]]
    expected = [rule["id"] for rule in rules]
    expect(ids == expected, "the app lists rules {}, the file has {}".format(ids, expected))


def check_list(text: str, rules: List[dict]) -> None:
    envelope = json.loads(text)
    shared("cursor-envelope.schema.json").validate(envelope)
    component("TransactionCursorEnvelope").validate(envelope)


def find(text: str, rules: List[dict]) -> None:
    """Prints the id of the newest listed transaction that the first rule matches."""
    pattern = rules[0]["urlPattern"]
    matches = [tx for tx in json.loads(text).get("items") or [] if pattern in tx["url"]]
    expect(bool(matches), "no transaction matches {}".format(pattern))
    print(matches[-1]["id"])


def check_transaction(text: str, rules: List[dict]) -> None:
    tx = json.loads(text)
    component("Transaction").validate(tx)
    rule = rules[0]
    expect(rule["urlPattern"] in tx["url"], "{} does not match {}".format(tx["url"], rule["urlPattern"]))
    expect(tx["isMocked"] is True, "the rule did not serve the request")
    expect(tx["statusCode"] == rule["statusCode"], "status {}, the rule sets {}".format(tx["statusCode"], rule["statusCode"]))
    expect(tx["responseBody"] == rule["responseBody"], "body {!r}, the rule sets {!r}".format(tx["responseBody"], rule["responseBody"]))


CHECKS: Dict[str, Callable[[str, List[dict]], None]] = {
    "open": check_open,
    "meta": check_meta,
    "schema-network": check_schema_network,
    "sync": check_sync,
    "rules": check_rules,
    "list": check_list,
    "find": find,
    "transaction": check_transaction,
}


def main(argv: List[str]) -> int:
    if not 1 <= len(argv) <= 2 or argv[0] not in CHECKS:
        print("usage: cli_e2e_check.py {{{}}} [RULES_FILE] < output".format(",".join(CHECKS)), file=sys.stderr)
        return 2
    rules: List[dict] = []
    if len(argv) == 2:
        with open(argv[1], encoding="utf-8") as fh:
            rules = json.load(fh)
    try:
        CHECKS[argv[0]](sys.stdin.read(), rules)
    except ValidationError as exc:
        print("{}: {} at /{}".format(argv[0], exc.message, "/".join(str(p) for p in exc.absolute_path)), file=sys.stderr)
        return 1
    except (CheckFailed, ValueError) as exc:
        print("{}: {}".format(argv[0], exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

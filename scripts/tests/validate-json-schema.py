#!/usr/bin/env python3
"""Validate the JSON Schema features used by the provenance receipt fixture."""

import json
import re
import sys
from pathlib import Path


def fail(path: str, message: str) -> None:
    raise ValueError(f"{path}: {message}")


def resolve_pointer(schema: object, pointer: str) -> object:
    if not pointer.startswith("#/"):
        fail("$ref", f"unsupported reference {pointer!r}")
    value = schema
    for token in pointer[2:].split("/"):
        token = token.replace("~1", "/").replace("~0", "~")
        if not isinstance(value, dict) or token not in value:
            fail("$ref", f"unresolved reference {pointer!r}")
        value = value[token]
    return value


def has_type(value: object, expected: str) -> bool:
    return {
        "object": isinstance(value, dict),
        "array": isinstance(value, list),
        "string": isinstance(value, str),
        "integer": isinstance(value, int) and not isinstance(value, bool),
        "null": value is None,
        "boolean": isinstance(value, bool),
    }.get(expected, False)


def validate(root: dict, rule: object, value: object, path: str = "$") -> None:
    if not isinstance(rule, dict):
        fail(path, "schema rule is not an object")
    if "$ref" in rule:
        validate(root, resolve_pointer(root, rule["$ref"]), value, path)
        return
    if "const" in rule and value != rule["const"]:
        fail(path, f"does not equal const {rule['const']!r}")
    if "type" in rule and not has_type(value, rule["type"]):
        fail(path, f"is not type {rule['type']}")
    if isinstance(value, str):
        if "minLength" in rule and len(value) < rule["minLength"]:
            fail(path, "is shorter than minLength")
        if "maxLength" in rule and len(value) > rule["maxLength"]:
            fail(path, "is longer than maxLength")
        if "pattern" in rule and re.search(rule["pattern"], value) is None:
            fail(path, f"does not match {rule['pattern']!r}")
    if isinstance(value, int) and not isinstance(value, bool):
        if "minimum" in rule and value < rule["minimum"]:
            fail(path, "is below minimum")
    if isinstance(value, dict):
        properties = rule.get("properties", {})
        for required in rule.get("required", []):
            if required not in value:
                fail(path, f"is missing required property {required!r}")
        if rule.get("additionalProperties") is False:
            extra = sorted(set(value) - set(properties))
            if extra:
                fail(path, f"has additional properties {extra!r}")
        for key, child in value.items():
            if key in properties:
                validate(root, properties[key], child, f"{path}.{key}")
    if isinstance(value, list):
        if rule.get("uniqueItems"):
            encoded = [json.dumps(item, sort_keys=True, separators=(",", ":")) for item in value]
            if len(encoded) != len(set(encoded)):
                fail(path, "contains duplicate items")
        if "items" in rule:
            for index, child in enumerate(value):
                validate(root, rule["items"], child, f"{path}[{index}]")


def main() -> int:
    if len(sys.argv) != 3:
        print(f"usage: {Path(sys.argv[0]).name} SCHEMA JSON", file=sys.stderr)
        return 2
    schema = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    document = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
    validate(schema, schema, document)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"schema fixture: {error}", file=sys.stderr)
        raise SystemExit(1)

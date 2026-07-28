from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml
from yaml.events import AliasEvent

_ID = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
_ROOT_KEYS = {"version", "tests"}
_CASE_KEYS = {
    "id",
    "name",
    "description",
    "preflight",
    "checklist",
    "agent_instruction",
    "evidence",
}


class ConfigError(ValueError):
    """The manual-test YAML does not satisfy the generic schema."""


class _UniqueKeyLoader(yaml.SafeLoader):
    pass


def _construct_mapping(
    loader: yaml.SafeLoader, node: yaml.MappingNode, deep: bool = False
) -> dict[Any, Any]:
    result: dict[Any, Any] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise ConfigError(f"duplicate mapping key: {key}")
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


_UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
    _construct_mapping,
)


@dataclass(frozen=True)
class TestCase:
    id: str
    name: str
    description: str
    preflight: tuple[str, ...]
    checklist: tuple[str, ...]
    agent_instruction: str
    evidence: tuple[str, ...]


@dataclass(frozen=True)
class TestSuite:
    version: int
    tests: tuple[TestCase, ...]


def _text(value: Any, where: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ConfigError(f"{where} must be a non-empty string")
    return value.strip()


def _text_list(value: Any, where: str, minimum: int = 0) -> tuple[str, ...]:
    if not isinstance(value, list) or len(value) < minimum:
        raise ConfigError(f"{where} must contain at least {minimum} items")
    return tuple(_text(item, f"{where} item") for item in value)


def _reject_aliases(source: str) -> None:
    try:
        if any(isinstance(event, AliasEvent) for event in yaml.parse(source)):
            raise ConfigError("YAML aliases are not allowed")
    except yaml.YAMLError as exc:
        raise ConfigError(f"invalid YAML: {exc}") from exc


def load_suite(path: str | Path) -> TestSuite:
    source = Path(path).read_text(encoding="utf-8")
    _reject_aliases(source)
    try:
        raw = yaml.load(source, Loader=_UniqueKeyLoader)
    except ConfigError:
        raise
    except yaml.YAMLError as exc:
        raise ConfigError(f"invalid YAML: {exc}") from exc

    if not isinstance(raw, dict):
        raise ConfigError("suite must be a mapping")
    unknown = set(raw) - _ROOT_KEYS
    if unknown:
        raise ConfigError(f"suite has unknown keys: {sorted(unknown)}")
    if raw.get("version") != 1:
        raise ConfigError("version must be 1")
    tests = raw.get("tests")
    if not isinstance(tests, list) or not tests:
        raise ConfigError("tests must be a non-empty list")

    parsed: list[TestCase] = []
    seen: set[str] = set()
    for index, item in enumerate(tests):
        where = f"tests[{index}]"
        if not isinstance(item, dict):
            raise ConfigError(f"{where} must be a mapping")
        unknown = set(item) - _CASE_KEYS
        missing = _CASE_KEYS - set(item)
        if unknown:
            raise ConfigError(f"{where} has unknown keys: {sorted(unknown)}")
        if missing:
            raise ConfigError(f"{where} is missing keys: {sorted(missing)}")

        test_id = _text(item["id"], f"{where}.id")
        if not _ID.fullmatch(test_id):
            raise ConfigError(f"{where} has invalid id")
        if test_id in seen:
            raise ConfigError(f"duplicate test id: {test_id}")
        seen.add(test_id)
        parsed.append(
            TestCase(
                id=test_id,
                name=_text(item["name"], f"{where}.name"),
                description=_text(item["description"], f"{where}.description"),
                preflight=_text_list(item["preflight"], f"{where}.preflight"),
                checklist=_text_list(item["checklist"], f"{where}.checklist", 2),
                agent_instruction=_text(item["agent_instruction"], f"{where}.agent_instruction"),
                evidence=_text_list(item["evidence"], f"{where}.evidence", 1),
            )
        )
    return TestSuite(version=1, tests=tuple(parsed))

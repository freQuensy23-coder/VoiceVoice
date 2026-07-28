from pathlib import Path

import pytest

from manual_test_agent.config import ConfigError, load_suite

VALID = """
version: 1
tests:
  - id: generic-flow
    name: A generic visible flow
    description: Exercise the visible flow.
    preflight:
      - adb install -r /tmp/app.apk
      - adb shell pm clear example.app
    checklist:
      - Establish the initial visible state.
      - Perform the requested interaction.
    agent_instruction: Complete every item and judge visible evidence.
    evidence:
      - Capture and inspect the initial state.
      - Capture and inspect the resulting state.
"""


def write(tmp_path: Path, text: str) -> Path:
    path = tmp_path / "suite.yaml"
    path.write_text(text, encoding="utf-8")
    return path


def test_loads_generic_suite(tmp_path: Path) -> None:
    suite = load_suite(write(tmp_path, VALID))

    assert suite.version == 1
    assert [case.id for case in suite.tests] == ["generic-flow"]
    assert suite.tests[0].checklist[1] == "Perform the requested interaction."
    assert suite.tests[0].evidence == (
        "Capture and inspect the initial state.",
        "Capture and inspect the resulting state.",
    )


@pytest.mark.parametrize(
    "text, message",
    [
        ("version: 2\ntests: []\n", "version must be 1"),
        ("version: 1\ntests: []\n", "tests must be a non-empty list"),
        ("version: 1\ntests: nope\n", "tests must be a non-empty list"),
        (
            VALID.replace("checklist:\n", "unknown: true\n    checklist:\n"),
            "unknown keys",
        ),
        (VALID.replace("generic-flow", "not valid!"), "invalid id"),
        (
            VALID.replace("      - Perform the requested interaction.\n", ""),
            "at least 2 items",
        ),
        (
            VALID.replace(
                "      - Capture and inspect the initial state.\n      - Capture and inspect the resulting state.\n",
                "    evidence: []\n",
            ).replace("    evidence:\n    evidence: []\n", "    evidence: []\n"),
            "at least 1 item",
        ),
    ],
)
def test_rejects_invalid_generic_shapes(tmp_path: Path, text: str, message: str) -> None:
    with pytest.raises(ConfigError, match=message):
        load_suite(write(tmp_path, text))


def test_rejects_duplicate_ids(tmp_path: Path) -> None:
    duplicate = VALID + VALID.split("tests:\n", 1)[1]
    with pytest.raises(ConfigError, match="duplicate test id"):
        load_suite(write(tmp_path, duplicate))


def test_rejects_yaml_aliases(tmp_path: Path) -> None:
    aliased = VALID.replace(
        "tests:\n",
        "shared: &shared [one, two]\ntests:\n",
    ).replace(
        "      - Establish the initial visible state.\n      - Perform the requested interaction.\n",
        "      *shared\n",
    )
    with pytest.raises(ConfigError, match="aliases are not allowed"):
        load_suite(write(tmp_path, aliased))

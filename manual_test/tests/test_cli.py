from __future__ import annotations

import json
import os
from pathlib import Path

from cryptography.fernet import Fernet

from manual_test_agent.cli import main

SUITE = """
version: 1
tests:
  - id: alpha-flow
    name: Alpha
    description: Generic.
    preflight: []
    checklist: [Observe alpha., Observe beta.]
    agent_instruction: Complete it.
    evidence: [Capture alpha.]
  - id: beta-flow
    name: Beta
    description: Generic.
    preflight: []
    checklist: [Observe one., Observe two.]
    agent_instruction: Complete it.
    evidence: [Capture beta.]
"""


def test_matrix_command_outputs_one_case_per_job(tmp_path: Path, capsys: object) -> None:
    suite = tmp_path / "suite.yaml"
    suite.write_text(SUITE)

    exit_code = main(["matrix", "--suite", str(suite)])

    assert exit_code == 0
    output = json.loads(capsys.readouterr().out)  # type: ignore[attr-defined]
    assert output == {"include": [{"test_id": "alpha-flow"}, {"test_id": "beta-flow"}]}


def test_aggregate_command_writes_json_and_markdown(tmp_path: Path) -> None:
    suite = tmp_path / "missing-suite.yaml"
    out = tmp_path / "out"

    exit_code = main(
        [
            "aggregate",
            "--suite",
            str(suite),
            "--artifacts",
            str(tmp_path / "missing"),
            "--output",
            str(out),
            "--pr-number",
            "5",
            "--head-sha",
            "sha",
            "--run-url",
            "https://run",
            "--blocked-reason",
            "Untrusted fork pull request.",
        ]
    )

    assert exit_code == 2
    assert json.loads((out / "report.json").read_text())["overall"] == "blocked"
    assert "blocked" in (out / "comment.md").read_text().lower()
    assert (out / "publication").is_dir()


def test_auth_state_cli_export_decrypt_and_persist(tmp_path: Path, monkeypatch: object) -> None:
    key = Fernet.generate_key().decode()
    monkeypatch.setenv("CODEX_AUTH_STATE_KEY", key)  # type: ignore[attr-defined]
    source = tmp_path / "source.json"
    seed = tmp_path / "seed.fernet"
    plaintext = tmp_path / "auth.json"
    run_auth = tmp_path / "run/codex-home/auth.json"
    rotated = tmp_path / "rotated.fernet"
    source.write_text(
        json.dumps(
            {
                "OPENAI_API_KEY": None,
                "auth_mode": "chatgpt",
                "tokens": {
                    "id_token": "id",
                    "access_token": "access",
                    "refresh_token": "refresh",
                    "account_id": "account",
                },
                "last_refresh": "now",
            }
        )
    )

    assert main(["export-auth", "--input", str(source), "--output", str(seed)]) == 0
    assert main(["decrypt-auth", "--input", str(seed), "--output", str(plaintext)]) == 0
    run_auth.parent.mkdir(parents=True)
    run_auth.write_bytes(plaintext.read_bytes())
    assert main(["persist-auth", "--run-dir", str(tmp_path / "run"), "--output", str(rotated)]) == 0
    assert seed.read_bytes() != rotated.read_bytes()
    assert b'"tokens"' not in seed.read_bytes()


def test_run_requires_auth_file_not_plaintext_environment(
    tmp_path: Path, monkeypatch: object, capsys: object
) -> None:
    suite = tmp_path / "suite.yaml"
    suite.write_text(SUITE)
    monkeypatch.setenv("CODEX_AUTH_JSON", "{}")  # type: ignore[attr-defined]

    exit_code = main(
        [
            "run",
            "--suite",
            str(suite),
            "--test-id",
            "alpha-flow",
            "--run-dir",
            str(tmp_path / "run"),
            "--auth-file",
            str(tmp_path / "missing.json"),
        ]
    )

    assert exit_code == 1
    assert "auth file" in capsys.readouterr().err.lower()  # type: ignore[attr-defined]
    assert os.environ["CODEX_AUTH_JSON"] == "{}"


def test_aggregate_invalid_suite_still_writes_error_report(tmp_path: Path) -> None:
    suite = tmp_path / "missing.yaml"
    out = tmp_path / "out"

    exit_code = main(
        [
            "aggregate",
            "--suite",
            str(suite),
            "--artifacts",
            str(tmp_path / "artifacts"),
            "--output",
            str(out),
            "--pr-number",
            "7",
            "--head-sha",
            "abc123",
            "--run-url",
            "https://run",
            "--error-reason",
            "Candidate YAML is missing or invalid.",
        ]
    )

    assert exit_code == 1
    report = json.loads((out / "report.json").read_text())
    assert report["overall"] == "error"
    assert report["error_reason"] == "Candidate YAML is missing or invalid."
    assert "ERROR" in (out / "comment.md").read_text()
    assert (out / "publication").is_dir()

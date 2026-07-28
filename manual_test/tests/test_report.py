from __future__ import annotations

import hashlib
import json
from base64 import b64decode
from pathlib import Path

import pytest

from manual_test_agent.config import load_suite
from manual_test_agent.report import aggregate_results, render_markdown

SUITE = """
version: 1
tests:
  - id: first-flow
    name: First generic flow
    description: Check it.
    preflight: []
    checklist: [Observe first., Observe second.]
    agent_instruction: Complete it.
    evidence: [Capture first., Capture second.]
  - id: second-flow
    name: Second generic flow
    description: Check another.
    preflight: []
    checklist: [Observe one., Observe two.]
    agent_instruction: Complete it.
    evidence: [Capture one.]
"""

PNG = b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
)


def write_result(root: Path, test_id: str, verdict: str) -> None:
    run = root / test_id
    (run / "evidence").mkdir(parents=True)
    result = {
        "schema_version": 1,
        "verdict": verdict,
        "summary": "Concrete <visible> summary @someone.",
        "checklist": [
            {"index": 1, "status": verdict, "observation": "Observed one."},
            {"index": 2, "status": verdict, "observation": "Observed two."},
        ],
        "evidence_ids": ["state", "state-two"],
        "submitted_at": "2026-01-01T00:00:00Z",
    }
    (run / "final_result.json").write_text(json.dumps(result))
    (run / "actions.jsonl").write_text(
        json.dumps({"timestamp": "now", "tool": "observe", "arguments": {}, "outcome": "ok"}) + "\n"
    )
    for evidence_id in ("state", "state-two"):
        hierarchy = f'<hierarchy text="Visible {evidence_id}" content-desc="Action"/>'
        (run / f"evidence/{evidence_id}.png").write_bytes(PNG)
        (run / f"evidence/{evidence_id}.xml").write_text(hierarchy)
        (run / f"evidence/{evidence_id}.json").write_text(
            json.dumps(
                {
                    "id": evidence_id,
                    "png": f"evidence/{evidence_id}.png",
                    "hierarchy": f"evidence/{evidence_id}.xml",
                    "png_sha256": hashlib.sha256(PNG).hexdigest(),
                    "hierarchy_sha256": hashlib.sha256(hierarchy.encode()).hexdigest(),
                    "inspected": True,
                }
            )
        )


def test_aggregate_preserves_agent_verdicts_observations_trace_and_evidence(
    tmp_path: Path,
) -> None:
    suite_path = tmp_path / "suite.yaml"
    suite_path.write_text(SUITE)
    suite = load_suite(suite_path)
    write_result(tmp_path / "artifacts", "first-flow", "pass")
    write_result(tmp_path / "artifacts", "second-flow", "fail")

    publication = tmp_path / "publication"
    report = aggregate_results(
        suite,
        tmp_path / "artifacts",
        publication=publication,
        pr_number=17,
        head_sha="abc123",
    )

    assert report["overall"] == "fail"
    assert [item["verdict"] for item in report["tests"]] == ["pass", "fail"]
    assert report["tests"][0]["checklist"][0]["observation"] == "Observed one."
    assert report["tests"][0]["actions"][0]["tool"] == "observe"
    assert report["tests"][0]["evidence"][0]["inspected"] is True
    evidence = report["tests"][0]["evidence"][0]
    assert evidence["png_size"] == len(PNG)
    assert evidence["width"] == 1
    assert evidence["height"] == 1
    assert evidence["ui_text"] == ["Visible state", "Action"]
    assert (publication / evidence["published_png"]).read_bytes() == PNG
    assert (publication / evidence["published_hierarchy"]).is_file()


def test_missing_or_invalid_result_is_reported_as_error_not_pass(
    tmp_path: Path,
) -> None:
    suite_path = tmp_path / "suite.yaml"
    suite_path.write_text(SUITE)
    suite = load_suite(suite_path)
    (tmp_path / "artifacts/first-flow").mkdir(parents=True)
    write_result(tmp_path / "artifacts", "second-flow", "pass")

    report = aggregate_results(
        suite,
        tmp_path / "artifacts",
        publication=tmp_path / "publication",
        pr_number=1,
        head_sha="abc123",
    )

    assert report["overall"] == "error"
    assert report["tests"][0]["verdict"] == "error"
    assert "missing" in report["tests"][0]["summary"]


def test_markdown_is_sticky_safe_and_links_evidence_artifact(tmp_path: Path) -> None:
    suite_path = tmp_path / "suite.yaml"
    suite_path.write_text(SUITE)
    suite = load_suite(suite_path)
    write_result(tmp_path / "artifacts", "first-flow", "pass")
    write_result(tmp_path / "artifacts", "second-flow", "pass")
    report = aggregate_results(
        suite,
        tmp_path / "artifacts",
        publication=tmp_path / "publication",
        pr_number=2,
        head_sha="abc123",
    )

    markdown = render_markdown(
        report,
        "https://github.example/runs/9",
        evidence_base_url="https://raw.githubusercontent.example/repo/commit/publication",
    )

    assert markdown.startswith("<!-- codex-android-manual-tests -->")
    assert "Observed one." in markdown
    assert "Action trace" in markdown
    assert "state.png" in markdown
    assert "![Screenshot state]" in markdown
    assert "raw.githubusercontent.example/repo/commit/publication" in markdown
    assert "UI text: Visible state; Action" in markdown
    assert "UI hierarchy XML" in markdown
    assert "<visible>" not in markdown
    assert "@someone" not in markdown


def test_blocked_report_is_explicit(tmp_path: Path) -> None:
    suite_path = tmp_path / "suite.yaml"
    suite_path.write_text(SUITE)
    suite = load_suite(suite_path)

    report = aggregate_results(
        suite,
        tmp_path / "does-not-exist",
        publication=tmp_path / "publication",
        pr_number=3,
        head_sha="forksha",
        blocked_reason="Codex credentials are unavailable to this untrusted PR.",
    )

    assert report["overall"] == "blocked"
    assert report["tests"] == []
    assert "untrusted PR" in render_markdown(report, "https://run")


def test_aggregate_rejects_tampered_hash_and_invalid_png(tmp_path: Path) -> None:
    suite_path = tmp_path / "suite.yaml"
    suite_path.write_text(SUITE)
    suite = load_suite(suite_path)
    write_result(tmp_path / "artifacts", "first-flow", "pass")
    write_result(tmp_path / "artifacts", "second-flow", "pass")
    (tmp_path / "artifacts/first-flow/evidence/state.png").write_bytes(b"not png")

    report = aggregate_results(
        suite,
        tmp_path / "artifacts",
        publication=tmp_path / "publication",
        pr_number=2,
        head_sha="abc123",
    )

    assert report["overall"] == "error"
    summary = report["tests"][0]["summary"]
    assert "PNG" in summary or "hash" in summary
    assert not (tmp_path / "publication/pr-2/abc123/first-flow/state.png").exists()


def test_aggregate_rejects_symlink_anywhere_in_artifact_tree(tmp_path: Path) -> None:
    suite_path = tmp_path / "suite.yaml"
    suite_path.write_text(SUITE)
    suite = load_suite(suite_path)
    artifacts = tmp_path / "artifacts"
    artifacts.mkdir()
    (artifacts / "unrelated-link").symlink_to(tmp_path / "outside")

    with pytest.raises(ValueError, match="symlink"):
        aggregate_results(
            suite,
            artifacts,
            publication=tmp_path / "publication",
            pr_number=1,
            head_sha="abc123",
        )

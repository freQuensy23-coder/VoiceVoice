from __future__ import annotations

import json
from base64 import b64decode
from pathlib import Path

import pytest

from manual_test_agent.android_mcp.backend import Snapshot
from manual_test_agent.android_mcp.state import EvidenceError, RunState

PNG = b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
)


class Device:
    def snapshot(self) -> Snapshot:
        return Snapshot(png=PNG, hierarchy='<hierarchy text="Ready"/>')


def state(tmp_path: Path) -> RunState:
    return RunState(tmp_path, required_evidence=2, required_checklist=2)


def checklist(status: str = "pass") -> list[dict[str, object]]:
    return [
        {"index": 1, "status": status, "observation": "Observed the first state."},
        {"index": 2, "status": status, "observation": "Observed the second state."},
    ]


def test_capture_persists_png_hierarchy_and_manifest(tmp_path: Path) -> None:
    record = state(tmp_path).capture("initial", Device())

    assert record.id == "initial"
    assert (tmp_path / "evidence/initial.png").read_bytes() == PNG
    assert (tmp_path / "evidence/initial.xml").read_text() == '<hierarchy text="Ready"/>'
    manifest = json.loads((tmp_path / "evidence/initial.json").read_text())
    assert manifest["id"] == "initial"
    assert len(manifest["png_sha256"]) == 64
    assert manifest["inspected"] is False


def test_latest_snapshot_is_validated_atomic_and_bound_to_action_sequence(tmp_path: Path) -> None:
    run = state(tmp_path)
    run.write_latest(Device().snapshot(), sequence=1)

    assert (tmp_path / "latest.png").read_bytes() == PNG
    assert (tmp_path / "latest.xml").read_text() == '<hierarchy text="Ready"/>'
    manifest = json.loads((tmp_path / "latest.json").read_text())
    assert manifest["sequence"] == 1
    assert len(manifest["png_sha256"]) == 64
    assert not list(tmp_path.glob("latest.*.tmp"))


def test_capture_rejects_non_png_or_empty_hierarchy(tmp_path: Path) -> None:
    class InvalidDevice:
        def snapshot(self) -> Snapshot:
            return Snapshot(png=b"not-png", hierarchy="")

    with pytest.raises(EvidenceError, match="PNG"):
        state(tmp_path).capture("invalid", InvalidDevice())


def test_inspect_recomputes_captured_hashes_before_marking_inspected(
    tmp_path: Path,
) -> None:
    run = state(tmp_path)
    run.capture("initial", Device())
    (tmp_path / "evidence/initial.png").write_bytes(PNG + b"tampered")

    with pytest.raises(EvidenceError, match="integrity"):
        run.inspect("initial")
    manifest = json.loads((tmp_path / "evidence/initial.json").read_text())
    assert manifest["inspected"] is False


def test_submit_rejects_missing_evidence(tmp_path: Path) -> None:
    run = state(tmp_path)
    run.capture("initial", Device())
    run.inspect("initial")

    with pytest.raises(EvidenceError, match="at least 2 captured"):
        run.submit("fail", "Could not complete.", checklist("fail"), ["initial"])


def test_submit_rejects_evidence_not_reopened(tmp_path: Path) -> None:
    run = state(tmp_path)
    run.capture("initial", Device())
    run.capture("result", Device())
    run.inspect("initial")

    with pytest.raises(EvidenceError, match="must be inspected"):
        run.submit("pass", "Looks correct.", checklist(), ["initial", "result"])


def test_submit_requires_all_and_only_captured_evidence(tmp_path: Path) -> None:
    run = state(tmp_path)
    for evidence_id in ("initial", "result", "extra"):
        run.capture(evidence_id, Device())
        run.inspect(evidence_id)

    with pytest.raises(EvidenceError, match="all captured evidence"):
        run.submit("pass", "Looks correct.", checklist(), ["initial", "result"])


def test_submit_validates_generic_checklist_contract(tmp_path: Path) -> None:
    run = state(tmp_path)
    for evidence_id in ("initial", "result"):
        run.capture(evidence_id, Device())
        run.inspect(evidence_id)

    with pytest.raises(EvidenceError, match="2 checklist results"):
        run.submit("pass", "Looks correct.", checklist()[:1], ["initial", "result"])
    with pytest.raises(EvidenceError, match="cannot pass"):
        run.submit("pass", "Looks correct.", checklist("fail"), ["initial", "result"])


def test_valid_submit_is_atomic_and_exactly_once(tmp_path: Path) -> None:
    run = state(tmp_path)
    for evidence_id in ("initial", "result"):
        run.capture(evidence_id, Device())
        run.inspect(evidence_id)

    result = run.submit("pass", "All visible states matched.", checklist(), ["initial", "result"])

    assert result["verdict"] == "pass"
    assert json.loads((tmp_path / "final_result.json").read_text()) == result
    assert not (tmp_path / "final_result.json.tmp").exists()
    with pytest.raises(EvidenceError, match="already submitted"):
        run.submit("pass", "Again.", checklist(), ["initial", "result"])


def test_every_operation_is_appended_to_transcript(tmp_path: Path) -> None:
    run = state(tmp_path)
    run.capture("initial", Device())
    run.inspect("initial")

    events = [json.loads(line) for line in (tmp_path / "actions.jsonl").read_text().splitlines()]
    assert [event["tool"] for event in events] == ["capture", "inspect"]
    assert all("timestamp" in event for event in events)

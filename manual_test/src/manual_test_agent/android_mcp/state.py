from __future__ import annotations

import hashlib
import json
import os
import re
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Protocol

from ..evidence import (
    IntegrityError,
    read_validated_evidence,
    validate_hierarchy,
    validate_png,
)
from .backend import Snapshot

_EVIDENCE_ID = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")


class EvidenceError(ValueError):
    """The generic evidence/checklist lifecycle was violated."""


class SnapshotDevice(Protocol):
    def snapshot(self) -> Snapshot: ...


@dataclass(frozen=True)
class EvidenceRecord:
    id: str
    png: str
    hierarchy: str
    png_sha256: str
    hierarchy_sha256: str
    inspected: bool


class RunState:
    def __init__(self, root: str | Path, required_evidence: int, required_checklist: int) -> None:
        if required_evidence < 1 or required_checklist < 1:
            raise ValueError("required counts must be positive")
        self.root = Path(root)
        self.evidence_dir = self.root / "evidence"
        self.evidence_dir.mkdir(parents=True, exist_ok=True)
        self.required_evidence = required_evidence
        self.required_checklist = required_checklist
        self._records: dict[str, EvidenceRecord] = {}
        for manifest in sorted(self.evidence_dir.glob("*.json")):
            if manifest.is_symlink() or not manifest.is_file():
                raise EvidenceError("invalid evidence manifest")
            try:
                record = EvidenceRecord(**json.loads(manifest.read_text(encoding="utf-8")))
            except (OSError, TypeError, json.JSONDecodeError) as exc:
                raise EvidenceError("invalid evidence manifest") from exc
            self._records[record.id] = record
        actions = self.root / "actions.jsonl"
        self._action_count = (
            len(actions.read_text(encoding="utf-8").splitlines())
            if actions.is_file() and not actions.is_symlink()
            else 0
        )
        self._submitted = (self.root / "final_result.json").exists()

    @staticmethod
    def _now() -> str:
        return datetime.now(UTC).isoformat()

    def log(self, tool: str, arguments: dict[str, Any], outcome: str = "ok") -> None:
        event = {
            "timestamp": self._now(),
            "tool": tool,
            "arguments": arguments,
            "outcome": outcome,
        }
        self.root.mkdir(parents=True, exist_ok=True)
        with (self.root / "actions.jsonl").open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(event, sort_keys=True) + "\n")
        self._action_count += 1

    def diagnostic(self, message: str) -> None:
        event = {"timestamp": self._now(), "message": str(message)[:1_000]}
        self.root.mkdir(parents=True, exist_ok=True)
        with (self.root / "mcp-diagnostics.jsonl").open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(event, sort_keys=True) + "\n")

    @property
    def action_count(self) -> int:
        return self._action_count

    @staticmethod
    def _write_atomic(path: Path, data: bytes) -> None:
        temporary = path.with_name(path.name + ".tmp")
        temporary.write_bytes(data)
        os.replace(temporary, path)

    def _manifest_path(self, evidence_id: str) -> Path:
        return self.evidence_dir / f"{evidence_id}.json"

    def _save_manifest(self, record: EvidenceRecord) -> None:
        payload = (json.dumps(asdict(record), indent=2, sort_keys=True) + "\n").encode()
        self._write_atomic(self._manifest_path(record.id), payload)

    def write_latest(self, snapshot: Snapshot, *, sequence: int | None = None) -> None:
        """Atomically publish a bounded snapshot for native CLI image attachment."""
        try:
            png_info = validate_png(snapshot.png)
            hierarchy = snapshot.hierarchy.encode("utf-8")
            _, _, hierarchy_hash = validate_hierarchy(hierarchy)
        except (IntegrityError, UnicodeEncodeError) as exc:
            raise EvidenceError(str(exc)) from exc
        sequence = self._action_count if sequence is None else sequence
        if sequence < 1:
            raise EvidenceError("latest snapshot sequence must be positive")
        self.root.mkdir(parents=True, exist_ok=True)
        self._write_atomic(self.root / "latest.png", snapshot.png)
        self._write_atomic(self.root / "latest.xml", hierarchy)
        manifest = {
            "sequence": sequence,
            "png_sha256": png_info.sha256,
            "hierarchy_sha256": hierarchy_hash,
            "png_size": png_info.size,
            "hierarchy_size": len(hierarchy),
        }
        self._write_atomic(
            self.root / "latest.json",
            (json.dumps(manifest, sort_keys=True) + "\n").encode("utf-8"),
        )

    def capture(self, evidence_id: str, device: SnapshotDevice) -> EvidenceRecord:
        if not isinstance(evidence_id, str) or not _EVIDENCE_ID.fullmatch(evidence_id):
            raise EvidenceError("evidence id must be lowercase kebab-case")
        if evidence_id in self._records or self._manifest_path(evidence_id).exists():
            raise EvidenceError(f"evidence already captured: {evidence_id}")
        snapshot = device.snapshot()
        try:
            validate_png(snapshot.png)
            validate_hierarchy(snapshot.hierarchy.encode("utf-8"))
        except (IntegrityError, UnicodeEncodeError) as exc:
            raise EvidenceError(str(exc)) from exc
        png_path = self.evidence_dir / f"{evidence_id}.png"
        xml_path = self.evidence_dir / f"{evidence_id}.xml"
        self._write_atomic(png_path, snapshot.png)
        self._write_atomic(xml_path, snapshot.hierarchy.encode("utf-8"))
        record = EvidenceRecord(
            id=evidence_id,
            png=str(png_path.relative_to(self.root)),
            hierarchy=str(xml_path.relative_to(self.root)),
            png_sha256=hashlib.sha256(snapshot.png).hexdigest(),
            hierarchy_sha256=hashlib.sha256(snapshot.hierarchy.encode()).hexdigest(),
            inspected=False,
        )
        self._records[evidence_id] = record
        self._save_manifest(record)
        self.log("capture", {"evidence_id": evidence_id})
        return record

    def inspect(self, evidence_id: str) -> Snapshot:
        record = self._records.get(evidence_id)
        if record is None:
            raise EvidenceError(f"evidence does not exist: {evidence_id}")
        try:
            png, hierarchy, _, _ = read_validated_evidence(
                self.root / record.png,
                self.root / record.hierarchy,
                expected_png_sha256=record.png_sha256,
                expected_hierarchy_sha256=record.hierarchy_sha256,
            )
        except (IntegrityError, OSError) as exc:
            raise EvidenceError(f"evidence integrity check failed: {exc}") from exc
        snapshot = Snapshot(png=png, hierarchy=hierarchy.decode("utf-8"))
        inspected = EvidenceRecord(**{**asdict(record), "inspected": True})
        self._records[evidence_id] = inspected
        self._save_manifest(inspected)
        self.log("inspect", {"evidence_id": evidence_id})
        return snapshot

    def submit(
        self,
        verdict: str,
        summary: str,
        checklist_results: list[dict[str, Any]],
        evidence_ids: list[str],
    ) -> dict[str, Any]:
        if self._submitted:
            raise EvidenceError("result already submitted")
        if verdict not in {"pass", "fail"}:
            raise EvidenceError("verdict must be pass or fail")
        if not isinstance(summary, str) or not summary.strip():
            raise EvidenceError("summary must be non-empty")
        if len(self._records) < self.required_evidence:
            raise EvidenceError(
                f"at least {self.required_evidence} captured evidence artifacts are required"
            )
        if set(evidence_ids) != set(self._records) or len(evidence_ids) != len(self._records):
            raise EvidenceError("submit must reference all captured evidence exactly once")
        uninspected = sorted(key for key, record in self._records.items() if not record.inspected)
        if uninspected:
            raise EvidenceError(f"all evidence must be inspected; pending: {uninspected}")
        if (
            not isinstance(checklist_results, list)
            or len(checklist_results) != self.required_checklist
        ):
            raise EvidenceError(f"exactly {self.required_checklist} checklist results are required")

        normalized: list[dict[str, Any]] = []
        for expected_index, item in enumerate(checklist_results, 1):
            if not isinstance(item, dict) or set(item) != {
                "index",
                "status",
                "observation",
            }:
                raise EvidenceError(
                    "each checklist result requires only index, status, and observation"
                )
            if item["index"] != expected_index:
                raise EvidenceError("checklist results must be in order with one-based indexes")
            if item["status"] not in {"pass", "fail"}:
                raise EvidenceError("checklist status must be pass or fail")
            observation = item["observation"]
            if not isinstance(observation, str) or not observation.strip():
                raise EvidenceError("checklist observation must be non-empty")
            normalized.append(
                {
                    "index": expected_index,
                    "status": item["status"],
                    "observation": observation.strip(),
                }
            )
        if verdict == "pass" and any(item["status"] != "pass" for item in normalized):
            raise EvidenceError("result cannot pass when a checklist item failed")

        result = {
            "schema_version": 1,
            "verdict": verdict,
            "summary": summary.strip(),
            "checklist": normalized,
            "evidence_ids": evidence_ids,
            "submitted_at": self._now(),
        }
        self._write_atomic(
            self.root / "final_result.json",
            (json.dumps(result, indent=2, sort_keys=True) + "\n").encode(),
        )
        self._submitted = True
        self.log("submit", {"verdict": verdict, "evidence_ids": evidence_ids})
        return result

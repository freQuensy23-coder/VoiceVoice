from __future__ import annotations

import html
import json
import os
from pathlib import Path
from typing import Any
from urllib.parse import quote

from .config import TestCase, TestSuite
from .evidence import IntegrityError, read_validated_evidence

_MARKER = "<!-- codex-android-manual-tests -->"
_MAX_JSON_BYTES = 2_000_000
_MAX_ACTIONS = 500


def _reject_symlink_tree(root: Path) -> None:
    if root.is_symlink():
        raise ValueError("artifact tree contains a symlink")
    if not root.exists():
        return
    for path in root.rglob("*"):
        if path.is_symlink():
            raise ValueError("artifact tree contains a symlink")


def _read_json(path: Path) -> Any:
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"missing JSON: {path.name}")
    if path.stat().st_size > _MAX_JSON_BYTES:
        raise ValueError(f"oversized JSON: {path.name}")
    return json.loads(path.read_text(encoding="utf-8"))


def _find_run(artifacts: Path, test_id: str) -> Path | None:
    direct = artifacts / test_id / "final_result.json"
    if direct.is_file():
        return direct.parent
    if not artifacts.exists():
        return None
    candidates: list[Path] = []
    for path in artifacts.rglob("final_result.json"):
        if (
            path.is_file()
            and not path.is_symlink()
            and (path.parent.name in {test_id, f"manual-test-{test_id}"} or test_id in path.parts)
        ):
            candidates.append(path.parent)
    return candidates[0] if len(candidates) == 1 else None


def _generic_result(result: Any, case: TestCase) -> None:
    if not isinstance(result, dict) or result.get("schema_version") != 1:
        raise ValueError("invalid result schema")
    if result.get("verdict") not in {"pass", "fail"}:
        raise ValueError("invalid verdict")
    checklist = result.get("checklist")
    if not isinstance(checklist, list) or len(checklist) != len(case.checklist):
        raise ValueError("invalid checklist count")
    for index, item in enumerate(checklist, 1):
        if (
            not isinstance(item, dict)
            or item.get("index") != index
            or item.get("status") not in {"pass", "fail"}
            or not isinstance(item.get("observation"), str)
            or not item["observation"].strip()
        ):
            raise ValueError("invalid checklist entry")
    evidence_ids = result.get("evidence_ids")
    if (
        not isinstance(evidence_ids, list)
        or len(evidence_ids) < len(case.evidence)
        or len(evidence_ids) != len(set(evidence_ids))
        or not all(isinstance(item, str) and item for item in evidence_ids)
    ):
        raise ValueError("invalid evidence references")
    if result["verdict"] == "pass" and any(item["status"] != "pass" for item in checklist):
        raise ValueError("pass verdict conflicts with checklist")


def _read_actions(run: Path) -> list[dict[str, Any]]:
    path = run / "actions.jsonl"
    if path.is_symlink() or not path.is_file() or path.stat().st_size > _MAX_JSON_BYTES:
        return []
    actions: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines()[:_MAX_ACTIONS]:
        value = json.loads(line)
        if isinstance(value, dict):
            actions.append(value)
    return actions


def _write_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_bytes(data)
    os.replace(temporary, path)


def _read_and_publish_evidence(
    run: Path,
    ids: list[str],
    publication: Path,
    prefix: Path,
) -> list[dict[str, Any]]:
    pending: list[tuple[dict[str, Any], bytes, bytes, Path, Path]] = []
    for evidence_id in ids:
        manifest_path = run / "evidence" / f"{evidence_id}.json"
        value = _read_json(manifest_path)
        if (
            not isinstance(value, dict)
            or set(value)
            != {
                "id",
                "png",
                "hierarchy",
                "png_sha256",
                "hierarchy_sha256",
                "inspected",
            }
            or value.get("id") != evidence_id
            or value.get("inspected") is not True
        ):
            raise ValueError(f"invalid evidence manifest: {evidence_id}")
        expected_png = f"evidence/{evidence_id}.png"
        expected_hierarchy = f"evidence/{evidence_id}.xml"
        if value["png"] != expected_png or value["hierarchy"] != expected_hierarchy:
            raise ValueError(f"invalid evidence path: {evidence_id}")
        png, hierarchy, png_info, ui_text = read_validated_evidence(
            run / expected_png,
            run / expected_hierarchy,
            expected_png_sha256=value["png_sha256"],
            expected_hierarchy_sha256=value["hierarchy_sha256"],
        )
        published_png = prefix / f"{evidence_id}.png"
        published_hierarchy = prefix / f"{evidence_id}.xml"
        record = {
            **value,
            "png_sha256": png_info.sha256,
            "hierarchy_sha256": value["hierarchy_sha256"],
            "png_size": png_info.size,
            "hierarchy_size": len(hierarchy),
            "width": png_info.width,
            "height": png_info.height,
            "ui_text": ui_text,
            "published_png": published_png.as_posix(),
            "published_hierarchy": published_hierarchy.as_posix(),
        }
        pending.append((record, png, hierarchy, published_png, published_hierarchy))

    records: list[dict[str, Any]] = []
    for record, png, hierarchy, published_png, published_hierarchy in pending:
        _write_atomic(publication / published_png, png)
        _write_atomic(publication / published_hierarchy, hierarchy)
        records.append(record)
    return records


def aggregate_results(
    suite: TestSuite,
    artifacts: str | Path,
    *,
    publication: str | Path,
    pr_number: int,
    head_sha: str,
    blocked_reason: str | None = None,
) -> dict[str, Any]:
    if blocked_reason:
        return {
            "schema_version": 1,
            "overall": "blocked",
            "pr_number": pr_number,
            "head_sha": head_sha,
            "blocked_reason": blocked_reason,
            "tests": [],
        }
    if pr_number < 1:
        raise ValueError("pull-request number must be positive")
    if not 6 <= len(head_sha) <= 64 or any(char not in "0123456789abcdef" for char in head_sha):
        raise ValueError("head SHA is invalid")

    artifacts_path = Path(artifacts)
    publication_path = Path(publication)
    _reject_symlink_tree(artifacts_path)
    tests: list[dict[str, Any]] = []
    for case in suite.tests:
        run = _find_run(artifacts_path, case.id)
        if run is None:
            tests.append(
                {
                    "id": case.id,
                    "name": case.name,
                    "verdict": "error",
                    "summary": "Result artifact is missing or ambiguous.",
                    "checklist": [],
                    "actions": [],
                    "evidence": [],
                }
            )
            continue
        try:
            result = _read_json(run / "final_result.json")
            _generic_result(result, case)
            prefix = Path(f"pr-{pr_number}") / head_sha / case.id
            evidence = _read_and_publish_evidence(
                run, result["evidence_ids"], publication_path, prefix
            )
            tests.append(
                {
                    "id": case.id,
                    "name": case.name,
                    "verdict": result["verdict"],
                    "summary": result.get("summary", ""),
                    "checklist": result["checklist"],
                    "actions": _read_actions(run),
                    "evidence": evidence,
                }
            )
        except (OSError, ValueError, json.JSONDecodeError, IntegrityError) as exc:
            tests.append(
                {
                    "id": case.id,
                    "name": case.name,
                    "verdict": "error",
                    "summary": f"Invalid or incomplete result artifact: {exc}",
                    "checklist": [],
                    "actions": [],
                    "evidence": [],
                }
            )
    verdicts = {item["verdict"] for item in tests}
    overall = "error" if "error" in verdicts else "fail" if "fail" in verdicts else "pass"
    return {
        "schema_version": 1,
        "overall": overall,
        "pr_number": pr_number,
        "head_sha": head_sha,
        "tests": tests,
    }


def verify_publication(report: dict[str, Any], publication: str | Path) -> None:
    root = Path(publication)
    _reject_symlink_tree(root)
    for test in report.get("tests", []):
        for evidence in test.get("evidence", []):
            png_relative = evidence.get("published_png")
            xml_relative = evidence.get("published_hierarchy")
            if not isinstance(png_relative, str) or not isinstance(xml_relative, str):
                raise TypeError("publication path is missing")
            png_path = Path(png_relative)
            xml_path = Path(xml_relative)
            if (
                png_path.is_absolute()
                or xml_path.is_absolute()
                or ".." in png_path.parts
                or ".." in xml_path.parts
            ):
                raise ValueError("publication path is unsafe")
            _, hierarchy, info, ui_text = read_validated_evidence(
                root / png_path,
                root / xml_path,
                expected_png_sha256=evidence.get("png_sha256", ""),
                expected_hierarchy_sha256=evidence.get("hierarchy_sha256", ""),
            )
            if (
                info.size != evidence.get("png_size")
                or len(hierarchy) != evidence.get("hierarchy_size")
                or info.width != evidence.get("width")
                or info.height != evidence.get("height")
                or ui_text != evidence.get("ui_text")
            ):
                raise ValueError("publication metadata mismatch")


def _safe(value: Any) -> str:
    text = html.escape(str(value), quote=False).replace("@", "@\u200b")
    return text.replace("|", "\\|").replace("`", "\\`")


def _evidence_url(base: str, relative: str) -> str:
    return base.rstrip("/") + "/" + quote(relative, safe="/")


def render_markdown(
    report: dict[str, Any],
    run_url: str,
    *,
    evidence_base_url: str | None = None,
) -> str:
    lines = [_MARKER, "## Codex Android manual tests", ""]
    overall = report["overall"]
    lines.append(f"Overall: **{_safe(overall.upper())}**")
    if overall == "blocked":
        lines.extend(
            [
                "",
                _safe(report.get("blocked_reason", "Run was blocked.")),
                "",
                f"[Workflow run]({_safe(run_url)})",
            ]
        )
        return "\n".join(lines) + "\n"

    for item in report.get("tests", []):
        lines.extend(
            [
                "",
                f"### {_safe(item['name'])} — {_safe(item['verdict'].upper())}",
                "",
                _safe(item.get("summary", "")),
            ]
        )
        checklist = item.get("checklist", [])
        if checklist:
            lines.extend(["", "| # | Verdict | Observation |", "|---:|---|---|"])
            for result in checklist:
                lines.append(
                    f"| {result['index']} | {_safe(result['status'])} | {_safe(result['observation'])} |"
                )
        lines.extend(["", "<details><summary>Action trace</summary>", ""])
        for action in item.get("actions", []):
            arguments = json.dumps(action.get("arguments", {}), sort_keys=True, ensure_ascii=False)
            lines.append(
                f"- `{_safe(action.get('tool', '?'))}` {_safe(arguments)} → {_safe(action.get('outcome', '?'))}"
            )
        lines.extend(["", "</details>", "", "Evidence:"])
        for evidence in item.get("evidence", []):
            evidence_id = _safe(evidence["id"])
            if evidence_base_url:
                png_url = _evidence_url(evidence_base_url, evidence["published_png"])
                xml_url = _evidence_url(evidence_base_url, evidence["published_hierarchy"])
                lines.extend(
                    [
                        "",
                        f"#### Evidence `{evidence_id}`",
                        "",
                        f"![Screenshot {evidence_id}]({_safe(png_url)})",
                        "",
                        f"[Full-resolution PNG]({_safe(png_url)}) · [UI hierarchy XML]({_safe(xml_url)})",
                    ]
                )
            else:
                lines.append(
                    f"- `{evidence_id}`: publication pending; sha256=`{_safe(evidence.get('png_sha256', ''))}`"
                )
            ui_text = evidence.get("ui_text", [])
            if ui_text:
                lines.append("UI text: " + "; ".join(_safe(value) for value in ui_text))
            lines.append(
                f"Validated PNG {evidence.get('width', '?')}×{evidence.get('height', '?')}, {evidence.get('png_size', '?')} bytes; inspected=true; sha256=`{_safe(evidence.get('png_sha256', ''))}`"
            )
    lines.extend(["", f"[Trusted workflow run]({_safe(run_url)})"])
    return "\n".join(lines) + "\n"

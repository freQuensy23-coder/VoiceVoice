from __future__ import annotations

import argparse
import json
import os
import sys
from collections.abc import Sequence
from pathlib import Path

from .auth_state import (
    AuthStateError,
    decrypt_auth_state,
    export_auth_state,
    load_codex_auth,
    persist_rotated_auth_state,
)
from .config import ConfigError, TestCase, load_suite
from .launcher import LaunchError, launch_case, run_preflight
from .pixel_probe import CASE as PIXEL_PROBE_CASE
from .pixel_probe import FIXTURE as PIXEL_PROBE_FIXTURE
from .report import aggregate_results, render_markdown, verify_publication


def _case(suite_path: str, test_id: str) -> TestCase:
    suite = load_suite(suite_path)
    matches = [case for case in suite.tests if case.id == test_id]
    if len(matches) != 1:
        raise ConfigError(f"unknown test id: {test_id}")
    return matches[0]


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(prog="manual-test")
    commands = root.add_subparsers(dest="command", required=True)

    matrix = commands.add_parser("matrix")
    matrix.add_argument("--suite", required=True)

    preflight = commands.add_parser("preflight")
    preflight.add_argument("--suite", required=True)
    preflight.add_argument("--test-id", required=True)
    preflight.add_argument("--cwd", default=".")

    run = commands.add_parser("run")
    run.add_argument("--suite", required=True)
    run.add_argument("--test-id", required=True)
    run.add_argument("--run-dir", required=True)
    run.add_argument("--auth-file", required=True)
    run.add_argument("--codex-bin", default="codex")

    pixel_probe = commands.add_parser("pixel-probe")
    pixel_probe.add_argument("--run-dir", required=True)
    pixel_probe.add_argument("--auth-file", required=True)
    pixel_probe.add_argument("--codex-bin", default="codex")

    for name in ("export-auth", "decrypt-auth"):
        command = commands.add_parser(name)
        command.add_argument("--input", required=True)
        command.add_argument("--output", required=True)
    persist = commands.add_parser("persist-auth")
    persist.add_argument("--run-dir", required=True)
    persist.add_argument("--output", required=True)

    aggregate = commands.add_parser("aggregate")
    aggregate.add_argument("--suite", required=True)
    aggregate.add_argument("--artifacts", required=True)
    aggregate.add_argument("--output", required=True)
    aggregate.add_argument("--pr-number", required=True, type=int)
    aggregate.add_argument("--head-sha", required=True)
    aggregate.add_argument("--run-url", required=True)
    aggregate.add_argument("--blocked-reason")
    aggregate.add_argument("--error-reason")

    verify = commands.add_parser("verify-publication")
    verify.add_argument("--report", required=True)
    verify.add_argument("--publication", required=True)

    render = commands.add_parser("render")
    render.add_argument("--report", required=True)
    render.add_argument("--run-url", required=True)
    render.add_argument("--evidence-base-url", required=True)
    render.add_argument("--output", required=True)
    return root


def main(argv: Sequence[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        if args.command == "matrix":
            suite = load_suite(args.suite)
            print(json.dumps({"include": [{"test_id": case.id} for case in suite.tests]}))
            return 0

        if args.command == "preflight":
            run_preflight(_case(args.suite, args.test_id), Path(args.cwd).resolve())
            return 0

        if args.command == "run":
            result = launch_case(
                _case(args.suite, args.test_id),
                load_codex_auth(args.auth_file),
                args.run_dir,
                codex_bin=args.codex_bin,
            )
            return 0 if result["verdict"] == "pass" else 1

        if args.command == "pixel-probe":
            result = launch_case(
                PIXEL_PROBE_CASE,
                load_codex_auth(args.auth_file),
                args.run_dir,
                codex_bin=args.codex_bin,
                probe_fixture=PIXEL_PROBE_FIXTURE,
            )
            if result["verdict"] != "fail":
                raise LaunchError("native pixel probe must submit fail for the red screenshot")
            return 0

        if args.command == "export-auth":
            export_auth_state(args.input, args.output, os.environ.get("CODEX_AUTH_STATE_KEY", ""))
            return 0
        if args.command == "decrypt-auth":
            decrypt_auth_state(args.input, args.output, os.environ.get("CODEX_AUTH_STATE_KEY", ""))
            return 0
        if args.command == "persist-auth":
            persist_rotated_auth_state(
                args.run_dir, args.output, os.environ.get("CODEX_AUTH_STATE_KEY", "")
            )
            return 0

        if args.command == "aggregate":
            output = Path(args.output)
            output.mkdir(parents=True, exist_ok=True)
            (output / "publication").mkdir(parents=True, exist_ok=True)
            if args.blocked_reason:
                report = {
                    "schema_version": 1,
                    "overall": "blocked",
                    "pr_number": args.pr_number,
                    "head_sha": args.head_sha,
                    "blocked_reason": args.blocked_reason[:500],
                    "tests": [],
                }
                (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
                (output / "comment.md").write_text(render_markdown(report, args.run_url))
                return 2
            if args.error_reason:
                report = {
                    "schema_version": 1,
                    "overall": "error",
                    "error_reason": args.error_reason[:500],
                    "pr_number": args.pr_number,
                    "head_sha": args.head_sha,
                    "tests": [],
                }
                (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
                (output / "comment.md").write_text(render_markdown(report, args.run_url))
                return 1
            suite = load_suite(args.suite)
            report = aggregate_results(
                suite,
                args.artifacts,
                publication=Path(args.output) / "publication",
                pr_number=args.pr_number,
                head_sha=args.head_sha,
            )
            (output / "report.json").write_text(
                json.dumps(report, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            (output / "comment.md").write_text(
                render_markdown(report, args.run_url), encoding="utf-8"
            )
            return 0 if report["overall"] == "pass" else 2 if report["overall"] == "blocked" else 1

        if args.command == "verify-publication":
            report = json.loads(Path(args.report).read_text(encoding="utf-8"))
            if not isinstance(report, dict):
                raise ValueError("report must be a JSON object")
            verify_publication(report, args.publication)
            return 0

        if args.command == "render":
            report = json.loads(Path(args.report).read_text(encoding="utf-8"))
            if not isinstance(report, dict):
                raise ValueError("report must be a JSON object")
            Path(args.output).write_text(
                render_markdown(
                    report,
                    args.run_url,
                    evidence_base_url=args.evidence_base_url,
                ),
                encoding="utf-8",
            )
            return 0
    except (
        ConfigError,
        AuthStateError,
        LaunchError,
        OSError,
        TypeError,
        ValueError,
        json.JSONDecodeError,
    ) as exc:
        print(f"manual-test: {exc}", file=sys.stderr)
        return 1
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

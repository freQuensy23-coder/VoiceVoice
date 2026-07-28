import re
from pathlib import Path

import yaml

ROOT = Path(__file__).parents[2]
WORKFLOWS = ROOT / ".github/workflows"


def load(name: str) -> tuple[str, dict]:
    text = (WORKFLOWS / name).read_text(encoding="utf-8")
    # PyYAML 1.1 treats `on` as boolean; text assertions cover triggers.
    return text, yaml.safe_load(text)


def test_untrusted_pr_workflow_has_no_credentials_or_agent_execution() -> None:
    text, workflow = load("manual-test-build.yml")

    assert "pull_request:" in text
    assert "pull_request_target" not in text
    assert "CODEX_AUTH" not in text
    assert "openai-codex" not in text
    assert "@openai/codex" not in text
    assert workflow["permissions"] == {"contents": "read"}
    assert "manual_test/tests.yaml" in text
    assert "name: manual-test-suite" in text


def test_privileged_agent_workflow_runs_only_after_trust_gate_from_base_code() -> None:
    text, workflow = load("codex-manual-tests.yml")

    assert "workflow_run:" in text
    assert "pull_request:" not in text
    assert "pull_request_target" not in text
    assert "author_association" in text
    assert "head.repo.full_name" in text
    assert text.count("'.state'") >= 3
    assert text.count("'.merged'") >= 3
    assert "github.event.repository.default_branch" in text
    assert "github.event.workflow_run.head_sha" not in re.sub(r"gh api.*", "", text)
    assert workflow["permissions"] == {"actions": "read", "contents": "read"}
    assert "needs: [authorize, prepare]" in text
    assert "name: manual-test-suite" in text
    assert "path: ${{ runner.temp }}/candidate-suite" in text
    assert '--suite "$RUNNER_TEMP/candidate-suite/tests.yaml"' in text
    assert "Build matrix from candidate YAML with trusted parser" in text


def test_every_agent_matrix_job_gets_fresh_emulator_and_one_codex_session() -> None:
    text, _ = load("codex-manual-tests.yml")

    assert "matrix: ${{ fromJSON(needs.prepare.outputs.matrix) }}" in text
    assert "reactivecircus/android-emulator-runner" in text.lower()
    assert "name: Enable KVM for the fresh emulator" in text
    assert "manual-test run" in text
    assert "--provider openai-codex" not in text  # launcher owns immutable provider selection
    assert "OPENAI_API_KEY" not in text
    assert "CODEX_AUTH_JSON" not in text
    assert "CODEX_AUTH_STATE_KEY" in text
    assert "CODEX_AUTH_SEED" in text
    assert "codex-auth-state" in text
    assert "manual-test decrypt-auth" in text
    assert "--auth-file" in text
    assert "manual-test persist-auth" in text
    assert "manual-test preflight" in text
    manual_text = text[text.index("\n  manual:") :]
    assert manual_text.index("manual-test preflight") < manual_text.index(
        "manual-test decrypt-auth"
    )
    assert manual_text.index(
        'gh api "repos/$GITHUB_REPOSITORY/pulls/$PR_NUMBER"'
    ) < manual_text.index("manual-test decrypt-auth")
    assert "max-parallel: 1" in text
    assert "group: codex-auth-state" in text
    assert text.count("candidate/manual-test-suite/tests.yaml") >= 3


def test_credential_workflow_always_rotates_and_pushes_ciphertext_only() -> None:
    text, workflow = load("codex-manual-tests.yml")

    manual = workflow["jobs"]["manual"]
    assert manual["permissions"]["contents"] == "write"
    assert "if: always()" in text
    assert "auth-state.fernet" in text
    assert "refs/heads/codex-auth-state" in text
    persistence = text[
        text.index("name: Persist encrypted Codex auth state") : text.index("name: Upload evidence")
    ]
    assert 'if [[ ! -f "reports/$TEST_ID/codex-home/auth.json" ]]' in persistence
    assert "evidence/" not in persistence


def test_candidate_preflight_waits_for_android_package_manager() -> None:
    suite = (ROOT / "manual_test/tests.yaml").read_text(encoding="utf-8")

    assert "until cmd package list packages" in suite
    assert suite.index("until cmd package list packages") < suite.index(
        "adb install -r app-debug.apk"
    )


def test_publisher_is_workflow_run_only_and_never_executes_artifacts() -> None:
    text, workflow = load("codex-manual-test-publisher.yml")

    assert "workflow_run:" in text
    assert "pull_request:" not in text
    assert "pull_request_target" not in text
    assert "github.event.repository.default_branch" in text
    assert workflow["permissions"] == {"actions": "read", "contents": "read"}
    assert "comment.md" in text
    assert "source " not in text
    assert "chmod +x" not in text
    assert "contents: write" in text
    assert "manual-test-evidence" in text
    assert "raw.githubusercontent.com" in text
    assert "verify-publication" in text
    assert "manual-test render" in text
    assert "overall" in text
    assert "overall" in text
    assert "report_head_sha" in text
    assert "current_head_sha" in text
    assert text.index("current_head_sha") < text.index("push origin")


def test_credentialed_workflow_runs_exact_pixel_probe_before_app_matrix() -> None:
    text, workflow = load("codex-manual-tests.yml")

    assert "pixel-probe" in workflow["jobs"]
    assert workflow["jobs"]["manual"]["needs"] == ["authorize", "prepare", "pixel-probe"]
    assert "manual-test pixel-probe" in text
    assert "Persist pixel-probe rotated OAuth state" in text


def test_every_downloaded_artifact_tree_rejects_symlinks() -> None:
    for name in ("codex-manual-tests.yml", "codex-manual-test-publisher.yml"):
        text, _ = load(name)
        assert "find " in text
        assert "-type l" in text


def test_aggregate_reports_even_when_build_or_prepare_failed() -> None:
    text, _ = load("codex-manual-tests.yml")

    assert "PREPARE_RESULT: ${{ needs.prepare.result }}" in text
    assert "continue-on-error: true" in text
    assert "--error-reason" in text
    assert "if-no-files-found: error" in text


def test_all_workflow_actions_are_pinned_and_checkouts_drop_credentials() -> None:
    for path in WORKFLOWS.glob("*.yml"):
        text = path.read_text(encoding="utf-8")
        for reference in re.findall(r"^\s*uses:\s*([^\s]+)", text, re.MULTILINE):
            assert re.search(r"@[0-9a-f]{40}$", reference), (
                f"unpinned action in {path.name}: {reference}"
            )
        if "actions/checkout@" in text:
            workflow = yaml.safe_load(text)
            for job in workflow["jobs"].values():
                for step in job.get("steps", []):
                    if str(step.get("uses", "")).startswith("actions/checkout@"):
                        assert step.get("with", {}).get("persist-credentials") is False


def test_workflows_contain_no_test_specific_ui_assertions() -> None:
    for path in WORKFLOWS.glob("*.yml"):
        text = path.read_text(encoding="utf-8")
        assert "recording-toggle" not in text
        assert "Recording state" not in text

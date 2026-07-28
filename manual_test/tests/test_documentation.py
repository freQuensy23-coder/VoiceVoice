from pathlib import Path

ROOT = Path(__file__).parents[2]


def test_manual_harness_readme_documents_runtime_security_and_boundary() -> None:
    text = (ROOT / "manual_test/README.md").read_text(encoding="utf-8")

    assert "@openai/codex@0.144.6" in text
    assert "gpt-5.6-sol" in text
    assert "one standalone Codex CLI session" in text
    assert "no OpenAI API key" in text
    assert "fresh emulator" in text
    assert "untrusted" in text
    assert "Android SDK" in text
    assert "CODEX_AUTH_JSON" not in text
    assert "CODEX_AUTH_STATE_KEY" in text
    assert "CODEX_AUTH_SEED" in text
    assert "manual-test export-auth --input" in text
    assert "manual-test decrypt-auth" in text
    assert "manual-test persist-auth" in text
    assert "codex-auth-state" in text
    assert "manual-test-evidence" in text
    assert "immutable" in text


def test_root_readme_links_manual_testing_harness() -> None:
    text = (ROOT / "README.md").read_text(encoding="utf-8")
    assert "manual_test/README.md" in text


def test_runtime_artifacts_and_isolated_homes_are_ignored() -> None:
    text = (ROOT / ".gitignore").read_text(encoding="utf-8")
    assert "manual_test/.venv/" in text
    assert ".manual-test-input/" in text
    assert "manual_test/reports/" in text

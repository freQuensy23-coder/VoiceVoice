from __future__ import annotations

import json
from pathlib import Path

import pytest
from cryptography.fernet import Fernet

from manual_test_agent.auth_state import (
    AuthStateError,
    decrypt_auth_state,
    encrypt_auth_state,
    export_auth_state,
    persist_rotated_auth_state,
)

AUTH = {
    "OPENAI_API_KEY": None,
    "auth_mode": "chatgpt",
    "tokens": {
        "id_token": "id",
        "access_token": "access",
        "refresh_token": "single-use",
        "account_id": "account",
    },
    "last_refresh": "2026-07-28T00:00:00Z",
}


def test_encrypt_decrypt_round_trip_requires_strict_sanitized_auth(tmp_path: Path) -> None:
    key = Fernet.generate_key().decode()
    source = tmp_path / "auth.json"
    encrypted = tmp_path / "auth.fernet"
    restored = tmp_path / "restored.json"
    source.write_text(json.dumps(AUTH), encoding="utf-8")

    encrypt_auth_state(source, encrypted, key)
    assert b"single-use" not in encrypted.read_bytes()
    decrypt_auth_state(encrypted, restored, key)

    assert json.loads(restored.read_text(encoding="utf-8")) == AUTH
    assert restored.stat().st_mode & 0o777 == 0o600


def test_encrypt_rejects_unsanitized_auth_before_writing_ciphertext(
    tmp_path: Path,
) -> None:
    source = tmp_path / "auth.json"
    output = tmp_path / "auth.fernet"
    source.write_text(json.dumps({**AUTH, "other": "must not survive"}))

    with pytest.raises(AuthStateError, match="sanitized"):
        encrypt_auth_state(source, output, Fernet.generate_key().decode())

    assert not output.exists()


def test_export_sanitizes_real_store_and_encrypts_without_plaintext_output(
    tmp_path: Path,
) -> None:
    key = Fernet.generate_key().decode()
    source = tmp_path / "real-auth.json"
    seed = tmp_path / "seed.fernet"
    restored = tmp_path / "restored.json"
    source.write_text(
        json.dumps(
            {
                **AUTH,
            }
        )
    )

    export_auth_state(source, seed, key)
    decrypt_auth_state(seed, restored, key)

    assert json.loads(restored.read_text()) == AUTH
    assert not (tmp_path / "codex-auth.json").exists()


def test_persist_encrypts_only_rotated_run_home_auth(tmp_path: Path) -> None:
    key = Fernet.generate_key().decode()
    rotated = {**AUTH, "last_refresh": "later"}
    auth_path = tmp_path / "run/codex-home/auth.json"
    auth_path.parent.mkdir(parents=True)
    auth_path.write_text(json.dumps(rotated))
    encrypted = tmp_path / "next.fernet"
    restored = tmp_path / "restored.json"

    persist_rotated_auth_state(tmp_path / "run", encrypted, key)
    decrypt_auth_state(encrypted, restored, key)

    assert json.loads(restored.read_text()) == rotated


def test_decrypt_rejects_wrong_key_and_leaves_no_plaintext(tmp_path: Path) -> None:
    source = tmp_path / "auth.json"
    encrypted = tmp_path / "auth.fernet"
    output = tmp_path / "restored.json"
    source.write_text(json.dumps(AUTH))
    encrypt_auth_state(source, encrypted, Fernet.generate_key().decode())

    with pytest.raises(AuthStateError, match="decrypt"):
        decrypt_auth_state(encrypted, output, Fernet.generate_key().decode())

    assert not output.exists()

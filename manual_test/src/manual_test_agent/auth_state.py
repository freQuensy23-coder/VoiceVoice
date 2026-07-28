from __future__ import annotations

import json
import os
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from cryptography.fernet import Fernet, InvalidToken

_MAX_AUTH_BYTES = 2_000_000
_ROOT_KEYS = {"OPENAI_API_KEY", "auth_mode", "tokens", "last_refresh"}
_TOKEN_KEYS = {"id_token", "access_token", "refresh_token", "account_id"}


class AuthStateError(RuntimeError):
    """A standalone Codex CLI auth state failed validation or persistence."""


def validate_codex_auth(auth: Mapping[str, Any]) -> dict[str, Any]:
    tokens = auth.get("tokens")
    if (
        set(auth) != _ROOT_KEYS
        or auth.get("OPENAI_API_KEY") is not None
        or auth.get("auth_mode") != "chatgpt"
        or not isinstance(tokens, dict)
        or set(tokens) != _TOKEN_KEYS
        or not all(isinstance(tokens[key], str) and tokens[key] for key in _TOKEN_KEYS)
        or not isinstance(auth.get("last_refresh"), str)
        or not auth["last_refresh"].strip()
    ):
        raise AuthStateError("auth.json must be strict sanitized standalone Codex CLI OAuth state")
    return json.loads(json.dumps(dict(auth)))


def _read(path: str | Path, label: str) -> bytes:
    source = Path(path)
    if source.is_symlink() or not source.is_file():
        raise AuthStateError(f"{label} is missing or not a regular file")
    if not 0 < source.stat().st_size <= _MAX_AUTH_BYTES:
        raise AuthStateError(f"{label} is empty or oversized")
    return source.read_bytes()


def _decode(data: bytes, label: str) -> dict[str, Any]:
    try:
        value = json.loads(data)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise AuthStateError(f"{label} is not valid JSON") from exc
    if not isinstance(value, dict):
        raise AuthStateError(f"{label} must be a JSON object")
    return validate_codex_auth(value)


def load_codex_auth(path: str | Path) -> dict[str, Any]:
    return _decode(_read(path, "auth file"), "auth file")


def _fernet(key: str) -> Fernet:
    try:
        return Fernet(key.encode("ascii"))
    except (AttributeError, UnicodeEncodeError, ValueError) as exc:
        raise AuthStateError("CODEX_AUTH_STATE_KEY is not a valid Fernet key") from exc


def _write_exclusive(path: str | Path, data: bytes) -> None:
    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    try:
        descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError as exc:
        raise AuthStateError(f"refusing to overwrite output: {destination}") from exc
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(data)
    except BaseException:
        destination.unlink(missing_ok=True)
        raise


def _canonical(auth: Mapping[str, Any]) -> bytes:
    return (
        json.dumps(validate_codex_auth(auth), sort_keys=True, separators=(",", ":")) + "\n"
    ).encode()


def encrypt_auth_state(input_path: str | Path, output_path: str | Path, key: str) -> None:
    _write_exclusive(
        output_path, _fernet(key).encrypt(_canonical(load_codex_auth(input_path))) + b"\n"
    )


def decrypt_auth_state(input_path: str | Path, output_path: str | Path, key: str) -> None:
    try:
        plaintext = _fernet(key).decrypt(_read(input_path, "encrypted auth state").strip())
    except InvalidToken as exc:
        raise AuthStateError("could not decrypt auth state") from exc
    _write_exclusive(output_path, _canonical(_decode(plaintext, "decrypted auth state")))


def export_auth_state(input_path: str | Path, output_path: str | Path, key: str) -> None:
    encrypt_auth_state(input_path, output_path, key)


def persist_rotated_auth_state(run_dir: str | Path, output_path: str | Path, key: str) -> None:
    encrypt_auth_state(Path(run_dir).resolve() / "codex-home" / "auth.json", output_path, key)

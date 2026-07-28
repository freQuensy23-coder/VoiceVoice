from __future__ import annotations

import hashlib
import struct
import xml.etree.ElementTree as ET
import zlib
from dataclasses import dataclass
from pathlib import Path

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
MAX_PNG_BYTES = 20_000_000
MAX_HIERARCHY_BYTES = 5_000_000
MAX_DIMENSION = 32_768


class IntegrityError(ValueError):
    """An inert evidence file failed generic integrity validation."""


@dataclass(frozen=True)
class PngInfo:
    size: int
    width: int
    height: int
    sha256: str


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def validate_png(data: bytes) -> PngInfo:
    if not data.startswith(PNG_SIGNATURE):
        raise IntegrityError("evidence is not a PNG")
    if len(data) > MAX_PNG_BYTES:
        raise IntegrityError("PNG evidence is oversized")

    offset = len(PNG_SIGNATURE)
    first = True
    width = height = 0
    has_idat = False
    has_iend = False
    while offset < len(data):
        if offset + 12 > len(data):
            raise IntegrityError("PNG has a truncated chunk")
        length = struct.unpack(">I", data[offset : offset + 4])[0]
        kind = data[offset + 4 : offset + 8]
        end = offset + 12 + length
        if end > len(data):
            raise IntegrityError("PNG has an invalid chunk size")
        payload = data[offset + 8 : offset + 8 + length]
        expected_crc = struct.unpack(">I", data[offset + 8 + length : end])[0]
        if zlib.crc32(kind + payload) & 0xFFFFFFFF != expected_crc:
            raise IntegrityError("PNG chunk CRC is invalid")
        if first:
            if kind != b"IHDR" or length != 13:
                raise IntegrityError("PNG must begin with IHDR")
            width, height = struct.unpack(">II", payload[:8])
            if not 0 < width <= MAX_DIMENSION or not 0 < height <= MAX_DIMENSION:
                raise IntegrityError("PNG dimensions are invalid")
            if payload[10] != 0 or payload[11] != 0 or payload[12] not in {0, 1}:
                raise IntegrityError("PNG header is invalid")
            first = False
        elif kind == b"IHDR":
            raise IntegrityError("PNG contains multiple IHDR chunks")
        if kind == b"IDAT":
            has_idat = True
        if kind == b"IEND":
            if length != 0 or end != len(data):
                raise IntegrityError("PNG IEND is invalid")
            has_iend = True
            offset = end
            break
        offset = end

    if first or not has_idat or not has_iend or offset != len(data):
        raise IntegrityError("PNG is incomplete")
    return PngInfo(len(data), width, height, digest(data))


def validate_hierarchy(data: bytes) -> tuple[str, list[str], str]:
    if not data:
        raise IntegrityError("UI hierarchy is empty")
    if len(data) > MAX_HIERARCHY_BYTES:
        raise IntegrityError("UI hierarchy is oversized")
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise IntegrityError("UI hierarchy is not UTF-8") from exc
    lowered = text.lower()
    if "<!doctype" in lowered or "<!entity" in lowered:
        raise IntegrityError("UI hierarchy declarations are forbidden")
    try:
        root = ET.fromstring(text)
    except ET.ParseError as exc:
        raise IntegrityError("UI hierarchy is not valid XML") from exc

    values: list[str] = []
    seen: set[str] = set()
    for element in root.iter():
        for attribute in ("text", "content-desc"):
            value = element.attrib.get(attribute, "").strip()
            if value and value not in seen:
                seen.add(value)
                values.append(value[:500])
                if len(values) == 100:
                    return text, values, digest(data)
    return text, values, digest(data)


def read_validated_evidence(
    png_path: Path,
    hierarchy_path: Path,
    *,
    expected_png_sha256: str,
    expected_hierarchy_sha256: str,
) -> tuple[bytes, bytes, PngInfo, list[str]]:
    for path, suffix in ((png_path, ".png"), (hierarchy_path, ".xml")):
        if path.suffix != suffix or path.is_symlink() or not path.is_file():
            raise IntegrityError(f"invalid evidence file: {path.name}")
    png = png_path.read_bytes()
    hierarchy = hierarchy_path.read_bytes()
    png_info = validate_png(png)
    _, ui_text, hierarchy_hash = validate_hierarchy(hierarchy)
    if png_info.sha256 != expected_png_sha256:
        raise IntegrityError("PNG integrity hash mismatch")
    if hierarchy_hash != expected_hierarchy_sha256:
        raise IntegrityError("UI hierarchy integrity hash mismatch")
    return png, hierarchy, png_info, ui_text

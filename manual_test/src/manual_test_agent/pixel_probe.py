from __future__ import annotations

import json
import struct
import zlib
from pathlib import Path

from .android_mcp.backend import Snapshot
from .config import TestCase

FIXTURE = Path(__file__).with_name("pixel_probe.json")
CASE = TestCase(
    id="native-pixel-probe",
    name="Native CLI pixel visibility probe",
    description="Determine whether the solid screenshot is blue.",
    preflight=(),
    checklist=("The screenshot is solid blue.",),
    agent_instruction=(
        "Judge the attached screenshot pixels, capture and inspect one evidence artifact, "
        "then submit the evidence-backed checklist verdict."
    ),
    evidence=("Capture and inspect the solid-color screenshot.",),
)


class ProbeDevice:
    def __init__(self, fixture: str | Path) -> None:
        value = json.loads(Path(fixture).read_text(encoding="utf-8"))
        width, height = value["width"], value["height"]
        rgb = bytes(value["rgb"])
        raw = b"".join(b"\0" + rgb * width for _ in range(height))

        def chunk(kind: bytes, data: bytes) -> bytes:
            return (
                struct.pack(">I", len(data))
                + kind
                + data
                + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
            )

        png = (
            b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b"")
        )
        self._snapshot = Snapshot(png=png, hierarchy=value["hierarchy"])

    def snapshot(self) -> Snapshot:
        return self._snapshot

    def tap(self, x: int, y: int) -> None:
        return None

    def tap_text(self, text: str) -> tuple[int, int]:
        return (128, 128)

    def type_text(self, text: str) -> None:
        return None

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int) -> None:
        return None

    def press(self, key: str) -> None:
        return None

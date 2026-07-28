from __future__ import annotations

import re
import subprocess
from collections.abc import Callable
from dataclasses import dataclass
from xml.etree import ElementTree

_BOUNDS = re.compile(r"^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$")
_ALLOWED_KEYS = frozenset(
    {
        "BACK",
        "ENTER",
        "ESCAPE",
        "HOME",
        "TAB",
        "DPAD_UP",
        "DPAD_DOWN",
        "DPAD_LEFT",
        "DPAD_RIGHT",
        "DPAD_CENTER",
    }
)


class AdbError(RuntimeError):
    """A fixed Android bridge operation failed."""


@dataclass(frozen=True)
class Snapshot:
    png: bytes
    hierarchy: str


def _subprocess_runner(
    args: list[str], *, input: bytes | None = None
) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        args,
        input=input,
        capture_output=True,
        check=False,
    )


class AdbDevice:
    def __init__(
        self,
        serial: str | None = None,
        adb: str = "adb",
        runner: Callable[..., subprocess.CompletedProcess[bytes]] = _subprocess_runner,
    ) -> None:
        if serial is not None and (not serial.strip() or any(c.isspace() for c in serial)):
            raise ValueError("invalid Android serial")
        self.serial = serial
        self.adb = adb
        self._runner = runner

    def _run(self, *args: str) -> bytes:
        argv = [self.adb]
        if self.serial:
            argv += ["-s", self.serial]
        argv.extend(args)
        completed = self._runner(argv, input=None)
        if completed.returncode != 0:
            detail = completed.stderr.decode("utf-8", errors="replace").strip()
            raise AdbError(detail or f"adb exited {completed.returncode}")
        return completed.stdout

    @staticmethod
    def _coordinate(value: int) -> str:
        if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= 100_000:
            raise ValueError("coordinates must be integers from 0 through 100000")
        return str(value)

    def snapshot(self) -> Snapshot:
        png = self._run("exec-out", "screencap", "-p")
        if not png:
            raise AdbError("screencap returned no image")
        self._run("shell", "uiautomator", "dump", "/sdcard/window.xml")
        hierarchy = self._run("exec-out", "cat", "/sdcard/window.xml").decode(
            "utf-8", errors="replace"
        )
        if not hierarchy.strip():
            raise AdbError("UI hierarchy is empty")
        return Snapshot(png=png, hierarchy=hierarchy)

    def tap(self, x: int, y: int) -> None:
        self._run("shell", "input", "tap", self._coordinate(x), self._coordinate(y))

    def tap_text(self, text: str) -> tuple[int, int]:
        if not isinstance(text, str) or not text:
            raise ValueError("text must be non-empty")
        snapshot = self.snapshot()
        try:
            root = ElementTree.fromstring(snapshot.hierarchy)
        except ElementTree.ParseError as exc:
            raise AdbError(f"invalid UI hierarchy: {exc}") from exc
        matches = [node for node in root.iter() if node.attrib.get("text") == text]
        if not matches:
            raise AdbError(f"exact visible text not found: {text!r}")
        if len(matches) != 1:
            raise AdbError(f"exact visible text is ambiguous: {text!r}")
        match = _BOUNDS.fullmatch(matches[0].attrib.get("bounds", ""))
        if not match:
            raise AdbError("matched UI node has invalid bounds")
        left, top, right, bottom = map(int, match.groups())
        if right <= left or bottom <= top:
            raise AdbError("matched UI node has empty bounds")
        point = ((left + right) // 2, (top + bottom) // 2)
        self.tap(*point)
        return point

    def type_text(self, text: str) -> None:
        if not isinstance(text, str) or not text or "\x00" in text or "\n" in text or "\r" in text:
            raise ValueError("text must be one non-empty line")
        encoded = text.replace("%", "\\%").replace(" ", "%s")
        self._run("shell", "input", "text", encoded)

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> None:
        if (
            isinstance(duration_ms, bool)
            or not isinstance(duration_ms, int)
            or not 1 <= duration_ms <= 5_000
        ):
            raise ValueError("duration_ms must be from 1 through 5000")
        self._run(
            "shell",
            "input",
            "swipe",
            self._coordinate(x1),
            self._coordinate(y1),
            self._coordinate(x2),
            self._coordinate(y2),
            str(duration_ms),
        )

    def press(self, key: str) -> None:
        if key not in _ALLOWED_KEYS:
            raise ValueError(f"allowed key required: {sorted(_ALLOWED_KEYS)}")
        self._run("shell", "input", "keyevent", key)

from __future__ import annotations

import subprocess

import pytest

from manual_test_agent.android_mcp.backend import AdbDevice, AdbError, Snapshot

UI = b"""<?xml version="1.0" encoding="UTF-8"?>
<hierarchy><node text="Continue" bounds="[10,20][110,80]"/></hierarchy>"""


class FakeRun:
    def __init__(self) -> None:
        self.calls: list[tuple[list[str], bytes | None]] = []

    def __call__(
        self, args: list[str], *, input: bytes | None = None
    ) -> subprocess.CompletedProcess[bytes]:
        self.calls.append((args, input))
        if args[-3:] == ["exec-out", "screencap", "-p"]:
            return subprocess.CompletedProcess(args, 0, b"PNG", b"")
        if args[-3:] == ["exec-out", "cat", "/sdcard/window.xml"]:
            return subprocess.CompletedProcess(args, 0, UI, b"")
        return subprocess.CompletedProcess(args, 0, b"ok", b"")


def test_snapshot_uses_fixed_adb_argv() -> None:
    run = FakeRun()
    device = AdbDevice(serial="emulator-5554", adb="/sdk/adb", runner=run)

    snapshot = device.snapshot()

    assert snapshot == Snapshot(png=b"PNG", hierarchy=UI.decode())
    assert run.calls == [
        (["/sdk/adb", "-s", "emulator-5554", "exec-out", "screencap", "-p"], None),
        (
            [
                "/sdk/adb",
                "-s",
                "emulator-5554",
                "shell",
                "uiautomator",
                "dump",
                "/sdcard/window.xml",
            ],
            None,
        ),
        (
            [
                "/sdk/adb",
                "-s",
                "emulator-5554",
                "exec-out",
                "cat",
                "/sdcard/window.xml",
            ],
            None,
        ),
    ]


def test_actions_are_restricted_to_typed_adb_operations() -> None:
    run = FakeRun()
    device = AdbDevice(serial="serial", runner=run)

    device.tap(12, 34)
    device.tap_text("Continue")
    device.type_text("hello world")
    device.swipe(1, 2, 3, 4, 250)
    device.press("BACK")

    argv = [call[0][3:] for call in run.calls]
    assert ["shell", "input", "tap", "12", "34"] in argv
    assert ["shell", "input", "tap", "60", "50"] in argv
    assert ["shell", "input", "text", "hello%sworld"] in argv
    assert ["shell", "input", "swipe", "1", "2", "3", "4", "250"] in argv
    assert ["shell", "input", "keyevent", "BACK"] in argv


@pytest.mark.parametrize("key", ["SHELL", "POWER", "VOLUME_UP", "A;rm -rf /"])
def test_press_rejects_unapproved_keys(key: str) -> None:
    with pytest.raises(ValueError, match="allowed key"):
        AdbDevice(runner=FakeRun()).press(key)


def test_tap_text_requires_one_exact_visible_match() -> None:
    run = FakeRun()
    device = AdbDevice(runner=run)
    with pytest.raises(AdbError, match="not found"):
        device.tap_text("continue")


def test_adb_failure_does_not_fall_back_to_shell() -> None:
    def fail(args: list[str], *, input: bytes | None = None) -> subprocess.CompletedProcess[bytes]:
        return subprocess.CompletedProcess(args, 1, b"", b"device offline")

    with pytest.raises(AdbError, match="device offline"):
        AdbDevice(runner=fail).tap(1, 2)

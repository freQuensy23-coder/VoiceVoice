import json
import re
from pathlib import Path

from mcp.types import TextContent

from manual_test_agent.android_mcp.backend import Snapshot
from manual_test_agent.android_mcp.server import _snapshot_content
from manual_test_agent.evidence import validate_png
from manual_test_agent.launcher import render_prompt
from manual_test_agent.pixel_probe import CASE, FIXTURE, ProbeDevice


def test_exact_runtime_probe_has_identical_nonvisual_data_and_distinct_native_pixels() -> None:
    fixture = json.loads(
        (Path(__file__).parent / "fixtures/pixel-probe.json").read_text(encoding="utf-8")
    )
    hierarchy = fixture["hierarchy"]
    rendered = []
    payloads = []

    for screen in fixture["screens"]:
        content = _snapshot_content(
            Snapshot(
                png=b"unused-by-text-result",
                hierarchy=hierarchy,
            ),
            "same label and path",
        )
        assert isinstance(content[0], TextContent)
        assert len(content) == 1
        rendered.append(content[0].text)
        payloads.append(screen["png_base64"])

    assert rendered[0] == rendered[1]
    assert payloads[0] != payloads[1]
    assert fixture["screens"][0]["answer"] != fixture["screens"][1]["answer"]


def test_executable_probe_fixture_is_256_square_red_with_nonvisual_blue_expectation() -> None:
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    info = validate_png(ProbeDevice(FIXTURE).snapshot().png)

    assert (info.width, info.height) == (256, 256)
    assert fixture["actual"] == "red"
    assert fixture["expected"] == "blue"
    assert fixture["path"] == "latest.png"
    assert fixture["rgb"] == [255, 0, 0]
    assert re.search(r"\bred\b", render_prompt(CASE).lower()) is None

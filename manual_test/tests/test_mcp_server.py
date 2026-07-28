import json
from base64 import b64decode
from pathlib import Path

import pytest
from mcp.server.fastmcp.exceptions import ToolError

from manual_test_agent.android_mcp.backend import Snapshot
from manual_test_agent.android_mcp.server import build_server
from manual_test_agent.android_mcp.state import RunState

PNG = b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
)


class Device:
    def snapshot(self) -> Snapshot:
        return Snapshot(png=PNG, hierarchy="<hierarchy/>")

    def tap(self, x: int, y: int) -> None:
        pass

    def tap_text(self, text: str) -> tuple[int, int]:
        return (1, 2)

    def type_text(self, text: str) -> None:
        pass

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int) -> None:
        pass

    def press(self, key: str) -> None:
        pass


@pytest.mark.asyncio
async def test_server_exposes_only_nine_android_testing_tools(tmp_path: Path) -> None:
    server = build_server(Device(), RunState(tmp_path, 1, 1))

    names = {tool.name for tool in await server.list_tools()}

    assert names == {
        "observe",
        "tap",
        "tap_text",
        "type",
        "swipe",
        "press",
        "capture",
        "inspect",
        "submit",
    }
    assert not any("shell" in name or "script" in name for name in names)


@pytest.mark.asyncio
async def test_tool_schema_constrains_evidence_ids_and_checklist_results(tmp_path: Path) -> None:
    server = build_server(Device(), RunState(tmp_path, 1, 1))
    tools = {tool.name: tool for tool in await server.list_tools()}

    capture_id = tools["capture"].inputSchema["properties"]["evidence_id"]
    assert capture_id["pattern"] == r"^[a-z0-9]+(?:-[a-z0-9]+)*$"
    submit = tools["submit"].inputSchema
    assert submit["properties"]["verdict"]["enum"] == ["pass", "fail"]
    checklist = submit["$defs"]["ChecklistResult"]
    assert checklist["additionalProperties"] is False
    assert set(checklist["required"]) == {"index", "status", "observation"}
    assert checklist["properties"]["index"]["minimum"] == 1


@pytest.mark.asyncio
async def test_server_exposes_no_resources_or_prompts(tmp_path: Path) -> None:
    server = build_server(Device(), RunState(tmp_path, 1, 1))

    assert await server.list_resources() == []
    assert await server.list_prompts() == []


@pytest.mark.asyncio
async def test_failed_tool_call_is_recorded(tmp_path: Path) -> None:
    class FailingDevice(Device):
        def press(self, key: str) -> None:
            raise ValueError("rejected key")

    server = build_server(FailingDevice(), RunState(tmp_path, 1, 1))

    with pytest.raises(ToolError, match="rejected key"):
        await server.call_tool("press", {"key": "POWER"})

    event = json.loads((tmp_path / "actions.jsonl").read_text())
    assert event["tool"] == "press"
    assert event["outcome"] == "error: ValueError: rejected key"


@pytest.mark.asyncio
async def test_observe_writes_latest_and_returns_stop_instruction(tmp_path: Path) -> None:
    server = build_server(Device(), RunState(tmp_path, 1, 1))

    result = await server.call_tool("observe", {})

    assert (tmp_path / "latest.png").read_bytes() == PNG
    assert (tmp_path / "latest.xml").read_text() == "<hierarchy/>"
    assert "stop this turn" in result[0][0].text.lower()

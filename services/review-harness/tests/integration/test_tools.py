import asyncio
import json

import pytest

from review_harness.infrastructure.persistence.store import RunStore
from tests.integration.test_workspaces import workspace_settings


async def test_readonly_tools_use_fixed_input_and_inject_identity(
    database, tmp_path, payload, workspaces
):
    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.runtime.tools import ToolGateway

    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    context = await workspaces.prepare(run, asyncio.Event())
    settings = workspace_settings(database, tmp_path)
    artifacts = ArtifactService(settings, database, store, workspaces)
    gateway = ToolGateway(settings, store, workspaces, artifacts)
    result = await gateway.call(
        context, "read_file", {"path": "src/Demo.java"}, asyncio.Event()
    )
    assert result.status == "OK"
    assert result.data["text"] == "class Demo {}\n"
    assert result.sourceRefs[0]["path"] == "src/Demo.java"
    assert result.artifactRef is None
    denied = await gateway.call(
        context,
        "read_file",
        {"path": "src/Demo.java", "runId": run.run_id},
        asyncio.Event(),
    )
    assert denied.error.code == "TOOL_ARGUMENT_INVALID"
    found = await gateway.call(
        context, "search_code", {"query": "Demo"}, asyncio.Event()
    )
    assert found.data["matches"][0]["line"] == 1
    diff = await gateway.call(context, "git_diff", {}, asyncio.Event())
    assert diff.data["text"].startswith("diff --git")


async def setup_gateway(
    database, tmp_path, payload, workspaces, *, allowed=None, settings=None
):
    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.runtime.tools import ToolGateway

    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    context = await workspaces.prepare(run, asyncio.Event())
    settings = settings or workspace_settings(database, tmp_path)
    artifacts = ArtifactService(settings, database, store, workspaces)
    return context, ToolGateway(settings, store, workspaces, artifacts, allowed=allowed)


@pytest.mark.parametrize(
    "arguments",
    [
        {"path": "../escape"},
        {"path": "src/Demo.java", "startLine": True},
        {"path": "src/Demo.java", "maxLines": 1001},
        {"path": "src/Demo.java", "root": "C:/secret"},
    ],
)
async def test_invalid_parameters_do_not_touch_file_jobs(
    database, tmp_path, payload, workspaces, arguments
):
    context, gateway = await setup_gateway(database, tmp_path, payload, workspaces)

    def forbidden(*args):
        raise AssertionError("file read must not occur")

    workspaces.read_bounded = forbidden
    result = await gateway.call(context, "read_file", arguments, asyncio.Event())
    assert result.error.code == "TOOL_ARGUMENT_INVALID"


async def test_hidden_tool_execution_rechecks_capabilities(
    database, tmp_path, payload, workspaces
):
    context, gateway = await setup_gateway(
        database, tmp_path, payload, workspaces, allowed={"glob_files"}
    )

    def forbidden(*args):
        raise AssertionError("file read must not occur")

    workspaces.read_bounded = forbidden
    result = await gateway.call(
        context, "read_file", {"path": "src/Demo.java"}, asyncio.Event()
    )
    assert result.error.code == "TOOL_ACCESS_DENIED"


async def test_spill_is_redacted_persistent_and_readable_without_recursive_spill(
    database, tmp_path, payload, workspaces
):
    from tests.snapshot_fixture import publish_snapshot

    secret = "DO_NOT_LEAK_CREDENTIAL"
    original = ('token="' + secret + '"\n' + "x" * 2000 + "\n") * 20
    # Fresh Snapshot identity avoids leftover fixture entries.
    from uuid import uuid4

    payload["snapshot"]["snapshotId"] = str(uuid4())
    publish_snapshot(
        tmp_path / "snapshots", payload, {"src/Demo.java": original.encode()}
    )
    context, gateway = await setup_gateway(database, tmp_path, payload, workspaces)
    result = await gateway.call(
        context, "read_file", {"path": "src/Demo.java"}, asyncio.Event()
    )
    assert result.status == "OK" and result.truncated and result.artifactRef
    assert secret not in result.model_dump_json()
    text, cursor = "", None
    while True:
        page = await gateway.call(
            context,
            "read_artifact",
            {"artifactId": result.artifactRef["artifactId"], "cursor": cursor},
            asyncio.Event(),
        )
        assert page.status == "OK" and page.artifactRef is None
        assert (
            len(page.model_dump_json().encode()) <= gateway.settings.tool_output_bytes
        )
        text += page.data["text"]
        cursor = page.data["nextCursor"]
        if cursor is None:
            break
    assert secret not in text
    stored = json.loads(text)
    assert "[REDACTED]" in stored["data"]["text"]
    assert stored["sourceRefs"][0] == result.sourceRefs[0]
    assert result.artifactRef["redactionState"] == "APPLIED"
    assert (context.workspace_root / "content/src/Demo.java").read_text() == original


async def test_manifest_whitelist_binary_skips_and_literal_search(
    database, tmp_path, payload, workspaces
):
    from uuid import uuid4

    from tests.snapshot_fixture import publish_snapshot

    payload["snapshot"]["snapshotId"] = str(uuid4())
    publish_snapshot(
        tmp_path / "snapshots",
        payload,
        {
            "a.txt": b"a.*b\n",
            "binary.bin": b"\x00\xff",
            "dir/z.txt": b"other\n",
        },
    )
    context, gateway = await setup_gateway(database, tmp_path, payload, workspaces)
    (context.workspace_root / "content/outside.txt").write_text("a.*b")
    missing = await gateway.call(
        context, "read_file", {"path": "outside.txt"}, asyncio.Event()
    )
    assert missing.error.code == "TOOL_SOURCE_NOT_FOUND"
    found = await gateway.call(
        context, "search_code", {"query": "a.*b"}, asyncio.Event()
    )
    assert [match["path"] for match in found.data["matches"]] == ["a.txt"]
    assert found.coverage == "PARTIAL" and found.data["skippedFiles"] == 1
    paths = await gateway.call(
        context, "glob_files", {"pattern": "**/*.txt"}, asyncio.Event()
    )
    assert [entry["path"] for entry in paths.data["files"]] == ["a.txt", "dir/z.txt"]


async def test_source_tampering_is_fatal_not_ignorable_tool_error(
    database, tmp_path, payload, workspaces
):
    from review_harness.domain.snapshot import WorkspaceError

    context, gateway = await setup_gateway(database, tmp_path, payload, workspaces)
    (context.workspace_root / "content/src/Demo.java").write_text("altered")
    with pytest.raises(WorkspaceError, match="SNAPSHOT_CONTENT_MISMATCH"):
        await gateway.call(
            context, "read_file", {"path": "src/Demo.java"}, asyncio.Event()
        )


async def test_fixed_diff_pages_are_budgeted_and_never_invoke_git(
    database, tmp_path, payload, workspaces, monkeypatch
):
    from tests.snapshot_fixture import digest

    source = tmp_path / "snapshots" / payload["snapshot"]["snapshotId"]
    diff = ('\x01"\\中😀\n' * 10000).encode()
    (source / "artifacts/changes.diff").write_bytes(diff)
    manifest = json.loads((source / "manifest.json").read_bytes())
    manifest["changes"]["diff"].update(byteSize=len(diff), diffSha256=digest(diff))
    raw = json.dumps(
        manifest, sort_keys=True, ensure_ascii=False, separators=(",", ":")
    ).encode()
    (source / "manifest.json").write_bytes(raw)
    payload["snapshot"]["manifestSha256"] = digest(raw)
    context, gateway = await setup_gateway(database, tmp_path, payload, workspaces)
    import subprocess

    def forbidden(*args, **kwargs):
        raise AssertionError("git_diff must not execute subprocesses")

    monkeypatch.setattr(subprocess, "Popen", forbidden)
    cursor, text = None, ""
    while True:
        page = await gateway.call(
            context, "git_diff", {"cursor": cursor}, asyncio.Event()
        )
        assert page.status == "OK" and page.artifactRef is None
        assert (
            len(page.model_dump_json().encode()) <= gateway.settings.tool_output_bytes
        )
        text += page.data["text"]
        cursor = page.data["nextCursor"]
        if cursor is None:
            break
    assert text == diff.decode()


async def test_scan_limit_and_empty_file_and_pre_cancel(
    database, tmp_path, payload, workspaces
):
    from uuid import uuid4

    from tests.snapshot_fixture import publish_snapshot

    payload["snapshot"]["snapshotId"] = str(uuid4())
    publish_snapshot(
        tmp_path / "snapshots", payload, {"a.txt": b"", "b.txt": b"find\n"}
    )
    settings = workspace_settings(database, tmp_path).model_copy(
        update={"tool_scan_bytes": 1}
    )
    context, gateway = await setup_gateway(
        database, tmp_path, payload, workspaces, settings=settings
    )
    empty = await gateway.call(context, "read_file", {"path": "a.txt"}, asyncio.Event())
    assert empty.status == "OK" and empty.data["text"] == ""
    scan = await gateway.call(
        context, "search_code", {"query": "find"}, asyncio.Event()
    )
    assert scan.coverage == "PARTIAL" and not scan.data["scanComplete"]
    signal = asyncio.Event()
    signal.set()
    cancelled = await gateway.call(context, "read_file", {"path": "b.txt"}, signal)
    assert cancelled.status == "CANCELLED"


async def test_multiline_literal_search_and_long_line_snippet_distinguish_scan_from_display(
    database, tmp_path, payload, workspaces
):
    from uuid import uuid4

    from tests.snapshot_fixture import publish_snapshot

    payload["snapshot"]["snapshotId"] = str(uuid4())
    publish_snapshot(
        tmp_path / "snapshots",
        payload,
        {
            "a.txt": b"alpha\nbeta\n",
            "long.txt": b"x" * 6000 + b"needle\n",
        },
    )
    context, gateway = await setup_gateway(database, tmp_path, payload, workspaces)
    multiline = await gateway.call(
        context, "search_code", {"query": "alpha\nbeta"}, asyncio.Event()
    )
    assert multiline.data["matches"][0]["line"] == 1
    assert multiline.data["matches"][0]["endLine"] == 2
    assert multiline.sourceRefs[0]["endLine"] == 2
    long_line = await gateway.call(
        context, "search_code", {"query": "needle"}, asyncio.Event()
    )
    assert long_line.data["scanComplete"]
    assert long_line.data["matches"][0]["snippetTruncated"]
    assert len(long_line.data["matches"][0]["text"]) == 4096
    assert long_line.coverage == "PARTIAL" and long_line.truncated

import asyncio
import os
from uuid import uuid4

import pytest

from review_harness.infrastructure.persistence.store import RunStore
from tests.integration.test_workspaces import workspace_settings


async def test_artifact_survives_workspace_cleanup_and_pages_exact_unicode(
    database, tmp_path, payload, workspaces
):
    from review_harness.infrastructure.artifacts import ArtifactService

    settings = workspace_settings(database, tmp_path)
    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    context = await workspaces.prepare(run, asyncio.Event())
    artifacts = ArtifactService(settings, database, store, workspaces)
    text = '中文😀"\\\n' * 10_000
    artifact = await artifacts.publish(
        context, text, "TOOL_OUTPUT", "read_file", asyncio.Event()
    )
    await workspaces.cleanup(run)
    await store.complete_success(run.run_id)
    cursor, parts = None, []
    while True:
        page = await artifacts.content(run.run_id, artifact.artifact_id, cursor)
        parts.append(page.text)
        assert len(page.model_dump_json().encode()) <= settings.tool_output_bytes
        cursor = page.nextCursor
        if cursor is None:
            break
    assert "".join(parts) == text


async def test_metadata_commit_ack_loss_is_confirmed_and_cross_run_cursor_rejected(
    database, tmp_path, payload, workspaces
):
    from review_harness.domain.model import HarnessError
    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.infrastructure.persistence.artifacts import ArtifactStore

    async def commit_then_fail(connection):
        await connection.commit()
        raise OSError("ack lost")

    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    context = await workspaces.prepare(run, asyncio.Event())
    service = ArtifactService(
        workspace_settings(database, tmp_path),
        database,
        store,
        workspaces,
        store=ArtifactStore(database, commit_then_fail),
    )
    artifact = await service.publish(
        context, "x" * 50000, "TOOL_OUTPUT", "search_code", asyncio.Event()
    )
    page = await service.content(run.run_id, artifact.artifact_id)
    assert page.hasMore and page.nextCursor
    for token in (page.nextCursor[:-1] + "!", "forged"):
        with pytest.raises(HarnessError, match="ARTIFACT_CURSOR_INVALID"):
            await service.content(run.run_id, artifact.artifact_id, token)
    with pytest.raises(HarnessError, match="ARTIFACT_NOT_FOUND"):
        await service.metadata(str(uuid4()), artifact.artifact_id)
    other = await service.publish(
        context, "y" * 50000, "TOOL_OUTPUT", "search_code", asyncio.Event()
    )
    with pytest.raises(HarnessError, match="ARTIFACT_CURSOR_INVALID"):
        await service.content(run.run_id, other.artifact_id, page.nextCursor)


async def test_tampered_artifact_is_marked_and_never_returned(
    database, tmp_path, payload, workspaces
):
    from review_harness.domain.model import HarnessError
    from review_harness.infrastructure.artifacts import ArtifactService

    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    context = await workspaces.prepare(run, asyncio.Event())
    settings = workspace_settings(database, tmp_path)
    service = ArtifactService(settings, database, store, workspaces)
    artifact = await service.publish(
        context, "private material", "SOURCE_EXCERPT", "read_file", asyncio.Event()
    )
    (settings.artifact_root / run.run_id / artifact.artifact_id / "content").write_text(
        "tampered"
    )
    with pytest.raises(HarnessError, match="ARTIFACT_INTEGRITY_FAILED"):
        await service.content(run.run_id, artifact.artifact_id)
    assert (
        await service.store.get(run.run_id, artifact.artifact_id)
    ).integrity_state == "CORRUPTED"


async def test_failed_metadata_keeps_orphan_charged_and_unreadable(
    database, tmp_path, payload, workspaces
):
    from review_harness.domain.model import HarnessError
    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.infrastructure.persistence.artifacts import ArtifactStore

    async def fail(connection):
        raise OSError("before commit")

    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    context = await workspaces.prepare(run, asyncio.Event())
    settings = workspace_settings(database, tmp_path).model_copy(
        update={"artifact_run_count": 1}
    )
    service = ArtifactService(
        settings, database, store, workspaces, store=ArtifactStore(database, fail)
    )
    with pytest.raises(HarnessError, match="ARTIFACT_STATE_WRITE_FAILED"):
        await service.publish(
            context, "orphan", "TOOL_OUTPUT", "read_file", asyncio.Event()
        )
    orphan = next((settings.artifact_root / run.run_id).iterdir())
    with pytest.raises(HarnessError, match="ARTIFACT_NOT_FOUND"):
        await service.metadata(run.run_id, orphan.name)
    with pytest.raises(HarnessError, match="ARTIFACT_LIMIT_EXCEEDED"):
        await service.publish(
            context, "retry", "TOOL_OUTPUT", "read_file", asyncio.Event()
        )
    assert (orphan / "content").read_text() == "orphan"


async def test_artifact_concurrent_quota_and_partial_capture(
    database, tmp_path, payload, workspaces
):
    from review_harness.domain.model import HarnessError
    from review_harness.infrastructure.artifacts import ArtifactService

    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    context = await workspaces.prepare(run, asyncio.Event())
    settings = workspace_settings(database, tmp_path).model_copy(
        update={
            "artifact_max_bytes": 64,
            "artifact_run_bytes": 64,
            "artifact_run_count": 1,
        }
    )
    service = ArtifactService(settings, database, store, workspaces)
    outcomes = await asyncio.gather(
        *[
            service.publish(
                context, "中" * 100, "TOOL_OUTPUT", "search_code", asyncio.Event()
            )
            for _ in range(2)
        ],
        return_exceptions=True,
    )
    success = [value for value in outcomes if not isinstance(value, Exception)]
    assert len(success) == 1
    assert success[0].coverage == "PARTIAL" and success[0].byte_size <= 64
    assert isinstance(
        next(value for value in outcomes if isinstance(value, Exception)), HarnessError
    )
    page = await service.content(run.run_id, success[0].artifact_id)
    assert page.coverage == "PARTIAL" and not page.hasMore


async def test_redaction_handles_long_lines_private_keys_and_json_credentials(
    database, tmp_path, payload, workspaces
):
    import json

    from review_harness.infrastructure.artifacts import ArtifactService

    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    context = await workspaces.prepare(run, asyncio.Event())
    service = ArtifactService(
        workspace_settings(database, tmp_path), database, store, workspaces
    )
    key = "PRIVATE_BODY_SENTINEL"
    secret = "TOKEN_BODY_SENTINEL"
    text = (
        "x" * 65530
        + "\n-----BEGIN PRIVATE KEY-----\n"
        + key
        + "\n-----END PRIVATE KEY-----\nBearer "
        + secret
    )
    artifact = await service.publish(
        context, text, "TOOL_OUTPUT", "read_file", asyncio.Event()
    )
    raw = (
        service.settings.artifact_root / run.run_id / artifact.artifact_id / "content"
    ).read_text()
    assert key not in raw and secret not in raw
    assert artifact.redaction_state == "APPLIED"
    structured = await service.publish(
        context,
        json.dumps({"token": secret, "safe": "normal text"}),
        "TOOL_OUTPUT",
        "read_file",
        asyncio.Event(),
        media_type="application/json",
    )
    page = await service.content(run.run_id, structured.artifact_id)
    assert json.loads(page.text) == {"token": "[REDACTED]", "safe": "normal text"}


async def test_publication_collision_never_replaces_existing_content(
    database, tmp_path, payload, workspaces
):
    from review_harness.domain.model import HarnessError
    from review_harness.infrastructure.artifacts import ArtifactService

    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    context = await workspaces.prepare(run, asyncio.Event())
    locations = []

    def collide(directory):
        locations.append(directory)
        (directory / "content").write_text("original owner")

    service = ArtifactService(
        workspace_settings(database, tmp_path),
        database,
        store,
        workspaces,
        publish_hook=collide,
    )
    with pytest.raises(HarnessError, match="ARTIFACT_WRITE_FAILED"):
        await service.publish(
            context, "new bytes", "TOOL_OUTPUT", "read_file", asyncio.Event()
        )
    assert (locations[0] / "content").read_text() == "original owner"
    with pytest.raises(HarnessError, match="ARTIFACT_NOT_FOUND"):
        await service.metadata(run.run_id, locations[0].name)


async def test_batch_two_database_requires_explicit_artifact_upgrade(tmp_path, payload):
    from review_harness.infrastructure.persistence.database import Database, migrate

    url = "sqlite+aiosqlite:///" + (tmp_path / "old-batch-two.sqlite").as_posix()
    await asyncio.to_thread(migrate, url, "0002_workspaces")
    db = Database(url)
    try:
        assert not await db.schema_ready()
        store = RunStore(db)
        before, _ = await store.create_run(payload)
        await db.close()
        await asyncio.to_thread(migrate, url)
        assert await db.schema_ready()
        assert await store.get_run(before.run_id) == before
    finally:
        await db.close()


@pytest.mark.parametrize(
    "alias", ["snapshots", "executions", "snapshots/nested", "executions/nested"]
)
def test_artifact_root_cannot_alias_or_nest_input_or_workspace(tmp_path, alias):
    from review_harness.config import Settings

    with pytest.raises(ValueError):
        Settings(
            internal_bearer_token="test-only-credential",
            snapshot_root=tmp_path / "snapshots",
            execution_root=tmp_path / "executions",
            artifact_root=tmp_path / alias,
        )


@pytest.mark.skipif(os.name != "nt", reason="Windows junction regression")
async def test_artifact_junction_replacement_cannot_read_foreign_tree(
    database, tmp_path, payload, workspaces
):
    import os
    import subprocess

    from review_harness.domain.model import HarnessError
    from review_harness.infrastructure.artifacts import ArtifactService

    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    context = await workspaces.prepare(run, asyncio.Event())
    service = ArtifactService(
        workspace_settings(database, tmp_path), database, store, workspaces
    )
    artifact = await service.publish(
        context, "original", "TOOL_OUTPUT", "read_file", asyncio.Event()
    )
    content = (
        service.settings.artifact_root / run.run_id / artifact.artifact_id / "content"
    )
    assert content.parent.parent.parent == service.settings.artifact_root
    content.unlink()
    foreign = tmp_path / "foreign-artifact-tree"
    foreign.mkdir()
    sentinel = foreign / "private.txt"
    sentinel.write_text("FOREIGN_MUST_REMAIN")
    process = subprocess.run(
        ["cmd", "/c", "mklink", "/J", str(content), str(foreign)], capture_output=True
    )
    assert process.returncode == 0
    try:
        with pytest.raises(HarnessError, match="ARTIFACT_INTEGRITY_FAILED"):
            await service.content(run.run_id, artifact.artifact_id)
        assert sentinel.read_text() == "FOREIGN_MUST_REMAIN"
        assert (
            await service.store.get(run.run_id, artifact.artifact_id)
        ).integrity_state == "CORRUPTED"
    finally:
        # Remove the fixed junction itself, never its target recursively.
        assert content.parent.parent.parent == service.settings.artifact_root
        os.rmdir(content)


async def test_stalled_artifact_writer_does_not_block_other_runs_publication(
    database, tmp_path, payload
):
    import copy
    import threading

    from review_harness.domain.snapshot import WorkspaceError
    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.infrastructure.persistence.workspaces import WorkspaceStore
    from review_harness.infrastructure.workspaces import WorkspaceManager
    from tests.snapshot_fixture import publish_snapshot

    settings = workspace_settings(database, tmp_path).model_copy(
        update={
            "tool_timeout_seconds": 0.5,
            "workspace_cleanup_seconds": 0.1,
        }
    )
    store = RunStore(database)
    other = copy.deepcopy(payload)
    other["runId"], other["codingSessionId"] = str(uuid4()), str(uuid4())
    other["snapshot"]["snapshotId"] = str(uuid4())
    publish_snapshot(settings.snapshot_root, other)
    manager = WorkspaceManager(settings, WorkspaceStore(database))
    entered, release = threading.Event(), threading.Event()

    def block(directory):
        if directory.parent.name == payload["runId"]:
            entered.set()
            release.wait()

    service = ArtifactService(settings, database, store, manager, publish_hook=block)
    try:
        await store.create_run(payload)
        await store.create_run(other)
        first, _ = await store.start_run(payload["runId"])
        second, _ = await store.start_run(other["runId"])
        first_context = await manager.prepare(first, asyncio.Event())
        second_context = await manager.prepare(second, asyncio.Event())
        signal = asyncio.Event()
        task = asyncio.create_task(
            service.publish(first_context, "first", "TOOL_OUTPUT", "read_file", signal)
        )
        assert await asyncio.wait_for(asyncio.to_thread(entered.wait), 5)
        signal.set()
        with pytest.raises(WorkspaceError, match="EXECUTION_WORKSPACE_CLEANUP_FAILED"):
            await task
        assert not release.is_set()
        with pytest.raises(WorkspaceError, match="EXECUTION_WORKSPACE_CLEANUP_FAILED"):
            await manager.cleanup(first)
        from review_harness.domain.model import Failure

        await store.fail_cleanup_uncertain(
            first.run_id, Failure.make("EXECUTION_WORKSPACE_CLEANUP_FAILED")
        )
        assert (await store.get_run(first.run_id)).resource_state == "UNCERTAIN"
        saved = await service.publish(
            second_context, "second", "TOOL_OUTPUT", "read_file", asyncio.Event()
        )
        assert (
            await service.content(second.run_id, saved.artifact_id)
        ).text == "second"
    finally:
        release.set()
        await manager.close(1)


async def test_artifact_redacts_prefixed_credentials_and_escaped_values(
    database, tmp_path, payload, workspaces
):
    from review_harness.infrastructure.artifacts import ArtifactService

    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    context = await workspaces.prepare(run, asyncio.Event())
    service = ArtifactService(
        workspace_settings(database, tmp_path), database, store, workspaces
    )
    artifact = await service.publish(
        context,
        'OPENAI_API_KEY="first\\"second"\nmyApiKey=third\nINTERNAL_BEARER_TOKEN=fourth\nsafe=count',
        "TOOL_OUTPUT",
        "read_file",
        asyncio.Event(),
    )
    page = await service.content(run.run_id, artifact.artifact_id)
    assert (
        page.text
        == "OPENAI_API_KEY=[REDACTED]\nmyApiKey=[REDACTED]\nINTERNAL_BEARER_TOKEN=[REDACTED]\nsafe=count"
    )
    structured = await service.publish(
        context,
        '{"OPENAI_API_KEY":"fifth","myApiKey":"sixth","safe":"count"}',
        "TOOL_OUTPUT",
        "search_code",
        asyncio.Event(),
        media_type="application/json",
    )
    import json

    assert json.loads(
        (await service.content(run.run_id, structured.artifact_id)).text
    ) == {"OPENAI_API_KEY": "[REDACTED]", "myApiKey": "[REDACTED]", "safe": "count"}


async def test_json_expansion_truncation_is_explicit_plain_partial(
    database, tmp_path, payload, workspaces
):
    from review_harness.infrastructure.artifacts import ArtifactService

    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    context = await workspaces.prepare(run, asyncio.Event())
    settings = workspace_settings(database, tmp_path).model_copy(
        update={"artifact_max_bytes": 20}
    )
    service = ArtifactService(settings, database, store, workspaces)
    value = await service.publish(
        context,
        '{"token":"x"}',
        "TOOL_OUTPUT",
        "search_code",
        asyncio.Event(),
        media_type="application/json",
    )
    assert value.coverage == "PARTIAL" and value.media_type == "text/plain"


@pytest.mark.parametrize("quote", ['"', "'"])
@pytest.mark.parametrize(
    "boundary",
    [
        "capture",
        "unclosed",
        "escaped",
        "dangling_escape",
        "utf8",
        "json",
        "closed",
        "escaped_closed",
        "multiline",
    ],
)
async def test_unclosed_credential_values_never_survive_artifact_capture(
    database, tmp_path, payload, workspaces, quote, boundary
):
    from review_harness.infrastructure.artifacts import ArtifactService

    prefix = f"password = {quote}"
    value = "alpha beta gamma delta"
    media_type = "text/plain"
    if boundary in {"escaped", "escaped_closed"}:
        value = f"alpha \\{quote}beta gamma delta"
    if boundary == "dangling_escape":
        value = "alpha beta\\"
    if boundary == "utf8":
        value = "alpha beta 中文😀 delta"
    if boundary == "multiline":
        value = "alpha beta\ngamma delta\nprivate tail"
    if boundary == "json":
        # JSON uses double quotes; the single quote case still checks JSON text fallback.
        prefix = '{"password":' + quote
        media_type = "application/json"
    captured = prefix + value
    source = captured + quote + ("}" if boundary == "json" else "")
    limit = len(captured.encode())
    if boundary == "capture":
        limit = len((prefix + "alpha beta g").encode())
    elif boundary in {"unclosed", "dangling_escape", "multiline"}:
        source = captured
        limit = 256
    elif boundary in {"closed", "escaped_closed"}:
        source += "\nsafe=count"
        limit = 256
    elif boundary == "utf8":
        limit = len((prefix + "alpha beta 中").encode()) + 1

    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    context = await workspaces.prepare(run, asyncio.Event())
    settings = workspace_settings(database, tmp_path).model_copy(
        update={"artifact_max_bytes": limit}
    )
    service = ArtifactService(settings, database, store, workspaces)
    artifact = await service.publish(
        context,
        source,
        "TOOL_OUTPUT",
        "read_file",
        asyncio.Event(),
        media_type=media_type,
    )
    assert artifact.redaction_state == "APPLIED"
    page = await service.content(run.run_id, artifact.artifact_id)
    expected = (
        '{"password":[REDACTED]' if boundary == "json" else "password = [REDACTED]"
    )
    if boundary == "multiline":
        expected += "\n\n"
    elif boundary in {"closed", "escaped_closed"}:
        expected += "\nsafe=count"
    assert page.text == expected
    assert artifact.coverage == ("FULL" if limit == 256 else "PARTIAL")
    assert artifact.media_type == "text/plain"
    # Verify persisted publication bytes as well as the content application service.
    persisted = settings.artifact_root / run.run_id / artifact.artifact_id / "content"
    assert persisted.read_text(encoding="utf-8") == expected


async def test_unclosed_noncredential_text_remains_exact_and_later_credentials_redact(
    database, tmp_path, payload, workspaces
):
    from review_harness.infrastructure.artifacts import ArtifactService

    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    context = await workspaces.prepare(run, asyncio.Event())
    service = ArtifactService(
        workspace_settings(database, tmp_path), database, store, workspaces
    )
    text = 'description = "alpha beta \\" 中文😀\npassword = "private tail'
    artifact = await service.publish(
        context, text, "TOOL_OUTPUT", "read_file", asyncio.Event()
    )
    page = await service.content(run.run_id, artifact.artifact_id)
    assert page.text == 'description = "alpha beta \\" 中文😀\npassword = [REDACTED]'
    plain = 'description = "alpha beta \\" 中文😀'
    artifact = await service.publish(
        context, plain, "TOOL_OUTPUT", "read_file", asyncio.Event()
    )
    assert (await service.content(run.run_id, artifact.artifact_id)).text == plain

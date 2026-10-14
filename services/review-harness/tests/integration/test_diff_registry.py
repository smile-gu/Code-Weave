"""Fixed Diff pages retain strict concrete identities across State 3 and 4."""

from uuid import uuid4

import pytest

from review_harness.application.handle_observations import observe_page
from review_harness.domain.handles import HandleRegistry, SourceSelection
from review_harness.domain.multipass_state import MultipassRegistry
from review_harness.domain.submission import SubmissionIssue


@pytest.mark.parametrize("multipass", [False, True])
def test_diff_page_keeps_concrete_registry_identity_and_exact_observation(multipass):
    cls = MultipassRegistry if multipass else HandleRegistry
    registry = cls(
        runId=str(uuid4()),
        snapshotId=str(uuid4()),
        **({"activePass": "correctness"} if multipass else {}),
    )
    ref = {
        "sourceType": "CODE",
        "path": "src/Demo.java",
        "side": "diff",
        "sha256": "sha256:" + "a" * 64,
        "startLine": 5,
        "endLine": 5,
    }
    observed, handle = observe_page(
        registry,
        ref,
        "FULL",
        "NONE",
        {"startLine": 5, "endLine": 5, "startsMidLine": False, "endsMidLine": False},
        None,
        None,
    )
    assert type(observed) is cls
    assert (observed.runId, observed.snapshotId) == (
        registry.runId,
        registry.snapshotId,
    )
    assert observed.source_binding(handle).observedRanges == [[5, 5]]
    if multipass:
        assert observed.activePass == "correctness"
        assert observed.source_binding(handle).passId == "correctness"
    with pytest.raises(SubmissionIssue):
        observed.resolve(SourceSelection(sourceHandle=handle, startLine=4, endLine=5))


@pytest.mark.parametrize("multipass", [False, True])
@pytest.mark.parametrize("skipped", [False, True])
def test_half_line_completion_retains_identity_and_requires_exact_cursor(
    multipass, skipped
):
    cls = MultipassRegistry if multipass else HandleRegistry
    registry = cls(
        runId=str(uuid4()),
        snapshotId=str(uuid4()),
        **({"activePass": "correctness"} if multipass else {}),
    )
    ref = {
        "sourceType": "CODE",
        "path": "src/Demo.java",
        "side": "diff",
        "sha256": "sha256:" + "a" * 64,
        "startLine": 5,
        "endLine": 5,
    }
    registry, handle = observe_page(
        registry,
        ref,
        "PARTIAL",
        "NONE",
        {"startLine": 5, "endLine": 5, "startsMidLine": False, "endsMidLine": True},
        None,
        "page-2",
    )
    selection = SourceSelection(sourceHandle=handle, startLine=5, endLine=5)
    with pytest.raises(SubmissionIssue):
        registry.resolve(selection)
    registry, again = observe_page(
        registry,
        ref,
        "PARTIAL",
        "NONE",
        {"startLine": 5, "endLine": 5, "startsMidLine": True, "endsMidLine": False},
        "wrong-page" if skipped else "page-2",
        None,
    )
    assert again == handle and type(registry) is cls
    if multipass:
        assert (
            registry.activePass
            == registry.source_binding(handle).passId
            == "correctness"
        )
    if skipped:
        with pytest.raises(SubmissionIssue):
            registry.resolve(selection)
    else:
        assert registry.resolve(selection).startLine == 5


def publish_git_diff(tmp_path, payload, snapshots):
    """Generate real multi-file Git Diff outside the runtime, then publish exact bytes."""
    import json
    import subprocess

    from review_harness.domain.model import canonical, sha256
    from tests.snapshot_fixture import publish_snapshot

    repo = tmp_path / "git-source"
    repo.mkdir()

    def git(*arguments):
        return subprocess.run(
            ["git", "-C", str(repo), *arguments], check=True, capture_output=True
        ).stdout

    git("init", "-b", "main")
    git("config", "core.autocrlf", "false")
    paths = ["src/A.java", "src/B.java"]
    files = {}
    for path in paths:
        target = repo / path
        target.parent.mkdir(exist_ok=True)
        target.write_bytes(b"old\nkeep\n")
    git("add", ".")
    git(
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.local",
        "commit",
        "-m",
        "baseline",
    )
    for path in paths:
        data = ("new " + path + " " + "x" * 1800 + "\nkeep\n").encode()
        (repo / path).write_bytes(data)
        files[path] = data
    diff = git("diff", "--no-ext-diff", "--no-textconv", "HEAD")
    payload["snapshot"]["snapshotId"] = str(uuid4())
    location = publish_snapshot(snapshots, payload, files)
    manifest = json.loads((location / "manifest.json").read_bytes())
    manifest["targetPaths"] = paths
    manifest["changes"]["files"] = [
        {
            "path": path,
            "oldPath": None,
            "changeType": "MODIFIED",
            "binary": False,
            "gitMode": "100644",
        }
        for path in paths
    ]
    manifest["changes"]["diff"].update(byteSize=len(diff), diffSha256=sha256(diff))
    (location / "artifacts/changes.diff").write_bytes(diff)
    raw = canonical(manifest)
    (location / "manifest.json").write_bytes(raw)
    payload["snapshot"]["manifestSha256"] = sha256(raw)
    return diff, paths


@pytest.mark.parametrize("multipass", [False, True])
async def test_production_diff_pages_persist_and_reach_model_without_cross_pass_observation(
    database, payload, workspaces, tmp_path, multipass
):
    import asyncio
    import json

    import httpx
    from pydantic import SecretStr

    from review_harness.application.evidence import EvidenceService
    from review_harness.application.local_handles import prepare_local_submission
    from review_harness.domain.graph import GraphMessage, ToolCall, pending_calls
    from review_harness.domain.model import utc_now
    from review_harness.domain.multipass_state import MultipassState, PassInvestigation
    from review_harness.domain.real_graph import HandleReviewGraphState
    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.infrastructure.checkpoints import Checkpoints
    from review_harness.infrastructure.persistence.model_calls import ModelCallStore
    from review_harness.infrastructure.persistence.store import RunStore
    from review_harness.runtime.context import ContextEngine
    from review_harness.runtime.deepseek import DeepSeekAdapter
    from review_harness.runtime.review import RealReviewExecutor
    from tests.integration.test_fee_ledger import paid_run

    diff, paths = publish_git_diff(tmp_path, payload, workspaces.settings.snapshot_root)
    if multipass:
        runs, _ = await paid_run(database, payload)
    else:
        payload["executionContract"] = {"executionMode": "REAL", "resultVersion": "2.0"}
        runs = RunStore(database)
        await runs.create_run(payload)
        await runs.start_run(payload["runId"])
    run = await runs.get_run(payload["runId"])
    signal = asyncio.Event()
    context = await workspaces.prepare(run, signal)
    artifacts = ArtifactService(workspaces.settings, database, runs, workspaces)
    evidence = EvidenceService(
        workspaces.settings, database, runs, workspaces, artifacts
    )
    checkpoints = Checkpoints(tmp_path / "diff-checkpoints.sqlite")
    await checkpoints.open()

    def no_send(request):
        raise AssertionError("This tool-chain regression must not call a provider")

    adapter = DeepSeekAdapter(
        SecretStr("offline-only"), transport=httpx.MockTransport(no_send)
    )
    engine = ContextEngine(
        workspaces.settings,
        runs,
        workspaces,
        artifacts,
        checkpoints,
        adapter,
        model_calls=ModelCallStore(database),
        evidence=evidence,
        state_type=MultipassState if multipass else HandleReviewGraphState,
    )
    executor = RealReviewExecutor(engine, evidence)
    try:
        state = await engine.initial(context, signal)
        first_pass_handles = []
        for phase in ["correctness", "consistency"] if multipass else [None]:
            if phase == "consistency":
                state = state.for_pass(phase, utc_now())
                await engine.persist(state, signal)
                old = state.handleRegistry.source_binding(first_pass_handles[0])
                line = old.observedRanges[0][0]
                submission = PassInvestigation.model_validate(
                    {
                        "decision": "COMPLETE",
                        "candidates": [],
                        "evidenceCandidates": [
                            {
                                "localKey": "n1",
                                "evidenceType": "CODE",
                                "coverage": "FULL",
                                "summary": "Old Pass observation",
                                "selections": [
                                    {
                                        "sourceHandle": old.sourceHandle,
                                        "startLine": line,
                                        "endLine": line,
                                    }
                                ],
                            }
                        ],
                        "checkedScope": paths,
                        "scopeKind": "FINAL_FILES",
                        "coverage": "FULL",
                        "limitations": ["Static fixed-input test"],
                        "incompleteReasons": [],
                    }
                )
                with pytest.raises(SubmissionIssue) as rejected:
                    await prepare_local_submission(
                        evidence,
                        context,
                        state,
                        submission,
                        None,
                        signal,
                        {"targetPaths": paths},
                    )
                assert rejected.value.reason == "SOURCE_NOT_OBSERVED_IN_PASS"
            cursor = None
            assembled = []
            saw_half = False
            pages = 0
            while True:
                call = ToolCall(
                    toolCallId=str(uuid4()),
                    providerToolCallId="diff-" + str(uuid4()),
                    name="git_diff",
                    arguments={"cursor": cursor} if cursor else {},
                )
                assistant = GraphMessage(
                    messageId=str(uuid4()),
                    role="assistant",
                    content="",
                    toolCalls=[call],
                )
                messages = [*state.messages, assistant]
                state = state.updated(
                    revision=state.revision + 1,
                    messages=[m.model_dump() for m in messages],
                    pendingToolCalls=pending_calls(messages),
                    toolCallCount=state.toolCallCount + 1,
                    providerCallMappings=[
                        *[m.model_dump() for m in state.providerCallMappings],
                        {
                            "modelCallId": str(uuid4()),
                            "toolCallId": call.toolCallId,
                            "providerToolCallId": call.providerToolCallId,
                        },
                    ],
                )
                await engine.persist(state, signal)
                state = await executor._tools(context, state, signal)
                envelope = json.loads(state.messages[-1].content)
                assert envelope["status"] == "OK" and envelope["artifactRef"] is None
                page = envelope["data"]
                assembled.append(page["text"])
                saw_half |= (
                    page["pageCoordinates"]["startsMidLine"]
                    or page["pageCoordinates"]["endsMidLine"]
                )
                pages += 1
                identity = await engine.persist(state, signal)
                restored = await checkpoints.read(state, identity)
                assert restored == state
                assert type(restored.handleRegistry) is (
                    MultipassRegistry if multipass else HandleRegistry
                )
                if multipass:
                    assert restored.handleRegistry.activePass == phase
                    assert all(
                        s.passId == phase
                        for s in restored.handleRegistry.sources
                        if s.sourceHandle
                        in [v["sourceHandle"] for v in page["sources"]]
                    )
                request = await engine.build(context, state, signal)
                assert state.messages[-1].content in [
                    m["content"] for m in json.loads(request.wire)["messages"]
                ]
                if not page["hasMore"]:
                    break
                cursor = page["nextCursor"]
                assert cursor and pages < 16
            assert "".join(assembled).encode() == diff and pages > 1 and saw_half
            bindings = [
                s
                for s in state.handleRegistry.sources
                if s.source.sourceType == "CODE"
                and s.source.side == "diff"
                and (not multipass or s.passId == phase)
            ]
            assert {s.source.path for s in bindings} == set(paths)
            # Every grant must be a physical code line, never Git file/hunk headers.
            physical = dict(enumerate(diff.decode().splitlines(), start=1))
            for binding in bindings:
                assert binding.observedRanges
                for lo, hi in binding.observedRanges:
                    for number in range(lo, hi + 1):
                        text = physical[number]
                        assert text.startswith(("+", "-", " ")) and not text.startswith(
                            ("+++", "---")
                        )
            if phase == "correctness":
                first_pass_handles = [s.sourceHandle for s in bindings]
            if phase == "consistency":
                assert not set(first_pass_handles) & {s.sourceHandle for s in bindings}
    finally:
        await engine.cleanup(run.run_id)
        await workspaces.cleanup(run)
        await checkpoints.close()

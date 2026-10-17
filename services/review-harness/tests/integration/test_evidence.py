import asyncio
import json
from uuid import uuid4

import pytest

from review_harness.application.evidence import EvidenceService
from review_harness.domain.model import HarnessError, sha256
from review_harness.domain.review import EvidenceCandidate
from review_harness.infrastructure.artifacts import ArtifactService
from review_harness.infrastructure.persistence.store import RunStore
from tests.snapshot_fixture import publish_snapshot


@pytest.mark.parametrize("damage", [None, "hash", "line", "deleted-final", "snapshot"])
async def test_code_evidence_opens_actual_fixed_source_before_persisting(
    database, workspaces, tmp_path, payload, damage
):
    runs = RunStore(database)
    await runs.create_run(payload)
    run, _ = await runs.start_run(payload["runId"])
    signal = asyncio.Event()
    context = await workspaces.prepare(run, signal)
    artifacts = ArtifactService(workspaces.settings, database, runs, workspaces)
    evidence = EvidenceService(
        workspaces.settings, database, runs, workspaces, artifacts
    )
    value = {
        "evidenceType": "CODE",
        "sourceRefs": [
            {
                "sourceType": "CODE",
                "path": "src/Demo.java",
                "side": "final",
                "sha256": sha256(b"class Demo {}\n"),
                "startLine": 1,
                "endLine": 1,
            }
        ],
        "coverage": "FULL",
        "summary": "Current class declaration.",
    }
    if damage == "hash":
        value["sourceRefs"][0]["sha256"] = "sha256:" + "f" * 64
    elif damage == "line":
        value["sourceRefs"][0]["endLine"] = 100
    elif damage == "deleted-final":
        value["sourceRefs"][0]["path"] = "deleted.java"
    elif damage == "snapshot":
        (
            workspaces.settings.snapshot_root
            / run.snapshot_id
            / "content/src/Demo.java"
        ).write_bytes(b"tampered\n")
    try:
        if damage:
            with pytest.raises(HarnessError):
                await evidence.capture(
                    context,
                    EvidenceCandidate.model_validate(value),
                    str(uuid4()),
                    signal,
                )
            assert not await evidence.list_for_run(run)
        else:
            record = await evidence.capture(
                context, EvidenceCandidate.model_validate(value), str(uuid4()), signal
            )
            assert record.runId == run.run_id and record.snapshotId == run.snapshot_id
            assert await evidence.get(run, record.evidenceId) == record
            material = await evidence.materials(run, record.sourceRefs, signal)
            assert material[0]["text"] == "class Demo {}\n"
    finally:
        await workspaces.cleanup(run)


@pytest.mark.parametrize(
    "side,line,valid",
    [("diff", 5, True), ("diff", 4, False), ("final", 1, False), ("base", 1, False)],
)
async def test_deleted_source_uses_verified_physical_diff_coordinates(
    database, workspaces, payload, side, line, valid
):
    payload["snapshot"]["snapshotId"] = str(uuid4())
    location = publish_snapshot(
        workspaces.settings.snapshot_root, payload, {"context.txt": b"context\n"}
    )
    diff = b"diff --git a/src/Deleted.java b/src/Deleted.java\n--- a/src/Deleted.java\n+++ /dev/null\n@@ -1 +0,0 @@\n-class Deleted {}\n"
    (location / "artifacts/changes.diff").write_bytes(diff)
    manifest = json.loads((location / "manifest.json").read_bytes())
    manifest["targetPaths"] = ["src/Deleted.java"]
    manifest["changes"]["files"] = [
        {
            "path": "src/Deleted.java",
            "oldPath": None,
            "changeType": "DELETED",
            "binary": False,
            "gitMode": "000000",
        }
    ]
    manifest["changes"]["diff"].update(byteSize=len(diff), diffSha256=sha256(diff))
    raw = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
    (location / "manifest.json").write_bytes(raw)
    payload["snapshot"]["manifestSha256"] = sha256(raw)
    runs = RunStore(database)
    await runs.create_run(payload)
    run, _ = await runs.start_run(payload["runId"])
    signal = asyncio.Event()
    context = await workspaces.prepare(run, signal)
    artifacts = ArtifactService(workspaces.settings, database, runs, workspaces)
    service = EvidenceService(
        workspaces.settings, database, runs, workspaces, artifacts
    )
    candidate = EvidenceCandidate.model_validate(
        {
            "evidenceType": "CODE",
            "coverage": "FULL",
            "summary": "Deleted declaration",
            "sourceRefs": [
                {
                    "sourceType": "CODE",
                    "path": "src/Deleted.java",
                    "side": side,
                    "sha256": sha256(diff),
                    "startLine": line,
                    "endLine": line,
                }
            ],
        }
    )
    try:
        if valid:
            record = await service.capture(context, candidate, str(uuid4()), signal)
            assert (await service.materials(run, record.sourceRefs, signal))[0][
                "text"
            ] == "-class Deleted {}\n"
        else:
            with pytest.raises(HarnessError):
                await service.capture(context, candidate, str(uuid4()), signal)
            assert not await service.list_for_run(run)
    finally:
        await workspaces.cleanup(run)


async def test_evidence_redacts_private_key_middle_line_before_selecting_excerpt(
    database, workspaces, payload
):
    payload["snapshot"]["snapshotId"] = str(uuid4())
    source = b"-----BEGIN PRIVATE KEY-----\nprivate-material\n-----END PRIVATE KEY-----\nclass Demo {}\n"
    publish_snapshot(
        workspaces.settings.snapshot_root, payload, {"src/Demo.java": source}
    )
    runs = RunStore(database)
    await runs.create_run(payload)
    run, _ = await runs.start_run(payload["runId"])
    signal = asyncio.Event()
    context = await workspaces.prepare(run, signal)
    artifacts = ArtifactService(workspaces.settings, database, runs, workspaces)
    service = EvidenceService(
        workspaces.settings, database, runs, workspaces, artifacts
    )
    candidate = EvidenceCandidate.model_validate(
        {
            "evidenceType": "CODE",
            "coverage": "PARTIAL",
            "summary": "Redacted material",
            "sourceRefs": [
                {
                    "sourceType": "CODE",
                    "path": "src/Demo.java",
                    "side": "final",
                    "sha256": sha256(source),
                    "startLine": 2,
                    "endLine": 2,
                }
            ],
        }
    )
    try:
        record = await service.capture(context, candidate, str(uuid4()), signal)
        material = (await service.materials(run, record.sourceRefs, signal))[0]
        assert "private-material" not in material["text"]
        assert (
            material["coverage"] == "PARTIAL"
            and material["redactionState"] == "APPLIED"
        )
    finally:
        await workspaces.cleanup(run)

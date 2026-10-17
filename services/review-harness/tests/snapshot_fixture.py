"""Real files matching the shared Manifest contract; no runtime bypass."""

import hashlib
import json
from pathlib import Path


def digest(data):
    return "sha256:" + hashlib.sha256(data).hexdigest()


def publish_snapshot(root: Path, payload: dict, files=None):
    files = (
        files
        if files is not None
        else {"src/Demo.java": b"class Demo {}\n", "context.txt": b"context\n"}
    )
    location = root / payload["snapshot"]["snapshotId"]
    (location / "content").mkdir(parents=True, exist_ok=True)
    (location / "artifacts").mkdir(exist_ok=True)
    entries = []
    for relative, content in sorted(files.items()):
        path = location / "content" / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        entries.append(
            {
                "path": relative,
                "type": "REGULAR_FILE",
                "tracked": True,
                "gitMode": "100644",
                "byteSize": len(content),
                "contentSha256": digest(content),
            }
        )
    diff = b"diff --git a/src/Demo.java b/src/Demo.java\n"
    (location / "artifacts" / "changes.diff").write_bytes(diff)
    changed = "src/Demo.java" if "src/Demo.java" in files else sorted(files)[0]
    manifest = {
        "snapshotFormatVersion": "1.0",
        "snapshotId": payload["snapshot"]["snapshotId"],
        "codingSessionId": payload["codingSessionId"],
        "baseCommit": "a" * 40,
        "capturedHeadCommit": "b" * 40,
        "branch": "main",
        "workspaceFingerprint": "sha256:" + "c" * 64,
        "createdAt": "2026-10-09T08:00:00.000000Z",
        "targetPaths": [changed],
        "excludedChangedPaths": [],
        "entries": entries,
        "changes": {
            "files": [
                {
                    "path": changed,
                    "oldPath": None,
                    "changeType": "MODIFIED",
                    "binary": False,
                    "gitMode": "100644",
                }
            ],
            "diff": {
                "path": "artifacts/changes.diff",
                "byteSize": len(diff),
                "diffSha256": digest(diff),
            },
        },
    }
    raw = json.dumps(
        manifest, sort_keys=True, ensure_ascii=False, separators=(",", ":")
    ).encode()
    (location / "manifest.json").write_bytes(raw)
    payload["snapshot"]["manifestSha256"] = digest(raw)
    return location

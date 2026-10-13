from sqlalchemy import (
    BLOB,
    CheckConstraint,
    Column,
    ForeignKey,
    Index,
    Integer,
    MetaData,
    Table,
    Text,
)

metadata = MetaData()

evidence_records = Table(
    "review_evidence",
    metadata,
    Column("evidence_id", Text, primary_key=True),
    Column(
        "run_id",
        Text,
        ForeignKey("review_runs.run_id", ondelete="RESTRICT"),
        nullable=False,
    ),
    Column("snapshot_id", Text, nullable=False),
    Column("record_bytes", BLOB, nullable=False),
    Column("sha256", Text, nullable=False),
    CheckConstraint("length(record_bytes) <= 131072", name="evidence_bytes"),
)
Index("ix_review_evidence_run", evidence_records.c.run_id)

model_calls = Table(
    "review_model_calls",
    metadata,
    Column("model_call_id", Text, primary_key=True),
    Column(
        "run_id",
        Text,
        ForeignKey("review_runs.run_id", ondelete="RESTRICT"),
        nullable=False,
    ),
    Column("snapshot_id", Text, nullable=False),
    Column("status", Text, nullable=False),
    Column("record_bytes", BLOB, nullable=False),
    Column("sha256", Text, nullable=False),
    CheckConstraint(
        "status IN ('PREPARED','DISPATCHING','RETURNED','LOCAL_CLOSED')",
        name="model_call_status",
    ),
    CheckConstraint("length(record_bytes) <= 16384", name="model_call_bytes"),
)
Index("ix_review_model_calls_run", model_calls.c.run_id)

artifacts = Table(
    "review_artifacts",
    metadata,
    Column("artifact_id", Text, primary_key=True),
    Column(
        "run_id",
        Text,
        ForeignKey("review_runs.run_id", ondelete="RESTRICT"),
        nullable=False,
    ),
    *[
        Column(name, Text, nullable=False)
        for name in (
            "snapshot_id",
            "kind",
            "producer_type",
            "producer_id",
            "media_type",
            "encoding",
            "sha256",
            "coverage",
            "redaction_state",
            "created_at",
            "integrity_state",
        )
    ],
    Column("byte_size", Integer, nullable=False),
    CheckConstraint("byte_size >= 0", name="artifact_size"),
    CheckConstraint(
        "kind IN ('SOURCE_EXCERPT','TOOL_OUTPUT','CONTEXT_MANIFEST','COMPACTION_SUMMARY')",
        name="artifact_kind",
    ),
    CheckConstraint("producer_type IN ('TOOL','SYSTEM')", name="artifact_producer"),
    CheckConstraint(
        "coverage IN ('FULL','PARTIAL','SAMPLED')", name="artifact_coverage"
    ),
    CheckConstraint(
        "integrity_state IN ('READY','CORRUPTED')", name="artifact_integrity"
    ),
    CheckConstraint(
        "redaction_state IN ('NONE','APPLIED','NOT_APPLICABLE')",
        name="artifact_redaction",
    ),
    CheckConstraint("encoding IN ('UTF-8','BINARY')", name="artifact_encoding"),
    CheckConstraint(
        "media_type IN ('text/plain','application/json','application/octet-stream')",
        name="artifact_media",
    ),
    CheckConstraint(
        "length(artifact_id)=36 AND length(run_id)=36 AND length(snapshot_id)=36 AND length(sha256)=71 AND length(producer_id)<=160",
        name="artifact_identity_lengths",
    ),
)
STATUSES = "'PENDING','RUNNING','CANCELLING','SUCCEEDED','FAILED','CANCELLED'"
RESOURCES = "'NOT_ACQUIRED','ACTIVE','RELEASING','RELEASED','UNCERTAIN'"
EVENTS = "'REVIEW_RUN_ACCEPTED','REVIEW_RUN_STARTED','REVIEW_RUN_CANCELLING','REVIEW_RUN_SUCCEEDED','REVIEW_RUN_FAILED','REVIEW_RUN_CANCELLED','REVIEW_RUN_RESOURCES_RELEASED'"

runs = Table(
    "review_runs",
    metadata,
    Column("run_id", Text, primary_key=True),
    Column("protocol_version", Text, nullable=False),
    Column("request_fingerprint", Text, nullable=False),
    Column("request_payload", BLOB, nullable=False),
    Column("coding_session_id", Text, nullable=False),
    Column("review_thread_id", Text, nullable=False),
    Column("snapshot_id", Text, nullable=False),
    Column("manifest_sha256", Text, nullable=False),
    Column("status", Text, nullable=False),
    Column("resource_state", Text, nullable=False),
    Column("created_at", Text, nullable=False),
    Column("started_at", Text),
    Column("finished_at", Text),
    Column("updated_at", Text, nullable=False),
    Column("last_event_sequence", Integer, nullable=False, server_default="0"),
    Column("result_id", Text),
    Column("result_sha256", Text),
    Column("result_version", Text),
    Column("failure_code", Text),
    Column("failure_message", Text),
    Column("diagnostic_id", Text),
    Column("recovery_code", Text),
    CheckConstraint(f"status IN ({STATUSES})", name="run_status"),
    CheckConstraint(f"resource_state IN ({RESOURCES})", name="resource_state"),
    CheckConstraint("last_event_sequence >= 0", name="last_sequence"),
)
Index("ix_review_runs_status", runs.c.status)

events = Table(
    "review_run_events",
    metadata,
    Column(
        "run_id",
        Text,
        ForeignKey("review_runs.run_id", ondelete="RESTRICT"),
        primary_key=True,
    ),
    Column("sequence", Integer, primary_key=True),
    Column("event_type", Text, nullable=False),
    Column("occurred_at", Text, nullable=False),
    Column("payload", BLOB, nullable=False),
    CheckConstraint("sequence > 0", name="event_sequence"),
    CheckConstraint(f"event_type IN ({EVENTS})", name="event_type"),
)

results = Table(
    "review_results",
    metadata,
    Column("result_id", Text, primary_key=True),
    Column(
        "run_id",
        Text,
        ForeignKey("review_runs.run_id", ondelete="RESTRICT"),
        unique=True,
        nullable=False,
    ),
    Column("snapshot_id", Text, nullable=False),
    Column("manifest_sha256", Text, nullable=False),
    Column("result_version", Text, nullable=False),
    Column("document_bytes", BLOB, nullable=False),
    Column("sha256", Text, nullable=False),
    Column("created_at", Text, nullable=False),
)

workspaces = Table(
    "execution_workspaces",
    metadata,
    Column(
        "run_id",
        Text,
        ForeignKey("review_runs.run_id", ondelete="RESTRICT"),
        primary_key=True,
    ),
    *[
        Column(name, Text, nullable=False)
        for name in (
            "snapshot_id",
            "manifest_sha256",
            "relative_directory",
            "state",
            "created_at",
            "updated_at",
        )
    ],
    Column("failure_code", Text),
    Column("diagnostic_id", Text),
    CheckConstraint(
        "state IN ('PREPARING','READY','CLEANING','REMOVED','RETAINED')",
        name="workspace_state",
    ),
)


run_fee_budget = Table(
    "run_fee_budget",
    metadata,
    Column(
        "run_id",
        Text,
        ForeignKey("review_runs.run_id", ondelete="RESTRICT"),
        primary_key=True,
    ),
    Column("authorization_id", Text, nullable=False, unique=True),
    Column("record_bytes", BLOB, nullable=False),
    Column("sha256", Text, nullable=False),
    CheckConstraint("length(record_bytes) <= 16384", name="fee_budget_bytes"),
)

model_call_reservations = Table(
    "model_call_reservations",
    metadata,
    Column(
        "model_call_id",
        Text,
        ForeignKey("review_model_calls.model_call_id", ondelete="RESTRICT"),
        primary_key=True,
    ),
    Column(
        "run_id",
        Text,
        ForeignKey("review_runs.run_id", ondelete="RESTRICT"),
        nullable=False,
    ),
    Column("state", Text, nullable=False),
    Column("record_bytes", BLOB, nullable=False),
    Column("sha256", Text, nullable=False),
    CheckConstraint("length(record_bytes) <= 16384", name="reservation_bytes"),
    CheckConstraint(
        "state IN ('RESERVED','DISPATCHING','SETTLED','UNKNOWN','REFUNDED')",
        name="reservation_state",
    ),
)
Index("ix_call_reservations_run", model_call_reservations.c.run_id)

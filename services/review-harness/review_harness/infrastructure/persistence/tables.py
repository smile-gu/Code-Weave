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

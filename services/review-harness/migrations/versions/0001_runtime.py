"""Initial execution fact schema; intentionally independent of runtime models."""

import sqlalchemy as sa
from alembic import op

revision = "0001_runtime"
down_revision = None
branch_labels = None
depends_on = None


def upgrade():
    required = [
        "protocol_version",
        "request_fingerprint",
        "coding_session_id",
        "review_thread_id",
        "snapshot_id",
        "manifest_sha256",
        "status",
        "resource_state",
        "created_at",
        "updated_at",
    ]
    optional = [
        "started_at",
        "finished_at",
        "result_id",
        "result_sha256",
        "result_version",
        "failure_code",
        "failure_message",
        "diagnostic_id",
        "recovery_code",
    ]
    op.create_table(
        "review_runs",
        sa.Column("run_id", sa.Text, primary_key=True),
        *[sa.Column(name, sa.Text, nullable=False) for name in required],
        sa.Column("request_payload", sa.BLOB, nullable=False),
        *[sa.Column(name, sa.Text) for name in optional],
        sa.Column(
            "last_event_sequence", sa.Integer, nullable=False, server_default="0"
        ),
        sa.CheckConstraint(
            "status IN ('PENDING','RUNNING','CANCELLING','SUCCEEDED','FAILED','CANCELLED')",
            name="run_status",
        ),
        sa.CheckConstraint(
            "resource_state IN ('NOT_ACQUIRED','ACTIVE','RELEASING','RELEASED','UNCERTAIN')",
            name="resource_state",
        ),
        sa.CheckConstraint("last_event_sequence >= 0", name="last_sequence"),
    )
    op.create_index("ix_review_runs_status", "review_runs", ["status"])
    op.create_table(
        "review_run_events",
        sa.Column(
            "run_id",
            sa.Text,
            sa.ForeignKey("review_runs.run_id", ondelete="RESTRICT"),
            primary_key=True,
        ),
        sa.Column("sequence", sa.Integer, primary_key=True),
        sa.Column("event_type", sa.Text, nullable=False),
        sa.Column("occurred_at", sa.Text, nullable=False),
        sa.Column("payload", sa.BLOB, nullable=False),
        sa.CheckConstraint("sequence > 0", name="event_sequence"),
        sa.CheckConstraint(
            "event_type IN ('REVIEW_RUN_ACCEPTED','REVIEW_RUN_STARTED','REVIEW_RUN_CANCELLING','REVIEW_RUN_SUCCEEDED','REVIEW_RUN_FAILED','REVIEW_RUN_CANCELLED','REVIEW_RUN_RESOURCES_RELEASED')",
            name="event_type",
        ),
    )
    op.create_table(
        "review_results",
        sa.Column("result_id", sa.Text, primary_key=True),
        sa.Column(
            "run_id",
            sa.Text,
            sa.ForeignKey("review_runs.run_id", ondelete="RESTRICT"),
            nullable=False,
            unique=True,
        ),
        *[
            sa.Column(name, sa.Text, nullable=False)
            for name in (
                "snapshot_id",
                "manifest_sha256",
                "result_version",
                "sha256",
                "created_at",
            )
        ],
        sa.Column("document_bytes", sa.BLOB, nullable=False),
    )


def downgrade():
    op.drop_table("review_results")
    op.drop_table("review_run_events")
    op.drop_index("ix_review_runs_status", table_name="review_runs")
    op.drop_table("review_runs")

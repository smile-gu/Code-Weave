"""Persistent Artifact metadata, independent of disposable execution workspaces."""

import sqlalchemy as sa
from alembic import op

revision = "0003_artifacts"
down_revision = "0002_workspaces"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "review_artifacts",
        sa.Column("artifact_id", sa.Text, primary_key=True),
        sa.Column(
            "run_id",
            sa.Text,
            sa.ForeignKey("review_runs.run_id", ondelete="RESTRICT"),
            nullable=False,
        ),
        *[
            sa.Column(name, sa.Text, nullable=False)
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
        sa.Column("byte_size", sa.Integer, nullable=False),
        sa.CheckConstraint("byte_size >= 0", name="artifact_size"),
        sa.CheckConstraint(
            "kind IN ('SOURCE_EXCERPT','TOOL_OUTPUT')", name="artifact_kind"
        ),
        sa.CheckConstraint(
            "producer_type IN ('TOOL','SYSTEM')", name="artifact_producer"
        ),
        sa.CheckConstraint(
            "coverage IN ('FULL','PARTIAL','SAMPLED')", name="artifact_coverage"
        ),
        sa.CheckConstraint(
            "integrity_state IN ('READY','CORRUPTED')", name="artifact_integrity"
        ),
        sa.CheckConstraint(
            "redaction_state IN ('NONE','APPLIED','NOT_APPLICABLE')",
            name="artifact_redaction",
        ),
        sa.CheckConstraint("encoding IN ('UTF-8','BINARY')", name="artifact_encoding"),
        sa.CheckConstraint(
            "media_type IN ('text/plain','application/json','application/octet-stream')",
            name="artifact_media",
        ),
        sa.CheckConstraint(
            "length(artifact_id)=36 AND length(run_id)=36 AND length(snapshot_id)=36 AND length(sha256)=71 AND length(producer_id)<=160",
            name="artifact_identity_lengths",
        ),
    )


def downgrade():
    op.drop_table("review_artifacts")

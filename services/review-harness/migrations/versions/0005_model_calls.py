"""Durable, non-replayable model send intents; no prompt or credential storage."""

import sqlalchemy as sa
from alembic import op

revision = "0005_model_calls"
down_revision = "0004_context_artifacts"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "review_model_calls",
        sa.Column("model_call_id", sa.Text(), primary_key=True),
        sa.Column(
            "run_id",
            sa.Text(),
            sa.ForeignKey("review_runs.run_id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("snapshot_id", sa.Text(), nullable=False),
        sa.Column("status", sa.Text(), nullable=False),
        sa.Column("record_bytes", sa.LargeBinary(), nullable=False),
        sa.Column("sha256", sa.Text(), nullable=False),
        sa.CheckConstraint(
            "status IN ('PREPARED','DISPATCHING','RETURNED','LOCAL_CLOSED')",
            name="model_call_status",
        ),
        sa.CheckConstraint("length(record_bytes) <= 16384", name="model_call_bytes"),
    )
    op.create_index("ix_review_model_calls_run", "review_model_calls", ["run_id"])


def downgrade():
    if (
        op.get_bind()
        .execute(sa.text("SELECT COUNT(*) FROM review_model_calls"))
        .scalar()
    ):
        raise RuntimeError("Model call facts prevent destructive downgrade")
    op.drop_index("ix_review_model_calls_run", table_name="review_model_calls")
    op.drop_table("review_model_calls")

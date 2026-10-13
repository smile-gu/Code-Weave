"""Immutable verified-source facts, independent of graph checkpoints."""

import sqlalchemy as sa
from alembic import op

revision = "0006_evidence"
down_revision = "0005_model_calls"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "review_evidence",
        sa.Column("evidence_id", sa.Text(), primary_key=True),
        sa.Column(
            "run_id",
            sa.Text(),
            sa.ForeignKey("review_runs.run_id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("snapshot_id", sa.Text(), nullable=False),
        sa.Column("record_bytes", sa.LargeBinary(), nullable=False),
        sa.Column("sha256", sa.Text(), nullable=False),
        sa.CheckConstraint("length(record_bytes) <= 131072", name="evidence_bytes"),
    )
    op.create_index("ix_review_evidence_run", "review_evidence", ["run_id"])


def downgrade():
    if op.get_bind().execute(sa.text("SELECT COUNT(*) FROM review_evidence")).scalar():
        raise RuntimeError("Evidence facts prevent destructive downgrade")
    op.drop_index("ix_review_evidence_run", table_name="review_evidence")
    op.drop_table("review_evidence")

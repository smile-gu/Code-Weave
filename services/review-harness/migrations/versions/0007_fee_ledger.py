"""Durable exact fee authorization and per-call reservations."""

import sqlalchemy as sa
from alembic import op

revision = "0007_fee_ledger"
down_revision = "0006_evidence"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "run_fee_budget",
        sa.Column(
            "run_id",
            sa.Text(),
            sa.ForeignKey("review_runs.run_id", ondelete="RESTRICT"),
            primary_key=True,
        ),
        sa.Column("authorization_id", sa.Text(), nullable=False, unique=True),
        sa.Column("record_bytes", sa.LargeBinary(), nullable=False),
        sa.Column("sha256", sa.Text(), nullable=False),
        sa.CheckConstraint("length(record_bytes) <= 16384", name="fee_budget_bytes"),
    )
    op.create_table(
        "model_call_reservations",
        sa.Column(
            "model_call_id",
            sa.Text(),
            sa.ForeignKey("review_model_calls.model_call_id", ondelete="RESTRICT"),
            primary_key=True,
        ),
        sa.Column(
            "run_id",
            sa.Text(),
            sa.ForeignKey("review_runs.run_id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("state", sa.Text(), nullable=False),
        sa.Column("record_bytes", sa.LargeBinary(), nullable=False),
        sa.Column("sha256", sa.Text(), nullable=False),
        sa.CheckConstraint("length(record_bytes) <= 16384", name="reservation_bytes"),
        sa.CheckConstraint(
            "state IN ('RESERVED','DISPATCHING','SETTLED','UNKNOWN','REFUNDED')",
            name="reservation_state",
        ),
    )
    op.create_index("ix_call_reservations_run", "model_call_reservations", ["run_id"])


def downgrade():
    for table in ("run_fee_budget", "model_call_reservations"):
        if op.get_bind().execute(sa.text("SELECT COUNT(*) FROM " + table)).scalar():
            raise RuntimeError("Fee facts prevent destructive downgrade")
    op.drop_index("ix_call_reservations_run", table_name="model_call_reservations")
    op.drop_table("model_call_reservations")
    op.drop_table("run_fee_budget")

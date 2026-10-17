"""Durable serial pass lifecycle; immutable history cannot be dropped."""

import sqlalchemy as sa
from alembic import op

revision = "0008_review_passes"
down_revision = "0007_fee_ledger"
branch_labels = depends_on = None


def upgrade():
    with op.batch_alter_table("review_run_events") as batch:
        batch.drop_constraint("event_type", type_="check")
        batch.create_check_constraint(
            "event_type",
            "event_type IN ('REVIEW_RUN_ACCEPTED','REVIEW_RUN_STARTED','REVIEW_RUN_CANCELLING','REVIEW_RUN_SUCCEEDED','REVIEW_RUN_FAILED','REVIEW_RUN_CANCELLED','REVIEW_RUN_RESOURCES_RELEASED','REVIEW_PASS_UPDATED')",
        )
    op.create_table(
        "review_passes",
        sa.Column(
            "run_id",
            sa.Text(),
            sa.ForeignKey("review_runs.run_id", ondelete="RESTRICT"),
            primary_key=True,
        ),
        sa.Column("pass_id", sa.Text(), primary_key=True),
        sa.Column("record_bytes", sa.LargeBinary(), nullable=False),
        sa.Column("sha256", sa.Text(), nullable=False),
        sa.CheckConstraint("length(record_bytes) <= 65536", name="pass_record_bytes"),
    )


def downgrade():
    if op.get_bind().execute(sa.text("SELECT COUNT(*) FROM run_fee_budget")).scalar():
        raise RuntimeError("Fee facts prevent destructive downgrade")
    if op.get_bind().execute(sa.text("SELECT COUNT(*) FROM review_passes")).scalar():
        raise RuntimeError("Pass facts prevent destructive downgrade")
    op.drop_table("review_passes")
    if (
        op.get_bind()
        .execute(
            sa.text(
                "SELECT COUNT(*) FROM review_run_events WHERE event_type='REVIEW_PASS_UPDATED'"
            )
        )
        .scalar()
    ):
        raise RuntimeError("Pass events prevent destructive downgrade")
    with op.batch_alter_table("review_run_events") as batch:
        batch.drop_constraint("event_type", type_="check")
        batch.create_check_constraint(
            "event_type",
            "event_type IN ('REVIEW_RUN_ACCEPTED','REVIEW_RUN_STARTED','REVIEW_RUN_CANCELLING','REVIEW_RUN_SUCCEEDED','REVIEW_RUN_FAILED','REVIEW_RUN_CANCELLED','REVIEW_RUN_RESOURCES_RELEASED')",
        )

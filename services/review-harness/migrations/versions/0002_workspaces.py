"""Persistent file preparation/cleanup intentions, preserving batch 1 history."""

import sqlalchemy as sa
from alembic import op

revision = "0002_workspaces"
down_revision = "0001_runtime"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "execution_workspaces",
        sa.Column(
            "run_id",
            sa.Text,
            sa.ForeignKey("review_runs.run_id", ondelete="RESTRICT"),
            primary_key=True,
        ),
        *[
            sa.Column(name, sa.Text, nullable=False)
            for name in (
                "snapshot_id",
                "manifest_sha256",
                "relative_directory",
                "state",
                "created_at",
                "updated_at",
            )
        ],
        sa.Column("failure_code", sa.Text),
        sa.Column("diagnostic_id", sa.Text),
        sa.CheckConstraint(
            "state IN ('PREPARING','READY','CLEANING','REMOVED','RETAINED')",
            name="workspace_state",
        ),
    )


def downgrade():
    op.drop_table("execution_workspaces")

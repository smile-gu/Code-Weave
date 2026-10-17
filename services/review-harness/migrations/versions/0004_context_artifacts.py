"""Add only the Artifact kinds consumed by graph context and compaction."""

import sqlalchemy as sa
from alembic import op

revision = "0004_context_artifacts"
down_revision = "0003_artifacts"
branch_labels = None
depends_on = None


def change_kinds(expression):
    with op.batch_alter_table("review_artifacts") as batch:
        batch.drop_constraint("artifact_kind", type_="check")
        batch.create_check_constraint("artifact_kind", expression)


def upgrade():
    change_kinds(
        "kind IN ('SOURCE_EXCERPT','TOOL_OUTPUT','CONTEXT_MANIFEST','COMPACTION_SUMMARY')"
    )


def downgrade():
    # Existing new-kind records must be retained; downgrade with them is rejected.
    connection = op.get_bind()
    if connection.execute(
        sa.text(
            "SELECT COUNT(*) FROM review_artifacts WHERE kind IN ('CONTEXT_MANIFEST','COMPACTION_SUMMARY')"
        )
    ).scalar():
        raise RuntimeError("Context Artifacts prevent a destructive downgrade")
    change_kinds("kind IN ('SOURCE_EXCERPT','TOOL_OUTPUT')")

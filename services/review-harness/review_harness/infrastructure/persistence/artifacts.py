from pydantic import ValidationError
from sqlalchemy import insert, select, update

from review_harness.domain.artifact import Artifact, artifact_error
from review_harness.infrastructure.persistence.store import commit
from review_harness.infrastructure.persistence.tables import artifacts, runs


class ArtifactStore:
    def __init__(self, database, commit_transaction=commit):
        self.database = database
        self.commit_transaction = commit_transaction

    async def get(self, run_id, artifact_id):
        async with self.database.engine.connect() as connection:
            row = (
                (
                    await connection.execute(
                        select(artifacts)
                        .join(runs, artifacts.c.run_id == runs.c.run_id)
                        .where(
                            artifacts.c.run_id == run_id,
                            artifacts.c.artifact_id == artifact_id,
                            artifacts.c.snapshot_id == runs.c.snapshot_id,
                        )
                    )
                )
                .mappings()
                .first()
            )
        if row is None:
            raise artifact_error("ARTIFACT_NOT_FOUND", 404)
        try:
            return Artifact.model_validate(dict(row))
        except ValidationError:
            raise artifact_error("ARTIFACT_INTEGRITY_FAILED") from None

    async def save(self, value):
        async with self.database.write_lock:
            try:
                async with self.database.engine.connect() as connection:
                    await connection.exec_driver_sql("BEGIN IMMEDIATE")
                    await connection.execute(
                        insert(artifacts).values(**value.model_dump())
                    )
                    await self.commit_transaction(connection)
            except Exception:
                try:
                    saved = await self.get(value.run_id, value.artifact_id)
                except Exception:
                    raise artifact_error("ARTIFACT_STATE_WRITE_FAILED") from None
                if saved != value:
                    raise artifact_error("ARTIFACT_STATE_WRITE_FAILED") from None
        return value

    async def corrupt(self, value):
        async with (
            self.database.write_lock,
            self.database.engine.connect() as connection,
        ):
            await connection.execute(
                update(artifacts)
                .where(
                    artifacts.c.artifact_id == value.artifact_id,
                    artifacts.c.run_id == value.run_id,
                )
                .values(integrity_state="CORRUPTED")
            )
            try:
                await self.commit_transaction(connection)
            except Exception:
                saved = await self.get(value.run_id, value.artifact_id)
                if saved.integrity_state != "CORRUPTED":
                    raise artifact_error("ARTIFACT_STATE_WRITE_FAILED") from None

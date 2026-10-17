from sqlalchemy import case, func, insert, select

from review_harness.domain.graph import graph_error
from review_harness.domain.model import canonical, sha256
from review_harness.domain.provider import strict_json
from review_harness.domain.review import EvidenceRecord
from review_harness.infrastructure.persistence.store import commit
from review_harness.infrastructure.persistence.tables import evidence_records, runs


class EvidenceStore:
    def __init__(self, database, commit_transaction=commit):
        self.database, self.commit_transaction = database, commit_transaction

    @staticmethod
    def projection():
        return select(
            *[
                case((func.length(c) <= 131072, c), else_=None).label(c.name)
                if c.name == "record_bytes"
                else c
                for c in evidence_records.c
            ]
        )

    @staticmethod
    def decode(row, run):
        try:
            raw = bytes(row["record_bytes"])
            record = EvidenceRecord.model_validate(strict_json(raw, limit=131072))
            if (
                canonical(record.model_dump()) != raw
                or sha256(raw) != row["sha256"]
                or (record.evidenceId, record.runId, record.snapshotId)
                != (row["evidence_id"], run.run_id, run.snapshot_id)
                or row["snapshot_id"] != run.snapshot_id
            ):
                raise ValueError("Evidence integrity mismatch")
            return record
        except (ValueError, TypeError, KeyError):
            raise graph_error("EVIDENCE_INTEGRITY_FAILED") from None

    async def get(self, run, evidence_id):
        async with self.database.engine.connect() as connection:
            row = (
                (
                    await connection.execute(
                        self.projection().where(
                            evidence_records.c.run_id == run.run_id,
                            evidence_records.c.evidence_id == evidence_id,
                        )
                    )
                )
                .mappings()
                .first()
            )
        if row is None:
            raise graph_error("EVIDENCE_SOURCE_NOT_FOUND")
        return self.decode(row, run)

    async def list_for_run(self, run):
        async with self.database.engine.connect() as connection:
            rows = (
                (
                    await connection.execute(
                        self.projection()
                        .where(evidence_records.c.run_id == run.run_id)
                        .order_by(evidence_records.c.evidence_id)
                        .limit(129)
                    )
                )
                .mappings()
                .all()
            )
        if len(rows) > 128:
            raise graph_error("EVIDENCE_LIMIT_EXCEEDED")
        return [self.decode(row, run) for row in rows]

    async def save(self, run, record):
        return (await self.save_batch(run, [record]))[0]

    async def save_batch(self, run, records):
        if not records:
            return []
        if len(records) > 128 or len({record.evidenceId for record in records}) != len(
            records
        ):
            raise graph_error("EVIDENCE_LIMIT_EXCEEDED")
        values = []
        for record in records:
            raw = canonical(record.model_dump())
            if len(raw) > 131072 or (record.runId, record.snapshotId) != (
                run.run_id,
                run.snapshot_id,
            ):
                raise graph_error("EVIDENCE_LIMIT_EXCEEDED")
            values.append(
                {
                    "evidence_id": record.evidenceId,
                    "run_id": run.run_id,
                    "snapshot_id": run.snapshot_id,
                    "record_bytes": raw,
                    "sha256": sha256(raw),
                }
            )

        async def confirmed():
            for record in records:
                if await self.get(run, record.evidenceId) != record:
                    return False
            return True

        async with self.database.write_lock:
            mutated = False
            try:
                async with self.database.engine.connect() as connection:
                    await connection.exec_driver_sql("BEGIN IMMEDIATE")
                    status = (
                        await connection.execute(
                            select(runs.c.status, runs.c.snapshot_id).where(
                                runs.c.run_id == run.run_id
                            )
                        )
                    ).first()
                    if status != ("RUNNING", run.snapshot_id):
                        raise graph_error("CONTEXT_RUN_NOT_ACTIVE")
                    count = (
                        await connection.execute(
                            select(func.count())
                            .select_from(evidence_records)
                            .where(evidence_records.c.run_id == run.run_id)
                        )
                    ).scalar_one()
                    if count + len(records) > 128:
                        raise graph_error("EVIDENCE_LIMIT_EXCEEDED")
                    await connection.execute(insert(evidence_records), values)
                    mutated = True
                    await self.commit_transaction(connection)
            except Exception:
                if mutated:
                    try:
                        if await confirmed():
                            return records
                    except Exception:
                        pass
                raise graph_error("EVIDENCE_PERSISTENCE_FAILED") from None
            if not await confirmed():
                raise graph_error("EVIDENCE_PERSISTENCE_FAILED")
        return records

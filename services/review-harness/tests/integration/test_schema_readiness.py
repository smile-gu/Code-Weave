import pytest
from sqlalchemy import text

from review_harness.api.app import create_app
from tests.integration.test_lifecycle import client_for, settings_for


@pytest.mark.parametrize(
    "damage",
    [
        "DROP TABLE review_runs",
        "DROP TABLE review_run_events",
        "DROP TABLE review_results",
        "ALTER TABLE review_runs DROP COLUMN request_payload",
        "ALTER TABLE review_run_events DROP COLUMN payload",
        "ALTER TABLE review_results DROP COLUMN document_bytes",
        "DROP TABLE execution_workspaces",
        "DROP TABLE review_artifacts",
        "DROP TABLE review_model_calls",
        "DROP TABLE review_evidence",
        "ALTER TABLE review_evidence DROP COLUMN sha256",
        "ALTER TABLE review_model_calls DROP COLUMN sha256",
        "ALTER TABLE review_artifacts DROP COLUMN created_at",
        "ALTER TABLE execution_workspaces DROP COLUMN diagnostic_id",
    ],
)
async def test_incomplete_empty_schema_cannot_be_ready_or_accept_runs(
    database, tmp_path, payload, damage
):
    async with database.engine.begin() as connection:
        await connection.execute(text(damage))
    settings = settings_for(database, tmp_path)
    await database.close()
    app = create_app(settings)
    async with app.router.lifespan_context(app):
        async with client_for(app) as client:
            health = await client.get("/internal/v1/health")
            assert health.status_code == 503
            value = health.json()
            assert value["status"] == "NOT_READY" and not value["acceptingRuns"]
            assert value["checks"]["schema"] == "NOT_READY"
            creation = await client.post("/internal/v1/review-runs", json=payload)
            assert creation.status_code == 503
            assert creation.json()["errorCode"] == "REVIEW_HARNESS_NOT_READY"

from review_harness.api.app import create_app
from tests.integration.test_lifecycle import client_for, settings_for


async def test_real_contract_cannot_reserve_a_run_on_simulated_service(
    database, tmp_path, payload
):
    app = create_app(settings_for(database, tmp_path))
    async with app.router.lifespan_context(app), client_for(app) as client:
        capabilities = await client.get("/internal/v1/review-capabilities")
        assert capabilities.status_code == 200
        assert capabilities.json()["configuredContract"] == {
            "executionMode": "SIMULATED",
            "resultVersion": "1.0",
        }
        value = {
            **payload,
            "executionContract": {"executionMode": "REAL", "resultVersion": "2.0"},
        }
        rejected = await client.post("/internal/v1/review-runs", json=value)
        assert rejected.status_code == 400
        assert rejected.json()["errorCode"] == "EXECUTION_CONTRACT_UNSUPPORTED"
        assert (
            await client.get(f"/internal/v1/review-runs/{payload['runId']}")
        ).status_code == 404

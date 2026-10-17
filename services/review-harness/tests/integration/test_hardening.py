import subprocess
import sys
from dataclasses import asdict

from sqlalchemy import event

from review_harness.api.app import create_app
from review_harness.infrastructure.persistence.store import RunStore
from tests.integration.network import network_client
from tests.integration.test_lifecycle import settings_for


async def test_domain_validation_is_not_removed_by_optimized_python(database, payload):
    store = RunStore(database)
    run, _ = await store.create_run(payload)
    source = "\n".join(
        [
            "from dataclasses import replace",
            "from review_harness.domain.model import Run, HarnessError",
            "run = Run(**" + repr(asdict(run)) + ")",
            "invalid = replace(run, resource_state='RELEASED')",
            "try:",
            "    invalid.validate()",
            "except HarnessError:",
            "    raise SystemExit(0)",
            "raise SystemExit(1)",
        ]
    )
    result = subprocess.run(
        [sys.executable, "-O", "-c", source], capture_output=True, timeout=10
    )
    assert result.returncode == 0


async def test_unexpected_read_failure_is_safe_in_network_response_and_logs(
    database, tmp_path, payload, caplog
):
    marker = "private-secret-absolute-path"
    app = create_app(settings_for(database, tmp_path))
    async with network_client(app) as client:
        await client.post("/internal/v1/review-runs", json=payload)
        await app.state.supervisor.join()

        def reject_read(_, __, statement, *args):
            if statement.startswith("SELECT review_runs"):
                raise RuntimeError(marker)

        event.listen(
            app.state.database.engine.sync_engine, "before_cursor_execute", reject_read
        )
        try:
            response = await client.get("/internal/v1/review-runs/" + payload["runId"])
        finally:
            event.remove(
                app.state.database.engine.sync_engine,
                "before_cursor_execute",
                reject_read,
            )
        assert response.status_code == 500 and marker not in response.text
    assert marker not in caplog.text

import logging
from contextlib import asynccontextmanager
from pathlib import Path
from uuid import uuid4

from sqlalchemy.engine import make_url

from review_harness.application.evidence import EvidenceService
from review_harness.application.recovery import recover_runs
from review_harness.application.service import RunService
from review_harness.domain.real_graph import HandleReviewGraphState
from review_harness.infrastructure.artifacts import ArtifactService
from review_harness.infrastructure.checkpoints import Checkpoints
from review_harness.infrastructure.persistence.database import Database
from review_harness.infrastructure.persistence.model_calls import ModelCallStore
from review_harness.infrastructure.persistence.store import RunStore
from review_harness.infrastructure.persistence.workspaces import WorkspaceStore
from review_harness.infrastructure.workspaces import WorkspaceManager
from review_harness.runtime.context import ContextEngine
from review_harness.runtime.deepseek import DeepSeekAdapter
from review_harness.runtime.deepseek_meter import ASSETS, TextMeter
from review_harness.runtime.executor import DeterministicExecutor
from review_harness.runtime.review import RealReviewExecutor
from review_harness.runtime.supervisor import Supervisor
from review_harness.runtime.tools import ToolGateway

logger = logging.getLogger("review_harness")


@asynccontextmanager
async def lifespan(app):
    # Injected service is a test seam for HTTP contracts; production composes here.
    if app.state.injected_service:
        yield
        return
    settings = app.state.settings
    app.state.recovery_complete = False
    database = app.state.database = Database(settings.database_url)
    store = RunStore(database, notify=app.state.hub.notify)
    workspaces = app.state.workspaces = WorkspaceManager(
        settings, WorkspaceStore(database)
    )
    supervisor = app.state.supervisor = Supervisor(
        store,
        app.state.executor or DeterministicExecutor(),
        settings.supervisor_queue_capacity,
        workspaces,
    )
    app.state.service = RunService(store, supervisor)
    app.state.artifacts = ArtifactService(settings, database, store, workspaces)
    app.state.tools = ToolGateway(settings, store, workspaces, app.state.artifacts)
    app.state.evidence = EvidenceService(
        settings, database, store, workspaces, app.state.artifacts
    )
    if settings.checkpoint_database_url is not None:
        checkpoints = Checkpoints(
            Path(make_url(settings.checkpoint_database_url).database)
        )
        try:
            await checkpoints.open()
            app.state.checkpoints = checkpoints
            app.state.graph_capabilities["checkpoint"] = "READY"
        except Exception:
            app.state.graph_capabilities["checkpoint"] = "NOT_READY"
            logger.warning('{"errorCode":"CHECKPOINT_UNAVAILABLE"}')
    if settings.execution_mode == "REAL" and settings.deepseek_api_key is not None:
        try:
            adapter = app.state.real_adapter = DeepSeekAdapter(
                settings.deepseek_api_key,
                transport=app.state.provider_transport,
                meter=TextMeter(settings.deepseek_asset_root or ASSETS),
            )
            if settings.budget_policy_version != adapter.budget_policy.version:
                raise RuntimeError("Unsupported context budget policy")
            adapter.ready()
            if app.state.checkpoints is None or not await app.state.checkpoints.ready():
                raise RuntimeError("Checkpoint unavailable")
            evidence = app.state.evidence
            engine = ContextEngine(
                settings,
                store,
                workspaces,
                app.state.artifacts,
                app.state.checkpoints,
                adapter,
                model_calls=ModelCallStore(database),
                state_type=HandleReviewGraphState,
                evidence=evidence,
            )
            supervisor.executor = RealReviewExecutor(engine, evidence, app.state.tools)
            app.state.real_executor_ready = True
        except Exception as error:
            app.state.real_failure_code = getattr(
                error, "code", "CHECKPOINT_UNAVAILABLE"
            )
    try:
        if (
            await database.schema_ready()
            and await database.writable()
            and app.state.storage_probe()
        ):
            await recover_runs(store, app.state.service, workspaces)
            await ModelCallStore(database).recover(
                local_resources_released=lambda run_id: (
                    not workspaces.runtime_tasks_active(run_id)
                )
            )
            await app.state.artifacts.audit()
            app.state.recovery_complete = True
            from review_harness.application.readiness import real_failures

            if not await real_failures(app):
                supervisor.start()
    except Exception:
        supervisor.accepting = False
        logger.error(
            '{"diagnosticId":"%s","errorCode":"REVIEW_RECOVERY_FAILED"}', str(uuid4())
        )
    try:
        yield
    finally:
        await supervisor.stop(settings.shutdown_grace_seconds)
        await workspaces.close(settings.shutdown_grace_seconds)
        if app.state.checkpoints is not None:
            await app.state.checkpoints.close()
        await database.close()

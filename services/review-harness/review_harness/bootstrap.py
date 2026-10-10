import logging
from contextlib import asynccontextmanager
from uuid import uuid4

from review_harness.application.recovery import recover_runs
from review_harness.application.service import RunService
from review_harness.infrastructure.artifacts import ArtifactService
from review_harness.infrastructure.persistence.database import Database
from review_harness.infrastructure.persistence.store import RunStore
from review_harness.infrastructure.persistence.workspaces import WorkspaceStore
from review_harness.infrastructure.workspaces import WorkspaceManager
from review_harness.runtime.executor import DeterministicExecutor
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
    try:
        if (
            await database.schema_ready()
            and await database.writable()
            and app.state.storage_probe()
        ):
            await recover_runs(store, app.state.service, workspaces)
            await app.state.artifacts.audit()
            app.state.recovery_complete = True
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
        await database.close()

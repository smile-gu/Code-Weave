from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError

from review_harness.api.auth import InternalAuthentication
from review_harness.api.problem import problem
from review_harness.api.routes import router
from review_harness.application.hub import EventHub
from review_harness.bootstrap import lifespan
from review_harness.config import Settings
from review_harness.domain.model import HarnessError
from review_harness.infrastructure.paths import storage_accessible


def create_app(
    settings: Settings | None = None,
    *,
    service=None,
    hub=None,
    executor=None,
    provider_transport=None,
) -> FastAPI:
    settings = settings or Settings()
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.settings = settings
    app.state.service = service
    app.state.injected_service = service is not None
    app.state.hub = hub or EventHub()
    app.state.executor = executor
    app.state.database = None
    app.state.supervisor = None
    app.state.recovery_complete = False
    app.state.checkpoints = None
    app.state.real_adapter = None
    app.state.real_executor_ready = False
    app.state.multipass_executor_ready = False
    app.state.real_failure_code = None
    app.state.provider_transport = provider_transport
    app.state.graph_capabilities = {
        "checkpoint": "DISABLED",
        "model": "TEST_ONLY",
        "productionGraphEnabled": False,
    }
    app.state.storage_probe = lambda: storage_accessible(
        settings.snapshot_root, settings.execution_root, settings.artifact_root
    )

    app.add_middleware(
        InternalAuthentication, token=settings.internal_bearer_token.get_secret_value()
    )

    @app.exception_handler(HarnessError)
    async def harness_error(request, error):
        return problem(request, error.status, error.code, error.detail)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, error):
        return problem(
            request, 422, "REQUEST_VALIDATION_FAILED", "请求格式或字段不符合协议"
        )

    @app.exception_handler(Exception)
    async def internal_error(request, error):
        return problem(
            request, 500, "REVIEW_HARNESS_INTERNAL_ERROR", "运行服务出现内部错误"
        )

    app.include_router(router)
    return app

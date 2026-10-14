"""Offline-only State 2 fixtures within the versioned single-pass executor.

These tests retain the historical raw-reference gates. They do not resume a
saved Run or change the production State 3/4 protocol dispatch.
"""

from contextlib import asynccontextmanager

from review_harness.api.app import create_app
from review_harness.domain.multipass_state import MultipassState
from review_harness.domain.real_graph import RealReviewGraphState
from review_harness.runtime.multipass import VersionedReviewExecutor


def legacy_protocol_app(settings, *, provider_transport):
    app = create_app(settings, provider_transport=provider_transport)
    original = app.router.lifespan_context

    @asynccontextmanager
    async def historical_fixture(application):
        async with original(application):
            executor = application.state.supervisor.executor
            assert isinstance(executor, VersionedReviewExecutor)
            assert executor.single.engine is not executor.multipass.engine
            assert executor.multipass.engine.state_type is MultipassState
            executor.single.engine.state_type = RealReviewGraphState
            assert application.state.supervisor.executor is executor
            assert executor.multipass.engine.state_type is MultipassState
            yield

    app.router.lifespan_context = historical_fixture
    return app

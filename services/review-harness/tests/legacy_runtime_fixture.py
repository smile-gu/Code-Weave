"""Offline-only State 2/3 fixtures within the versioned single-pass executor.

These tests retain the historical raw-reference gates. They do not resume a
saved Run or change the production State 5/6 protocol dispatch.
"""

from contextlib import asynccontextmanager

from review_harness.api.app import create_app
from review_harness.domain.position import PositionMultipassState
from review_harness.domain.real_graph import (
    HandleReviewGraphState,
    RealReviewGraphState,
)
from review_harness.runtime.multipass import VersionedReviewExecutor


def _historical_app(settings, *, provider_transport, state_type):
    app = create_app(settings, provider_transport=provider_transport)
    original = app.router.lifespan_context

    @asynccontextmanager
    async def historical_fixture(application):
        async with original(application):
            executor = application.state.supervisor.executor
            assert isinstance(executor, VersionedReviewExecutor)
            assert executor.single.engine is not executor.multipass.engine
            assert executor.multipass.engine.state_type is PositionMultipassState
            executor.single.engine.state_type = state_type
            assert application.state.supervisor.executor is executor
            assert executor.multipass.engine.state_type is PositionMultipassState
            yield

    app.router.lifespan_context = historical_fixture
    return app


def legacy_protocol_app(settings, *, provider_transport):
    return _historical_app(
        settings, provider_transport=provider_transport, state_type=RealReviewGraphState
    )


def quote_protocol_app(settings, *, provider_transport):
    """Explicit State3 compatibility fixture; never infer a protocol from a reply."""
    return _historical_app(
        settings,
        provider_transport=provider_transport,
        state_type=HandleReviewGraphState,
    )

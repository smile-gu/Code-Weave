"""Offline-only State 2 fixtures; production bootstrap always uses State 3.

These tests retain the historical raw-reference gates. They do not resume a
saved Run or certify the current local-handle model protocol.
"""

from contextlib import asynccontextmanager

from review_harness.api.app import create_app
from review_harness.domain.real_graph import RealReviewGraphState


def legacy_protocol_app(settings, *, provider_transport):
    app = create_app(settings, provider_transport=provider_transport)
    original = app.router.lifespan_context

    @asynccontextmanager
    async def historical_fixture(application):
        async with original(application):
            application.state.supervisor.executor.engine.state_type = (
                RealReviewGraphState
            )
            yield

    app.router.lifespan_context = historical_fixture
    return app

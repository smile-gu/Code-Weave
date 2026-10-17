import logging
import secrets
from uuid import uuid4

from starlette.requests import Request

from review_harness.api.problem import problem


class InternalAuthentication:
    """Pure ASGI middleware preserves streaming disconnect/cancellation semantics."""

    def __init__(self, app, token):
        self.app = app
        self.expected = ("Bearer " + token).encode()

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            supplied = dict(scope["headers"]).get(b"authorization", b"")
            if not secrets.compare_digest(supplied, self.expected):
                response = problem(
                    Request(scope),
                    401,
                    "REVIEW_HARNESS_UNAUTHENTICATED",
                    "内部凭证缺失或无效",
                )
                await response(scope, receive, send)
                return
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        started, completed = False, False

        async def guarded_send(message):
            nonlocal started, completed
            if message["type"] == "http.response.start":
                started = True
            elif message["type"] == "http.response.body" and not message.get(
                "more_body", False
            ):
                completed = True
            await send(message)

        try:
            await self.app(scope, receive, guarded_send)
        except Exception:
            # ServerErrorMiddleware rethrows after responding; contain it before
            # Uvicorn logs a potentially sensitive raw exception/SQL statement.
            if not started:
                await problem(
                    Request(scope),
                    500,
                    "REVIEW_HARNESS_INTERNAL_ERROR",
                    "运行服务出现内部错误",
                )(scope, receive, send)
            elif not completed:
                logging.getLogger("review_harness").error(
                    '{"diagnosticId":"%s","errorCode":"REVIEW_EVENT_STREAM_FAILED"}',
                    str(uuid4()),
                )
                try:
                    await send(
                        {"type": "http.response.body", "body": b"", "more_body": False}
                    )
                except OSError:
                    pass

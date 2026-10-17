import logging
from uuid import uuid4

from fastapi import Request
from starlette.responses import JSONResponse

logger = logging.getLogger("review_harness")


def problem(request: Request, status: int, code: str, detail: str) -> JSONResponse:
    diagnostic_id = str(uuid4())
    # Only bounded identities and codes enter logs; never request/exception text.
    logger.warning(
        '{"diagnosticId":"%s","errorCode":"%s","status":%d}',
        diagnostic_id,
        code,
        status,
    )
    return JSONResponse(
        {
            "type": "urn:code-weave:problem:" + code.lower().replace("_", "-"),
            "title": "Review Harness 请求失败",
            "status": status,
            "detail": detail,
            "instance": request.url.path[:512],
            "errorCode": code,
            "diagnosticId": diagnostic_id,
        },
        status_code=status,
        media_type="application/problem+json",
    )

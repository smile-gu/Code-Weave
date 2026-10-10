import json

from fastapi import APIRouter, Request
from pydantic import ValidationError
from starlette.responses import Response

from review_harness.api.dto import CreateRun
from review_harness.api.sse import BoundedStreamingResponse, parse_cursor, stream_events
from review_harness.application.readiness import readiness
from review_harness.domain.model import HarnessError, canonical, valid_uuid

router = APIRouter(prefix="/internal/v1")


def json_response(value, status=200):
    return Response(
        canonical(value),
        status_code=status,
        media_type="application/json; charset=utf-8",
    )


def service(request):
    value = request.app.state.service
    if value is None:
        raise HarnessError("REVIEW_HARNESS_NOT_READY", 503, "运行服务尚未就绪")
    return value


def run_identity(run_id):
    if not valid_uuid(run_id):
        raise HarnessError("REQUEST_VALIDATION_FAILED", 422, "运行身份格式无效")
    return run_id


@router.post("/review-runs")
async def create_run(request: Request):
    # 首次接受返回 202；同请求重试返回 200；同 runId 不同请求返回 409。
    body = bytearray()
    async for chunk in request.stream():
        body.extend(chunk)
        if len(body) > 65536:
            raise HarnessError("REVIEW_REQUEST_TOO_LARGE", 413, "请求超过大小上限")
    try:
        value = json.loads(body)
        if (
            isinstance(value, dict)
            and isinstance(value.get("protocolVersion"), str)
            and value["protocolVersion"] != "1.0"
        ):
            raise HarnessError("PROTOCOL_VERSION_UNSUPPORTED", 400, "协议版本不受支持")
        dto = CreateRun.model_validate(value)
    except (ValidationError, ValueError, UnicodeError):
        raise HarnessError(
            "REQUEST_VALIDATION_FAILED", 422, "请求格式或字段不符合协议"
        ) from None
    if request.app.state.database is not None:
        _, ready = await readiness(request.app)
        if not ready:
            raise HarnessError("REVIEW_HARNESS_NOT_READY", 503, "运行服务尚未就绪")
    run, status = await service(request).create(dto.model_dump())
    return json_response(run.wire(), status)


@router.get("/review-runs/{run_id}")
async def get_run(run_id: str, request: Request):
    run = await service(request).store.get_run(run_identity(run_id))
    return json_response(run.wire())


@router.post("/review-runs/{run_id}/cancel")
async def cancel_run(run_id: str, request: Request):
    # RUNNING 首次取消返回 202；其他状态返回 200 和真实状态，不覆盖终态。
    run, status = await service(request).cancel(run_identity(run_id))
    return json_response(run.wire(), status)


@router.get("/review-runs/{run_id}/result")
async def get_result(run_id: str, request: Request):
    original, digest = await service(request).store.get_result(run_identity(run_id))
    return Response(
        original,
        media_type="application/json; charset=utf-8",
        headers={"ETag": '"' + digest + '"', "X-Content-SHA256": digest},
    )


@router.get("/review-runs/{run_id}/events")
async def get_events(run_id: str, request: Request):
    store = service(request).store
    run = await store.get_run(run_identity(run_id))
    cursor = parse_cursor(request.headers.get("last-event-id"), run.last_event_sequence)
    return BoundedStreamingResponse(
        stream_events(
            request,
            store,
            request.app.state.hub,
            run_id,
            cursor,
            request.app.state.settings,
        ),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.get("/health")
async def health(request: Request):
    value, ready = await readiness(request.app)
    return json_response(value, 200 if ready else 503)


@router.get("/review-runs/{run_id}/artifacts/{artifact_id}")
async def artifact_metadata(run_id: str, artifact_id: str, request: Request):
    value = await request.app.state.artifacts.metadata(
        run_identity(run_id), artifact_id
    )
    return json_response(value.wire())


@router.get("/review-runs/{run_id}/artifacts/{artifact_id}/content")
async def artifact_content(
    run_id: str, artifact_id: str, request: Request, cursor: str | None = None
):
    value = await request.app.state.artifacts.content(
        run_identity(run_id), artifact_id, cursor
    )
    return json_response(value.model_dump())

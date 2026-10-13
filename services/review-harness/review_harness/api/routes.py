import asyncio

from fastapi import APIRouter, Request
from pydantic import ValidationError
from starlette.responses import Response

from review_harness.api.dto import CreateRun, CreateRunV11
from review_harness.api.sse import BoundedStreamingResponse, parse_cursor, stream_events
from review_harness.application.readiness import readiness, real_failures
from review_harness.domain.model import HarnessError, canonical, valid_uuid
from review_harness.domain.model_call import ReviewCapabilities
from review_harness.domain.multipass import MultipassCapabilities
from review_harness.domain.provider import strict_json

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
        if len(body) + len(chunk) > 65536:
            raise HarnessError("REVIEW_REQUEST_TOO_LARGE", 413, "请求超过大小上限")
        body.extend(chunk)
    try:
        value = strict_json(bytes(body), limit=65536)
        if (
            isinstance(value, dict)
            and isinstance(value.get("protocolVersion"), str)
            and value["protocolVersion"] not in {"1.0", "1.1"}
        ):
            raise HarnessError("PROTOCOL_VERSION_UNSUPPORTED", 400, "协议版本不受支持")
        if isinstance(value, dict) and value.get("protocolVersion") == "1.1":
            CreateRunV11.model_validate(value)
            # Contract-only slice: no fee ledger/multipass executor exists yet.
            raise HarnessError(
                "EXECUTION_CONTRACT_UNSUPPORTED", 400, "多Pass运行尚未启用"
            )
        dto = CreateRun.model_validate(value)
    except (ValidationError, ValueError, UnicodeError):
        raise HarnessError(
            "REQUEST_VALIDATION_FAILED", 422, "请求格式或字段不符合协议"
        ) from None
    requested_contract = (
        dto.executionContract.model_dump()
        if dto.executionContract
        else {"executionMode": "SIMULATED", "resultVersion": "1.0"}
    )
    if requested_contract != request.app.state.settings.execution_contract:
        raise HarnessError(
            "EXECUTION_CONTRACT_UNSUPPORTED",
            400,
            "当前服务不支持请求的执行模式和结果版本",
        )
    if (
        request.app.state.database is not None
        or request.app.state.settings.execution_mode == "REAL"
    ):
        _, ready = await readiness(request.app)
        if not ready:
            raise HarnessError("REVIEW_HARNESS_NOT_READY", 503, "运行服务尚未就绪")
    run, status = await service(request).create(dto.model_dump(exclude_none=True))
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


@router.get("/graph-capabilities")
async def graph_capabilities(request: Request):
    value = dict(request.app.state.graph_capabilities)
    checkpoints = request.app.state.checkpoints
    if checkpoints is not None:
        value["checkpoint"] = "READY" if await checkpoints.ready() else "NOT_READY"
    return json_response(value)


@router.get("/review-capabilities")
async def review_capabilities(request: Request):
    version = request.query_params.get("protocolVersion", "1.0")
    if version == "1.1":
        value = MultipassCapabilities(
            protocolVersion="1.1",
            configuredContract={
                "executionMode": request.app.state.settings.execution_mode,
                "resultVersion": "3.0",
            },
            planVersions=["1.0"],
            feeReservation="NOT_READY",
            status="NOT_READY",
            failureCodes=["MULTIPASS_RUNTIME_NOT_READY"],
        )
        return json_response(value.model_dump())
    if version != "1.0":
        raise HarnessError("PROTOCOL_VERSION_UNSUPPORTED", 400, "协议版本不受支持")
    _, ready = await readiness(request.app)
    value = ReviewCapabilities.model_validate(
        {
            "protocolVersion": "1.0",
            "configuredContract": request.app.state.settings.execution_contract,
            "status": "READY" if ready else "NOT_READY",
            "provider": "DEEPSEEK"
            if request.app.state.settings.execution_mode == "REAL"
            else None,
            "model": "deepseek-flash"
            if request.app.state.settings.execution_mode == "REAL"
            else None,
            "meterVersion": "v4.1-2cba9e42-v1"
            if request.app.state.settings.execution_mode == "REAL"
            else None,
            "failureCodes": await real_failures(request.app)
            or ([] if ready else ["REVIEW_HARNESS_NOT_READY"]),
        }
    )
    return json_response(value.model_dump())


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


@router.get("/review-runs/{run_id}/evidence/{evidence_id}/materials")
async def evidence_materials(run_id: str, evidence_id: str, request: Request):
    run = await service(request).store.get_run(run_identity(run_id))
    if not valid_uuid(evidence_id):
        raise HarnessError("REQUEST_VALIDATION_FAILED", 422, "证据身份格式无效")
    evidence = request.app.state.evidence
    record = await evidence.get(run, evidence_id)
    value = {
        "evidenceId": evidence_id,
        "runId": run.run_id,
        "snapshotId": run.snapshot_id,
        "materials": await evidence.materials(run, record.sourceRefs, asyncio.Event()),
    }
    return json_response(value)

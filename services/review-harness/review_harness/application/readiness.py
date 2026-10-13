async def real_failures(app):
    if app.state.settings.execution_mode != "REAL":
        return []
    failures = []
    if app.state.real_failure_code:
        failures.append(app.state.real_failure_code)
    if (
        app.state.settings.deepseek_api_key is None
        or not app.state.settings.deepseek_api_key.get_secret_value().strip()
    ):
        failures.append("PROVIDER_CREDENTIAL_UNAVAILABLE")
    checkpoints = app.state.checkpoints
    if checkpoints is None or not await checkpoints.ready():
        failures.append("CHECKPOINT_UNAVAILABLE")
    adapter = app.state.real_adapter
    if adapter is not None:
        try:
            adapter.ready()
        except Exception as error:
            failures.append(getattr(error, "code", "CONTEXT_MODEL_CAPACITY_UNKNOWN"))
    elif not failures:
        failures.append("CONTEXT_MODEL_CAPACITY_UNKNOWN")
    if not app.state.real_executor_ready:
        failures.append("REVIEW_EXECUTOR_UNAVAILABLE")
    return failures


async def readiness(app):
    db = app.state.database
    supervisor = app.state.supervisor
    schema = await db.schema_ready() if db else False
    database = await db.writable() if db and schema else False
    checks = {
        "database": database,
        "schema": schema,
        "recovery": app.state.recovery_complete,
        "supervisor": supervisor.healthy if supervisor else False,
        "snapshotStore": app.state.storage_probe(),
    }
    ready = all(checks.values())
    if await real_failures(app):
        checks["supervisor"] = False
        ready = False
    return {
        "protocolVersion": "1.0",
        "status": "READY" if ready else "NOT_READY",
        "acceptingRuns": ready,
        "checks": {
            key: "READY" if value else "NOT_READY" for key, value in checks.items()
        },
    }, ready

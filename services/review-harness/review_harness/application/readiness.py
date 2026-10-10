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
    return {
        "protocolVersion": "1.0",
        "status": "READY" if ready else "NOT_READY",
        "acceptingRuns": ready,
        "checks": {
            key: "READY" if value else "NOT_READY" for key, value in checks.items()
        },
    }, ready

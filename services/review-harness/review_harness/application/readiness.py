import os


def snapshot_accessible(path):
    try:
        if not path.is_dir() or not os.access(path, os.R_OK):
            return False
        with os.scandir(path) as entries:
            next(entries, None)
        return True
    except OSError:
        return False


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
        "snapshotStore": snapshot_accessible(app.state.settings.snapshot_root),
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

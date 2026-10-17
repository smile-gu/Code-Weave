from review_harness.domain.model import Failure, HarnessError
from review_harness.domain.snapshot import WorkspaceError


async def recover_runs(store, service, workspaces):
    """Establish all Session isolation before any pending task can be scheduled."""
    await workspaces.audit_ownership()
    async for run in store.scan_runs():
        try:
            record = await workspaces.resources.get(run)
            if run.status == "PENDING" and (
                record is not None or workspaces.has_directories(run)
            ):
                await store.isolate_run(run.run_id)
            elif (
                run.resource_state in {"RELEASED", "NOT_ACQUIRED"}
                and record
                and record.state != "REMOVED"
            ):
                await store.isolate_run(run.run_id)
        except Exception as error:
            if (
                isinstance(error, HarnessError)
                and error.code == "REVIEW_RUN_INTEGRITY_ERROR"
            ):
                await store.isolate_run(run.run_id)
            else:
                raise
    async for run in store.scan_runs():
        if run.status == "PENDING":
            await service.ensure_scheduled(run)
            continue
        if run.protocol_version == "1.1" and run.status in {"RUNNING", "CANCELLING"}:
            from review_harness.infrastructure.persistence.passes import PassStore

            await PassStore(store.database, store).interrupt(run.run_id)
        try:
            await workspaces.cleanup(run)
        except Exception as error:
            if (
                isinstance(error, HarnessError)
                and error.code == "REVIEW_RUN_INTEGRITY_ERROR"
            ):
                await store.isolate_run(run.run_id)
            elif isinstance(error, WorkspaceError):
                if run.status in {"RUNNING", "CANCELLING"}:
                    await store.fail_cleanup_uncertain(
                        run.run_id, Failure.make("EXECUTION_WORKSPACE_CLEANUP_FAILED")
                    )
                elif run.resource_state != "UNCERTAIN":
                    await store.isolate_run(run.run_id)
            else:
                raise
            continue
        if run.status == "RUNNING":
            await store.complete_failure_safe(
                run.run_id,
                Failure.make("REVIEW_RUN_INTERRUPTED"),
                "REVIEW_RUN_INTERRUPTED",
            )
        elif run.status == "CANCELLING":
            await store.complete_cancel(run.run_id, "REVIEW_CANCELLATION_RECOVERED")
        elif run.status == "FAILED" and run.resource_state == "UNCERTAIN":
            await store.confirm_resources_released(
                run.run_id, "REVIEW_RESOURCES_CONFIRMED"
            )

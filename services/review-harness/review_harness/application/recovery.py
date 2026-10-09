from review_harness.domain.model import Failure


async def recover_runs(store, service):
    """Only the simulator proves process-local resources vanish after a restart."""
    async for run in store.scan_runs():
        if run.status == "RUNNING":
            await store.complete_failure_safe(
                run.run_id,
                Failure.make("REVIEW_RUN_INTERRUPTED"),
                "REVIEW_RUN_INTERRUPTED",
            )
        elif run.status == "CANCELLING":
            await store.complete_cancel(run.run_id, "REVIEW_CANCELLATION_RECOVERED")
        elif run.status == "PENDING":
            await service.ensure_scheduled(run)

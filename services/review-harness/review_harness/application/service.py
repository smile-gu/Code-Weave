from review_harness.application.ports import RunStorePort, SchedulerPort
from review_harness.domain.model import Failure, HarnessError


class RunService:
    def __init__(self, store: RunStorePort, scheduler: SchedulerPort):
        self.store, self.scheduler = store, scheduler

    async def ensure_scheduled(self, run):
        if run.status == "PENDING" and not self.scheduler.ensure_submitted(run.run_id):
            try:
                run, _ = await self.store.fail_before_start(
                    run.run_id, Failure.make("REVIEW_EXECUTOR_REJECTED")
                )
            except Exception:
                self.scheduler.accepting = False
                raise HarnessError(
                    "REVIEW_RUN_STATE_WRITE_UNCERTAIN",
                    503,
                    "无法确认排队拒绝后的运行状态",
                ) from None
        return run

    async def create(self, payload):
        if not self.scheduler.accepting:
            raise HarnessError("REVIEW_HARNESS_NOT_READY", 503, "运行服务尚未就绪")
        try:
            run, created = await self.store.create_run(payload)
        except HarnessError as error:
            if error.code == "REVIEW_RUN_STATE_WRITE_UNCERTAIN":
                self.scheduler.accepting = False
            raise
        run = await self.ensure_scheduled(run)
        return run, 202 if created else 200

    async def cancel(self, run_id):
        try:
            return await self._cancel(run_id)
        except HarnessError as error:
            if error.code == "REVIEW_RUN_STATE_WRITE_UNCERTAIN":
                self.scheduler.accepting = False
            raise

    async def _cancel(self, run_id):
        run = await self.store.get_run(run_id)
        if run.status == "PENDING":
            run, _ = await self.store.cancel_pending(run_id)
        # A worker may have started between reading PENDING and cancelling it.
        if run.status == "RUNNING":
            run, changed = await self.store.request_cancel(run_id)
            if changed:
                self.scheduler.signal_cancel(run_id)
                return run, 202
        return run, 200

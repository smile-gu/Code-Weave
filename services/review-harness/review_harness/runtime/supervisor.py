import asyncio
import logging

from review_harness.application.ports import RunStorePort
from review_harness.domain.model import Failure, HarnessError, SessionBlocked
from review_harness.domain.snapshot import WorkCancelled, WorkspaceError

logger = logging.getLogger("review_harness")


class Supervisor:
    def __init__(self, store: RunStorePort, executor, capacity: int, workspaces):
        self.store, self.executor = store, executor
        self.workspaces = workspaces
        self.queue: asyncio.Queue[str] = asyncio.Queue(maxsize=capacity)
        self.scheduled_run_ids: set[str] = set()
        self.signals: dict[str, asyncio.Event] = {}
        self.accepting = False
        self._preparing = True
        self._stopping = False
        self._task: asyncio.Task | None = None

    @property
    def healthy(self):
        return self.accepting and self._task is not None and not self._task.done()

    def ensure_submitted(self, run_id: str) -> bool:
        if self._stopping or not (self.accepting or self._preparing):
            return False
        if run_id in self.scheduled_run_ids:
            return True
        try:
            self.queue.put_nowait(run_id)
        except asyncio.QueueFull:
            return False
        self.scheduled_run_ids.add(run_id)
        return True

    def signal_cancel(self, run_id: str):
        signal = self.signals.get(run_id)
        if signal:
            signal.set()

    def start(self):
        if self._task is not None:
            raise RuntimeError("Supervisor is already started")
        self._preparing = False
        self.accepting = True
        self._task = asyncio.create_task(self._work(), name="review-harness-worker")

    async def join(self):
        await self.queue.join()

    async def _work(self):
        try:
            while not self._stopping and self.accepting:
                run_id = await self.queue.get()
                try:
                    if self._stopping or not self.accepting:
                        return
                    signal = self.signals[run_id] = asyncio.Event()
                    await self._run_one(run_id, signal)
                except HarnessError as error:
                    if error.code == "REVIEW_RUN_INTEGRITY_ERROR":
                        # RunStore has quarantined this Run and its Session.
                        # One damaged record must not stop unrelated simulation runs.
                        await self.store.isolate_run(run_id)
                        continue
                    self.accepting = False
                    logger.error('{"runId":"%s","errorCode":"%s"}', run_id, error.code)
                    return
                except Exception:
                    self.accepting = False
                    logger.error(
                        '{"runId":"%s","errorCode":"REVIEW_HARNESS_INTERNAL_ERROR"}',
                        run_id,
                    )
                    return
                finally:
                    self.signals.pop(run_id, None)
                    self.scheduled_run_ids.discard(run_id)
                    self.queue.task_done()
        finally:
            self.accepting = False

    async def _run_one(self, run_id, signal):
        expected = getattr(self.executor, "expected_contract", None)
        if expected is not None:
            pending = await self.store.get_run(run_id)
            if pending.status == "PENDING" and pending.execution_contract != expected:
                await self.store.fail_before_start(
                    run_id, Failure.make("EXECUTION_CONTRACT_UNSUPPORTED")
                )
                return
        try:
            run, acquired = await self.store.start_run(run_id)
        except SessionBlocked:
            # Keep PENDING and all protection evidence; this is not a global fault
            # or an execution failure. A later retry must pass the same durable gate.
            return
        except HarnessError as error:
            if error.code in {
                "REVIEW_RUN_STATE_WRITE_UNCERTAIN",
                "REVIEW_RUN_INTEGRITY_ERROR",
            }:
                raise
            await self.store.fail_before_start(
                run_id, Failure.make("REVIEW_HARNESS_INTERNAL_ERROR")
            )
            return
        if not acquired:
            return
        # A cancellation can commit while start_run is returning; reread before execution.
        if (await self.store.get_run(run_id)).status == "CANCELLING":
            signal.set()
        cancelled, failure, context = False, None, None
        review_package = None
        try:
            async with asyncio.timeout(
                self.executor.timeout_for(run)
                if hasattr(self.executor, "timeout_for")
                else getattr(self.executor, "wallclock_seconds", None)
            ):
                context = await self.workspaces.prepare(run, signal)
                if (await self.store.get_run(run_id)).status == "CANCELLING":
                    signal.set()
                if signal.is_set():
                    raise WorkCancelled()
                review_package = await self.executor.execute(context, signal)
                if not signal.is_set():
                    await self.workspaces.verify(context, signal)
        except TimeoutError:
            signal.set()
            if hasattr(self.executor, "abort"):
                await self.executor.abort(run_id)
            failure = Failure.make("CONTEXT_RUN_TIMEOUT")
        except WorkCancelled:
            cancelled = True
        except WorkspaceError as error:
            failure = Failure.make(error.code)
        except HarnessError as error:
            if error.code in {
                "REVIEW_RUN_STATE_WRITE_UNCERTAIN",
                "REVIEW_RUN_INTEGRITY_ERROR",
            }:
                raise
            failure = Failure.make(
                error.code
                if error.code.startswith(
                    (
                        "ARTIFACT_",
                        "CONTEXT_",
                        "COMPACTION_",
                        "CHECKPOINT_",
                        "PROVIDER_",
                        "EVIDENCE_",
                        "FINDING_",
                        "FEE_",
                        "PASS_",
                        "PRICE_",
                        "RUN_",
                    )
                )
                else "REVIEW_HARNESS_INTERNAL_ERROR"
            )
        except Exception:
            failure = Failure.make("REVIEW_EXECUTION_FAILED")
        # CancelledError deliberately escapes: shutdown cannot invent cleanup evidence.
        cleanup_failure = None
        if context is not None:
            try:
                await self.executor.cleanup(context)
            except Exception:
                cleanup_failure = "REVIEW_RESOURCE_CLEANUP_FAILED"
        try:
            await self.workspaces.cleanup(run)
        except WorkspaceError:
            cleanup_failure = "EXECUTION_WORKSPACE_CLEANUP_FAILED"
        if cleanup_failure:
            if failure is not None:
                logger.warning(
                    '{"runId":"%s","diagnosticId":"%s","originalFailureCode":"%s","cleanupFailureCode":"%s"}',
                    run_id,
                    failure.diagnostic_id,
                    failure.code,
                    cleanup_failure,
                )
            await self.store.fail_cleanup_uncertain(
                run_id, Failure.make(cleanup_failure)
            )
            return
        if failure:
            await self.store.complete_failure_safe(run_id, failure)
            return
        if cancelled or signal.is_set():
            current = await self.store.get_run(run_id)
            if current.status == "RUNNING":
                # Graceful shutdown interrupted the simulator without a user cancellation.
                await self.store.complete_failure_safe(
                    run_id, Failure.make("REVIEW_RUN_INTERRUPTED")
                )
            else:
                await self.store.complete_cancel(run_id)
            return
        try:
            if review_package is None:
                current, completed = await self.store.complete_success(run_id)
            else:
                current, completed = await self.store.complete_success(
                    run_id, review_package
                )
        except HarnessError as error:
            if error.code == "REVIEW_RUN_STATE_WRITE_UNCERTAIN":
                raise
            await self.store.complete_failure_safe(
                run_id, Failure.make("REVIEW_RESULT_PERSISTENCE_FAILED")
            )
            return
        if not completed and current.status == "CANCELLING":
            await self.store.complete_cancel(run_id)

    async def stop(self, grace: float):
        self.accepting = False
        self._preparing = False
        self._stopping = True
        for signal in self.signals.values():
            signal.set()
        if self._task is None:
            return
        if not self.signals and not self._task.done():
            self._task.cancel()
        try:
            await asyncio.wait_for(asyncio.shield(self._task), grace)
        except (TimeoutError, asyncio.CancelledError):
            self._task.cancel()
            await asyncio.gather(self._task, return_exceptions=True)

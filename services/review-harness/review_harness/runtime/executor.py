import asyncio

from review_harness.domain.snapshot import WorkCancelled


class ExecutionCancelled(WorkCancelled):
    pass


class DeterministicExecutor:
    """Three bounded phases, yielding only the frozen empty Result candidate."""

    async def execute(self, run, cancel: asyncio.Event):
        for _ in range(3):
            try:
                await asyncio.wait_for(cancel.wait(), timeout=0.02)
            except TimeoutError:
                continue
            raise ExecutionCancelled()

    async def cleanup(self, run):
        # This executor acquires no external resources. Never reuse this proof for tools.
        return

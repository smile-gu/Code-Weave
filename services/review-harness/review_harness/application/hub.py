import asyncio
from contextlib import contextmanager


class EventHub:
    """Wakeups only: one bit per live subscription, never an event backlog."""

    def __init__(self):
        self._subscriptions: dict[str, set[asyncio.Event]] = {}

    @contextmanager
    def subscribe(self, run_id: str):
        signal = asyncio.Event()
        listeners = self._subscriptions.setdefault(run_id, set())
        listeners.add(signal)
        try:
            yield signal
        finally:
            listeners.remove(signal)
            if not listeners:
                self._subscriptions.pop(run_id, None)

    def notify(self, run_id: str):
        for signal in self._subscriptions.get(run_id, ()):
            signal.set()

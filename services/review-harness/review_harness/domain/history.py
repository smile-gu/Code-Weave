import json
import re
from dataclasses import dataclass

from review_harness.domain.model import (
    TERMINAL,
    Run,
    canonical,
    integrity_error,
    valid_time,
)

EVENT_SOURCES = {
    "REVIEW_RUN_ACCEPTED": {None},
    "REVIEW_RUN_STARTED": {"PENDING"},
    "REVIEW_RUN_CANCELLING": {"RUNNING"},
    "REVIEW_RUN_SUCCEEDED": {"RUNNING"},
    "REVIEW_RUN_FAILED": {"PENDING", "RUNNING", "CANCELLING"},
    "REVIEW_RUN_CANCELLED": {"PENDING", "CANCELLING"},
    "REVIEW_RUN_RESOURCES_RELEASED": {"FAILED"},
}
EVENT_TARGETS = {
    "REVIEW_RUN_ACCEPTED": "PENDING",
    "REVIEW_RUN_STARTED": "RUNNING",
    "REVIEW_RUN_CANCELLING": "CANCELLING",
    "REVIEW_RUN_SUCCEEDED": "SUCCEEDED",
    "REVIEW_RUN_FAILED": "FAILED",
    "REVIEW_RUN_CANCELLED": "CANCELLED",
    "REVIEW_RUN_RESOURCES_RELEASED": "FAILED",
}


@dataclass(frozen=True)
class Event:
    run_id: str
    sequence: int
    event_type: str
    occurred_at: str
    payload: bytes

    def wire(self) -> dict:
        return {
            "protocolVersion": "1.0",
            "runId": self.run_id,
            "sequence": self.sequence,
            "eventType": self.event_type,
            "occurredAt": self.occurred_at,
            "payload": json.loads(self.payload),
        }


class HistoryValidator:
    """Incrementally replay bounded pages and compare the final durable snapshot."""

    def __init__(self, run: Run):
        self.run = run
        self.sequence = 0
        self.occurred_at = run.created_at
        self.status = None
        self.resource = None
        self.terminal_count = 0

    def accept(self, event: Event) -> None:
        if (
            event.event_type not in EVENT_SOURCES
            or self.status not in EVENT_SOURCES[event.event_type]
            or event.run_id != self.run.run_id
            or event.sequence != self.sequence + 1
            or not valid_time(event.occurred_at)
            or event.occurred_at < self.occurred_at
        ):
            raise integrity_error()
        payload = json.loads(event.payload)
        if not isinstance(payload, dict) or canonical(payload) != event.payload:
            raise integrity_error()
        target = EVENT_TARGETS[event.event_type]
        keys = {"status", "resourceState"}
        resources = {
            "PENDING": {"NOT_ACQUIRED"},
            "RUNNING": {"ACTIVE"},
            "CANCELLING": {"RELEASING"},
            "SUCCEEDED": {"RELEASED"},
            "FAILED": {"NOT_ACQUIRED"}
            if self.status == "PENDING"
            else {"RELEASED", "UNCERTAIN"},
            "CANCELLED": {"NOT_ACQUIRED"} if self.status == "PENDING" else {"RELEASED"},
        }[target]
        if event.event_type == "REVIEW_RUN_SUCCEEDED":
            keys.add("result")
            if payload.get("result") != self.run.descriptor:
                raise integrity_error()
        elif event.event_type == "REVIEW_RUN_FAILED":
            keys.add("failure")
            if (
                not self.run.failure
                or payload.get("failure") != self.run.failure.wire()
            ):
                raise integrity_error()
        elif event.event_type == "REVIEW_RUN_RESOURCES_RELEASED":
            keys.add("previousResourceState")
            resources = {"RELEASED"}
            if (
                self.resource != "UNCERTAIN"
                or payload.get("previousResourceState") != "UNCERTAIN"
            ):
                raise integrity_error()
            if "recoveryCode" in payload:
                keys.add("recoveryCode")
                code = payload["recoveryCode"]
                if (
                    not isinstance(code, str)
                    or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,127}", code)
                    or code != self.run.recovery_code
                ):
                    raise integrity_error()
        if (
            set(payload) != keys
            or payload["status"] != target
            or payload["resourceState"] not in resources
        ):
            raise integrity_error()
        if event.event_type in {
            "REVIEW_RUN_SUCCEEDED",
            "REVIEW_RUN_FAILED",
            "REVIEW_RUN_CANCELLED",
        }:
            self.terminal_count += 1
            if event.occurred_at != self.run.finished_at:
                raise integrity_error()
        if (
            event.event_type == "REVIEW_RUN_ACCEPTED"
            and event.occurred_at != self.run.created_at
        ):
            raise integrity_error()
        if (
            event.event_type == "REVIEW_RUN_STARTED"
            and event.occurred_at != self.run.started_at
        ):
            raise integrity_error()
        self.sequence, self.occurred_at = event.sequence, event.occurred_at
        self.status, self.resource = target, payload["resourceState"]

    def finish(self) -> None:
        if (
            self.sequence != self.run.last_event_sequence
            or self.status != self.run.status
            or self.resource != self.run.resource_state
            or self.occurred_at != self.run.updated_at
            or self.terminal_count != int(self.run.status in TERMINAL)
        ):
            raise integrity_error()

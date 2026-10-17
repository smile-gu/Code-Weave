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

    def wire(self, protocol_version="1.0") -> dict:
        return {
            "protocolVersion": protocol_version,
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
        self.passes = None

    def accept(self, event: Event) -> None:
        if event.event_type == "REVIEW_PASS_UPDATED":
            self._accept_passes(event)
            return
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

    def _accept_passes(self, event):
        from review_harness.domain.multipass import PASS_ORDER
        from review_harness.domain.pass_fact import PassFact

        try:
            payload = json.loads(event.payload)
            if any(
                set(f) != set(PassFact.model_fields) - {"result"}
                for f in payload["passes"]
            ):
                raise ValueError()
            facts = [
                PassFact.model_validate({**f, "result": None})
                for f in payload["passes"]
            ]
            selected = json.loads(self.run.request_payload)["reviewPlan"][
                "selectedPasses"
            ]
            if (
                self.run.protocol_version != "1.1"
                or self.status not in {"RUNNING", "CANCELLING"}
                or event.run_id != self.run.run_id
                or event.sequence != self.sequence + 1
                or not valid_time(event.occurred_at)
                or event.occurred_at < self.occurred_at
                or canonical(payload) != event.payload
                or set(payload) != {"status", "resourceState", "passes"}
                or (payload["status"], payload["resourceState"])
                != (self.status, self.resource)
                or [f.passId for f in facts] != list(PASS_ORDER)
                or any(f.runId != self.run.run_id for f in facts)
                or any(f.selected != (f.passId in selected) for f in facts)
                or sum(f.status == "RUNNING" for f in facts) > 1
            ):
                raise ValueError()
            active = next((f for f in facts if f.status == "RUNNING"), None)
            if active and any(
                f.status == "READY" and f.ordinal < active.ordinal for f in facts
            ):
                raise ValueError()
            if self.passes is not None:
                for before, after in zip(self.passes, facts, strict=True):
                    if before == after:
                        continue
                    allowed = {
                        "READY": {"RUNNING", "SKIPPED"},
                        "RUNNING": {"RUNNING", "COMPLETED", "INCOMPLETE"},
                    }
                    if (
                        after.revision != before.revision + 1
                        or after.status not in allowed.get(before.status, set())
                        or after.selected != before.selected
                    ):
                        raise ValueError()
            elif any(
                f.revision != 1 or f.status not in {"READY", "SKIPPED"} for f in facts
            ):
                raise ValueError()
        except (ValueError, TypeError, KeyError):
            raise integrity_error() from None
        self.passes = facts
        self.sequence, self.occurred_at = event.sequence, event.occurred_at

    def finish(self) -> None:
        if (
            self.sequence != self.run.last_event_sequence
            or self.status != self.run.status
            or self.resource != self.run.resource_state
            or self.occurred_at != self.run.updated_at
            or self.terminal_count != int(self.run.status in TERMINAL)
        ):
            raise integrity_error()

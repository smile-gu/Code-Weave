import pytest
from pydantic import ValidationError

from review_harness.config import Settings


def test_missing_secret_fails_at_configuration(monkeypatch):
    monkeypatch.delenv("CODE_WEAVE_REVIEW_INTERNAL_BEARER_TOKEN", raising=False)
    with pytest.raises(ValidationError):
        Settings()


@pytest.mark.parametrize(
    "overrides",
    [
        {"internal_bearer_token": ""},
        {"internal_bearer_token": "change-me"},
        {"database_url": "postgresql://localhost/db"},
        {"database_url": "sqlite+aiosqlite://remote/db"},
        {"database_url": "sqlite+aiosqlite:///file:memdb?mode=memory&uri=true"},
        {"database_url": "sqlite+aiosqlite:///file::memory:?uri=true"},
        {"supervisor_queue_capacity": 0},
        {"sse_page_size": 10001},
        {"shutdown_grace_seconds": 0},
    ],
)
def test_invalid_configuration_is_rejected(overrides):
    values = {"internal_bearer_token": "test-only-credential", "snapshot_root": "."}
    values.update(overrides)
    with pytest.raises(ValidationError):
        Settings(**values)

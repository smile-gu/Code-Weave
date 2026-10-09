from pathlib import Path

from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy.engine import make_url


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="CODE_WEAVE_REVIEW_", extra="forbid")

    host: str = "127.0.0.1"
    port: int = Field(default=8091, ge=1, le=65535)
    database_url: str = "sqlite+aiosqlite:///review-harness.sqlite"
    internal_bearer_token: SecretStr
    snapshot_root: Path
    supervisor_queue_capacity: int = Field(default=32, ge=1, le=10000)
    sse_page_size: int = Field(default=100, ge=1, le=1000)
    sse_heartbeat_seconds: float = Field(default=5, gt=0, le=60)
    shutdown_grace_seconds: float = Field(default=5, gt=0, le=60)

    @field_validator("internal_bearer_token")
    @classmethod
    def require_real_secret(cls, value: SecretStr) -> SecretStr:
        secret = value.get_secret_value()
        if not secret.strip() or secret.lower() in {
            "change-me",
            "changeme",
            "example",
            "your-token",
            "token",
            "secret",
        }:
            raise ValueError("A non-example internal credential is required")
        return value

    @field_validator("database_url")
    @classmethod
    def require_file_sqlite(cls, value: str) -> str:
        url = make_url(value)
        if (
            url.drivername != "sqlite+aiosqlite"
            or not url.database
            or ":memory:" in url.database
            or url.query.get("mode") == "memory"
            or any((url.username, url.password, url.host, url.port))
        ):
            raise ValueError("A file-backed SQLite Async URL is required")
        return value

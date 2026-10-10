import os
from pathlib import Path

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy.engine import make_url


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="CODE_WEAVE_REVIEW_", extra="forbid")

    host: str = "127.0.0.1"
    port: int = Field(default=8091, ge=1, le=65535)
    database_url: str = "sqlite+aiosqlite:///review-harness.sqlite"
    internal_bearer_token: SecretStr
    snapshot_root: Path
    execution_root: Path
    snapshot_max_files: int = Field(default=20_000, gt=0)
    snapshot_max_file_bytes: int = Field(default=64 * 1024 * 1024, gt=0)
    snapshot_max_total_bytes: int = Field(default=1024 * 1024 * 1024, gt=0)
    snapshot_max_manifest_bytes: int = Field(default=16 * 1024 * 1024, gt=0)
    snapshot_max_diff_bytes: int = Field(default=2 * 1024 * 1024, gt=0)
    workspace_prepare_seconds: float = Field(default=120, gt=0, le=600)
    workspace_cleanup_seconds: float = Field(default=30, gt=0, le=120)
    supervisor_queue_capacity: int = Field(default=32, ge=1, le=10000)
    sse_page_size: int = Field(default=100, ge=1, le=1000)
    sse_heartbeat_seconds: float = Field(default=5, gt=0, le=60)
    shutdown_grace_seconds: float = Field(default=5, gt=0, le=60)

    @model_validator(mode="after")
    def separate_absolute_roots(self):
        if (
            not self.snapshot_root.is_absolute()
            or not self.execution_root.is_absolute()
        ):
            raise ValueError("Managed roots must be absolute")
        self.snapshot_root = Path(os.path.abspath(self.snapshot_root))
        self.execution_root = Path(os.path.abspath(self.execution_root))
        if self.snapshot_root.is_relative_to(
            self.execution_root
        ) or self.execution_root.is_relative_to(self.snapshot_root):
            raise ValueError("Managed roots must not contain each other")
        return self

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

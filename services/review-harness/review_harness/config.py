import os
from pathlib import Path
from typing import Literal

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy.engine import make_url


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="CODE_WEAVE_REVIEW_", extra="forbid")

    host: str = "127.0.0.1"
    port: int = Field(default=8091, ge=1, le=65535)
    database_url: str = "sqlite+aiosqlite:///review-harness.sqlite"
    checkpoint_database_url: str | None = None
    execution_mode: Literal["SIMULATED", "REAL"] = "SIMULATED"
    budget_policy_version: Literal["deepseek-compatible-budget-v2"] = (
        "deepseek-compatible-budget-v2"
    )
    deepseek_api_key: SecretStr | None = None
    deepseek_asset_root: Path | None = None
    run_timeout_seconds: float = Field(default=180, gt=0, le=180)
    graph_max_steps: int = Field(default=64, ge=1, le=160)
    graph_max_model_calls: int = Field(default=16, ge=1, le=16)
    compaction_attempts: int = Field(default=2, ge=1, le=2)
    compaction_max_successes: int = Field(default=8, ge=1, le=8)
    model_timeout_seconds: float = Field(default=45, gt=0, le=120)
    internal_bearer_token: SecretStr
    snapshot_root: Path
    artifact_root: Path
    artifact_max_bytes: int = Field(default=8 * 1024 * 1024, gt=0)
    artifact_run_bytes: int = Field(default=64 * 1024 * 1024, gt=0)
    artifact_run_count: int = Field(default=256, gt=0)
    tool_output_bytes: int = Field(default=32768, ge=1024, le=32768)
    tool_timeout_seconds: float = Field(default=10, gt=0, le=120)
    tool_scan_bytes: int = Field(default=64 * 1024 * 1024, gt=0)
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

    @property
    def execution_contract(self):
        return {
            "executionMode": self.execution_mode,
            "resultVersion": "2.0" if self.execution_mode == "REAL" else "1.0",
        }

    @model_validator(mode="after")
    def separate_absolute_roots(self):
        from review_harness.infrastructure.paths import safe_directory

        roots = []
        for name in ("snapshot_root", "execution_root", "artifact_root"):
            path = getattr(self, name)
            if not path.is_absolute():
                raise ValueError("Managed roots must be absolute")
            path = safe_directory(Path(os.path.abspath(path)), must_exist=False)
            path = path.resolve(strict=False)
            setattr(self, name, path)
            # Resolve existing ancestors as well, so aliases such as a mounted
            # drive cannot make the same physical tree look independent.
            roots.append(path.resolve(strict=False))
        for index, root in enumerate(roots):
            for other in roots[index + 1 :]:
                if root.is_relative_to(other) or other.is_relative_to(root):
                    raise ValueError("Managed roots must not contain each other")
        if self.checkpoint_database_url is not None:
            self.require_file_sqlite(self.checkpoint_database_url)
            from review_harness.infrastructure.paths import safe_file

            path = Path(make_url(self.checkpoint_database_url).database)
            if not path.is_absolute():
                raise ValueError("Checkpoint database must be absolute")
            safe_directory(path.parent, must_exist=False)
            if os.path.lexists(path):
                safe_file(path.parent, path.name)
            path = path.resolve(strict=False)
            product = Path(make_url(self.database_url).database).resolve(strict=False)
            if path == product or (
                path.exists() and product.exists() and os.path.samefile(path, product)
            ):
                raise ValueError("Checkpoint database must be independent")
            if any(path.is_relative_to(root) for root in roots):
                raise ValueError(
                    "Checkpoint database cannot live in managed material roots"
                )
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

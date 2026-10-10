from dataclasses import dataclass
from pathlib import Path


class WorkspaceError(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


class WorkCancelled(Exception):
    pass


@dataclass(frozen=True)
class RuntimeContext:
    run: object
    workspace_root: Path
    manifest: dict

    @property
    def run_id(self):
        return self.run.run_id

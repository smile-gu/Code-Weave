"""Explicit acceptance command; not collected into normal pytest runs."""

import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import urllib.request
import venv
from pathlib import Path
from uuid import uuid4

SERVICE_ROOT = Path(__file__).resolve().parents[1]


def run(command, environment=None):
    subprocess.run(command, cwd=SERVICE_ROOT, env=environment, check=True)


def verify():
    if sys.version_info[:2] != (3, 12):
        raise RuntimeError("Acceptance baseline requires Python 3.12")
    with tempfile.TemporaryDirectory(prefix="code-weave-harness-acceptance-") as folder:
        root = Path(folder).resolve()
        if not root.is_relative_to(Path(tempfile.gettempdir()).resolve()):
            raise RuntimeError(
                "Acceptance directory must stay within the system temp root"
            )
        venv.EnvBuilder(with_pip=True).create(root / "venv")
        executable = (
            root / "venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
        )
        run([str(executable), "-m", "pip", "install", "-r", "requirements.lock"])
        run(
            [
                str(executable),
                "-m",
                "pip",
                "install",
                "--no-deps",
                "--no-build-isolation",
                "-e",
                ".",
            ]
        )
        run([str(executable), "-m", "pip", "check"])
        # Inherited operator configuration must not alter the acceptance fixture.
        environment = {
            name: value
            for name, value in os.environ.items()
            if not name.startswith("CODE_WEAVE_REVIEW_")
        }
        # Keep pytest retention/cleanup inside this acceptance's owned root.
        # Other shells may be running pytest under the same Windows account.
        run(
            [str(executable), "-m", "pytest", "-q", "--basetemp", str(root / "pytest")],
            environment,
        )
        run([str(executable), "-m", "ruff", "check", "."], environment)
        run([str(executable), "-m", "ruff", "format", "--check", "."], environment)
        snapshots = root / "snapshots"
        snapshots.mkdir()
        executions = root / "executions"
        executions.mkdir()
        artifacts = root / "artifacts"
        artifacts.mkdir()
        credential = uuid4().hex + uuid4().hex
        with socket.socket() as reserve:
            reserve.bind(("127.0.0.1", 0))
            port = reserve.getsockname()[1]
        environment.update(
            {
                "CODE_WEAVE_REVIEW_DATABASE_URL": "sqlite+aiosqlite:///"
                + (root / "smoke.sqlite").as_posix(),
                "CODE_WEAVE_REVIEW_SNAPSHOT_ROOT": str(snapshots),
                "CODE_WEAVE_REVIEW_EXECUTION_ROOT": str(executions),
                "CODE_WEAVE_REVIEW_ARTIFACT_ROOT": str(artifacts),
                "CODE_WEAVE_REVIEW_INTERNAL_BEARER_TOKEN": credential,
                "CODE_WEAVE_REVIEW_PORT": str(port),
            }
        )
        run([str(executable), "-m", "alembic", "upgrade", "head"], environment)
        started = threading.Event()
        process = subprocess.Popen(
            [str(executable), "-m", "review_harness.main"],
            cwd=root,
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )

        def read_startup():
            for line in process.stdout:
                if "Uvicorn running on" in line:
                    started.set()

        reader = threading.Thread(target=read_startup, daemon=True)
        reader.start()
        try:
            if not started.wait(30):
                raise RuntimeError("Service did not announce startup")
            request = urllib.request.Request(
                f"http://127.0.0.1:{port}/internal/v1/health",
                headers={"Authorization": "Bearer " + credential},
            )
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(request, timeout=5) as response:
                health = json.load(response)
                if (
                    response.status != 200
                    or health["status"] != "READY"
                    or not health["acceptingRuns"]
                ):
                    raise RuntimeError("Service is not Ready")
            print(
                "CLEAN_ACCEPTANCE: Python 3.12 install, migration, service startup and authenticated Readiness passed"
            )
        finally:
            process.terminate()
            process.wait(timeout=10)
            reader.join(timeout=5)
            process.stdout.close()


if __name__ == "__main__":
    verify()

import json
import os
import re
import stat
import unicodedata
from contextlib import contextmanager
from pathlib import Path

from review_harness.domain.snapshot import WorkspaceError


def storage_accessible(snapshot_root, execution_root, artifact_root):
    try:
        safe_directory(snapshot_root)
        safe_directory(execution_root)
        safe_directory(artifact_root)
        if not os.access(snapshot_root, os.R_OK):
            return False
        with os.scandir(snapshot_root) as entries:
            next(entries, None)
        return all(
            os.access(root, os.R_OK | os.W_OK)
            for root in (execution_root, artifact_root)
        )
    except (OSError, WorkspaceError):
        return False


def portable(value: str) -> str:
    try:
        value.encode("utf-8", "strict")
    except (UnicodeError, AttributeError):
        raise WorkspaceError("SNAPSHOT_PATH_INVALID") from None
    for part in value.split("/"):
        if (
            not part
            or part in {".", ".."}
            or part.lower() == ".git"
            or part.endswith((" ", "."))
            or re.search(r'[\\:\x00-\x1f\x7f<>"|?*]', part)
            or re.fullmatch(
                r"(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(\..*)?", part.upper()
            )
        ):
            raise WorkspaceError("SNAPSHOT_PATH_INVALID")
    return value


def distinct(paths, *, check_prefixes=True):
    keys = set()
    for path in paths:
        key = unicodedata.normalize("NFC", portable(path)).upper()
        if key in keys:
            raise WorkspaceError("SNAPSHOT_PATH_COLLISION")
        keys.add(key)
    if check_prefixes:
        for key in keys:
            parts = key.split("/")
            if any("/".join(parts[:i]) in keys for i in range(1, len(parts))):
                raise WorkspaceError("SNAPSHOT_PATH_COLLISION")


def link_or_reparse(info):
    return stat.S_ISLNK(info.st_mode) or bool(
        getattr(info, "st_file_attributes", 0)
        & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 1024)
    )


def safe_directory(path: Path, *, must_exist=True) -> Path:
    if not path.is_absolute():
        raise WorkspaceError("SNAPSHOT_PATH_INVALID")
    path = Path(os.path.abspath(path))
    current = Path(path.anchor)
    for part in path.parts[1:]:
        current /= part
        try:
            info = current.lstat()
        except FileNotFoundError:
            if must_exist:
                raise
            continue
        if link_or_reparse(info) or not stat.S_ISDIR(info.st_mode):
            raise WorkspaceError("SNAPSHOT_PATH_INVALID")
    return path


def safe_file(root: Path, relative: str) -> Path:
    path = safe_directory(root) / portable(relative)
    safe_directory(path.parent)
    info = path.lstat()
    if link_or_reparse(info) or not stat.S_ISREG(info.st_mode):
        raise WorkspaceError("SNAPSHOT_PATH_INVALID")
    return path


@contextmanager
def open_source(root: Path, relative: str):
    path = safe_file(root, relative)
    before = path.lstat()
    descriptor = os.open(
        path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_BINARY", 0)
    )
    with os.fdopen(descriptor, "rb") as stream:
        opened = os.fstat(stream.fileno())
        if (opened.st_dev, opened.st_ino) != (
            before.st_dev,
            before.st_ino,
        ) or not stat.S_ISREG(opened.st_mode):
            raise WorkspaceError("SNAPSHOT_PATH_INVALID")
        verified = safe_file(root, relative).lstat()
        if (verified.st_dev, verified.st_ino) != (opened.st_dev, opened.st_ino):
            raise WorkspaceError("SNAPSHOT_PATH_INVALID")
        yield stream
        after = safe_file(root, relative).lstat()
        if (after.st_dev, after.st_ino) != (opened.st_dev, opened.st_ino):
            raise WorkspaceError("SNAPSHOT_CONTENT_MISMATCH")


def visible_files(root: Path, *, max_files=20_000, check=lambda: None):
    safe_directory(root)
    paths = []
    visited = 0
    for directory, directories, names in os.walk(root, followlinks=False):
        check()
        visited += 1 + len(names)
        if visited > max_files * 256:
            raise WorkspaceError("SNAPSHOT_LIMIT_EXCEEDED")
        safe_directory(Path(directory))
        for name in directories:
            safe_directory(Path(directory) / name)
        for name in names:
            relative = (Path(directory) / name).relative_to(root).as_posix()
            safe_file(root, relative)
            paths.append(relative)
            if len(paths) > max_files:
                raise WorkspaceError("SNAPSHOT_LIMIT_EXCEEDED")
    distinct(paths)
    return sorted(paths)


def strict_json(raw: bytes):
    def reject_constant(_):
        raise ValueError("Non-finite number")

    def object_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate property")
            key.encode("utf-8", "strict")
            result[key] = value
        return result

    try:
        text = raw.decode("utf-8", "strict")
        if text.startswith("\ufeff"):
            raise ValueError("BOM")
        value = json.loads(
            text,
            object_pairs_hook=object_pairs,
            parse_constant=reject_constant,
        )
        json.dumps(value, ensure_ascii=False, allow_nan=False).encode("utf-8", "strict")
        return value
    except (ValueError, UnicodeError):
        raise WorkspaceError("SNAPSHOT_MANIFEST_INVALID") from None

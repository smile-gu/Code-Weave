import re

CREDENTIAL_KEY = re.compile(
    r"(?i)(?:password|passwd|secret|api[_-]?key|access[_-]?token|token)$"
)
ASSIGNMENT = re.compile(r"\b(?P<name>[A-Za-z_][A-Za-z0-9_-]{0,127})[\"']?\s*[:=]\s*")
UNQUOTED_VALUE = re.compile(r"[^\s,;}]+")
IDENTITY_FIELDS = frozenset(
    {
        "path",
        "contentSha256",
        "diffSha256",
        "sha256",
        "artifactId",
        "runId",
        "snapshotId",
        "toolCallId",
        "producerId",
    }
)


def redact_structure(value, *, preserve_identity=True):
    """Preserve JSON shape while applying the same value policy and key context."""
    changed = False

    def sanitize(item):
        nonlocal changed
        if isinstance(item, str):
            cleaned, state = redact(item)
            changed = changed or state == "APPLIED"
            return cleaned
        if isinstance(item, dict):
            result = {}
            for key, content in item.items():
                if preserve_identity and key in IDENTITY_FIELDS:
                    # Platform source identities must remain usable anchors.
                    # Text bodies still receive the same redaction policy.
                    result[key] = content
                elif CREDENTIAL_KEY.search(key) and content is not None:
                    result[key] = "[REDACTED]"
                    changed = True
                else:
                    result[key] = sanitize(content)
            return result
        if isinstance(item, list):
            return [sanitize(content) for content in item]
        return item

    cleaned = sanitize(value)
    return cleaned, "APPLIED" if changed else "NONE"


def redact_assignments(text):
    """Scan recognized values once; an open quote owns the remaining capture.

    Escaped quotes cannot terminate a value. Missing closing quotes (including
    a dangling backslash) conservatively consume to EOF, across lines. Ordinary
    assignments do not consume their value, so they cannot hide later secrets.
    """
    parts, copied, position = [], 0, 0
    while match := ASSIGNMENT.search(text, position):
        start = position = match.end()
        if not CREDENTIAL_KEY.search(match["name"]) or start == len(text):
            continue
        if text[start] in {"'", '"'}:
            quote, end = text[start], start + 1
            while end < len(text):
                if text[end] == "\\":
                    end = min(end + 2, len(text))
                elif text[end] == quote:
                    end += 1
                    break
                else:
                    end += 1
        else:
            value = UNQUOTED_VALUE.match(text, start)
            if value is None:
                continue
            end = value.end()
        parts.extend(
            (text[copied:start], "[REDACTED]", "\n" * text[start:end].count("\n"))
        )
        copied = position = end
    parts.append(text[copied:])
    return "".join(parts)


def redact(text):
    """Bounded complete-text policy; callers must cap capture before applying it.

    Patterns cover explicit credentials, bearer values and private-key blocks.
    This is display hygiene, not a guarantee that arbitrary secrets are detected.
    """
    original = text
    text = re.sub(
        r"-----BEGIN (?:[A-Z0-9 ]* )?PRIVATE KEY-----.*?(?:-----END (?:[A-Z0-9 ]* )?PRIVATE KEY-----|$)",
        lambda match: "[REDACTED PRIVATE KEY]" + "\n" * match.group(0).count("\n"),
        text,
        flags=re.S,
    )
    text = re.sub(r"(?i)(\bBearer\s+)[A-Za-z0-9._~+/=-]+", r"\1[REDACTED]", text)
    text = redact_assignments(text)
    return text, "APPLIED" if text != original else "NONE"

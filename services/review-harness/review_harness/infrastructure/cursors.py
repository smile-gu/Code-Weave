import base64
import hashlib
import hmac
import json

from review_harness.domain.artifact import artifact_error
from review_harness.domain.model import canonical


class CursorCodec:
    def __init__(self, credential):
        self.key = hashlib.sha256(
            ("artifact-cursor-v1:" + credential).encode()
        ).digest()

    def encode(self, run, identity, digest, offset):
        raw = canonical(
            {"v": 1, "run": run, "identity": identity, "hash": digest, "offset": offset}
        )
        signature = hmac.digest(self.key, raw, "sha256")
        return base64.urlsafe_b64encode(raw + signature).decode().rstrip("=")

    def decode(self, token, run, identity, digest):
        if token is None:
            return 0
        try:
            if not isinstance(token, str) or len(token) > 1024:
                raise ValueError()
            decoded = base64.b64decode(
                token + "=" * (-len(token) % 4), altchars=b"-_", validate=True
            )
            raw, signature = decoded[:-32], decoded[-32:]
            if not hmac.compare_digest(signature, hmac.digest(self.key, raw, "sha256")):
                raise ValueError()
            value = json.loads(raw)
            offset = value.get("offset")
            if (
                type(offset) is not int
                or offset < 0
                or value
                != {
                    "v": 1,
                    "run": run,
                    "identity": identity,
                    "hash": digest,
                    "offset": offset,
                }
            ):
                raise ValueError()
            return offset
        except (ValueError, KeyError, TypeError):
            raise artifact_error("ARTIFACT_CURSOR_INVALID", 422) from None

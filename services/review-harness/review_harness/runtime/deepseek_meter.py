"""Pinned text reference encoding; finite hosted samples are not global parity."""

import copy
import hashlib
import types
from pathlib import Path

from tokenizers import Tokenizer

from review_harness.domain.graph import graph_error
from review_harness.domain.provider import ProviderRequest, strict_json

ASSETS = Path(__file__).resolve().parents[1] / "assets" / "deepseek_v41"
REVISION = "2cba9e42aa026125f3ed06c6d98c1db82f7ca027"
METER_VERSION = "v4.1-2cba9e42-v1"
HASHES = {
    "encoding.py": "502bdaec8a3fd88ebc24c4721a7038fbe42f2063c664638127056107920035c1",
    "tokenizer.json": "c90dfa01249db1be4245780a052ede752e1361c612ac6d08e2bdada7d599476b",
    "tokenizer_config.json": "6ac8c8dc065ed118161d02dd532749ae3f52c243deac27872134fae2f50d8547",
    "LICENSE": "f2c6c602815669d292889e5be8c802f2ed950653b77999b1584e8e6aed25d040",
}


class TextMeter:
    def __init__(self, root=ASSETS):
        self.root = Path(root)
        verified = self.verify()
        # No global import path changes. Only hash-verified, vendored reference
        # code is loaded; unsupported vision/thinking inputs are rejected first.
        reference = types.ModuleType("code_weave_pinned_v41_encoding")
        exec(
            compile(verified["encoding.py"], "pinned-v41-encoding", "exec"),
            reference.__dict__,
        )
        self.encode_messages = reference.encode_messages
        self.tokenizer = Tokenizer.from_str(verified["tokenizer.json"].decode("utf-8"))

    def verify(self):
        verified = {}
        try:
            for name, expected in HASHES.items():
                path = self.root / name
                if path.is_symlink() or path.stat().st_size > 8 * 1024 * 1024:
                    raise ValueError("Unsafe asset")
                with path.open("rb") as handle:
                    raw = handle.read(8 * 1024 * 1024 + 1)
                if (
                    len(raw) > 8 * 1024 * 1024
                    or hashlib.sha256(raw).hexdigest() != expected
                ):
                    raise ValueError("Pinned asset mismatch")
                verified[name] = raw
        except (OSError, ValueError):
            raise graph_error("CONTEXT_TOKEN_METER_UNAVAILABLE") from None
        return verified

    def count(self, request):
        # Revalidation covers the actual immutable bytes, not an earlier DTO.
        value = ProviderRequest.model_validate(
            strict_json(request.wire, limit=512 * 1024)
        )
        messages = copy.deepcopy([message.model_dump() for message in value.messages])
        if messages[0]["role"] != "system":
            messages.insert(0, {"role": "system", "content": ""})
        if value.tools:
            messages[0]["tools"] = [tool.model_dump() for tool in value.tools]
        if value.response_format:
            messages[0]["response_format"] = value.response_format.model_dump()
        prompt = self.encode_messages(messages, thinking_mode="chat")
        return len(self.tokenizer.encode(prompt, add_special_tokens=False).ids)

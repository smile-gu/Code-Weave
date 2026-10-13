"""Operator-verified bounded price file; no model or network-derived pricing."""

from review_harness.domain.fees import PriceProfile, PriceProfileView
from review_harness.domain.graph import graph_error
from review_harness.domain.model import utc_now
from review_harness.domain.provider import strict_json
from review_harness.infrastructure.paths import safe_file


class PriceCatalog:
    def __init__(self, path, clock=utc_now):
        self.path, self.clock = path, clock

    def current(self):
        if self.path is None:
            raise graph_error("PRICE_PROFILE_UNAVAILABLE")
        try:
            path = safe_file(self.path.parent, self.path.name)
            with path.open("rb") as stream:
                raw = stream.read(8193)
            value = PriceProfile.model_validate(strict_json(raw, limit=8192))
        except Exception:
            raise graph_error("PRICE_PROFILE_UNAVAILABLE") from None
        if not value.current_at(self.clock()):
            raise graph_error("PRICE_PROFILE_EXPIRED")
        return PriceProfileView(priceProfile=value, digest=value.digest)

#!/usr/bin/env python3
"""Static semantic contract for FairEmail reply-from-alias authority.

This contract is intentionally read-only. Spam Control shares alias inventory with
reply composition, so Spam refactors must preserve the independent reply path:
original envelope/recipient evidence -> authoritative replyDeliveredTo ->
ref.deliveredto persistence/re-observation -> resolveReplyExtra -> draft.extra.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit("FAIL: " + message)


compose = read("app/src/main/java/eu/faircode/email/FragmentCompose.java")
intelligence = read("app/src/main/java/eu/faircode/email/SpamIntelligence.java")

# Recovery authority: original envelope headers before later routing Delivered-To.
require("Reply-time alias authority v5" in compose,
        "reply-time alias authority v5 marker missing")
require('"Envelope-To", "X-Envelope-To", "X-Original-To"' in compose,
        "original envelope headers are no longer the leading reply alias evidence")
require('replyAliasSource = "Delivered-To"' in compose,
        "Delivered-To fallback is missing")

# The resolved authority is first mirrored/persisted to ref.deliveredto and
# re-observed, then the same resolved value is consumed by reply-extra resolution.
# Do not pin this contract back to the pre-v4 implementation shape where the
# resolver consumed ref.deliveredto directly.
writeback = compose.find("ref.deliveredto = replyDeliveredTo;")
observe = compose.find("SpamIntelligence.observeMessage(context, refAccount, refFolder, ref);")
resolve = re.search(
    r"SpamIntelligence\.resolveReplyExtra\(\s*context\s*,\s*selected\s*,\s*replyDeliveredTo\s*\)",
    compose,
)
draft_write = compose.find("data.draft.extra = envelopeExtra;")

require(writeback >= 0,
        "resolved reply alias is not written back to ref.deliveredto")
require(observe > writeback,
        "resolved historical alias is not re-observed after ref.deliveredto repair")
require(resolve is not None,
        "compose no longer resolves reply extra from authoritative replyDeliveredTo")
require(resolve.start() > observe,
        "reply extra is resolved before authoritative alias re-observation")
require(draft_write > resolve.start(),
        "resolved reply alias extra is not written into the draft after resolution")

# SpamIntelligence validates capability, but does not redefine the desired alias.
require("String alias = AliasRegistry.normalizeAddress(deliveredTo);" in intelligence,
        "resolveReplyExtra no longer treats deliveredTo as its input authority")
require("isKnownAlias(context, identity, alias)" in intelligence,
        "resolveReplyExtra no longer validates reply-capable alias knowledge")
require("String local = alias.substring(0, aat);" in intelligence,
        "resolveReplyExtra no longer derives sender-extra from the delivered alias local-part")

print("PASS: reply-from-alias authority chain is intact")
print("PASS: original envelope evidence -> replyDeliveredTo -> ref.deliveredto/observe -> resolveReplyExtra -> draft.extra")

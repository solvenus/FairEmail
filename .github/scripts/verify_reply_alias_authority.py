#!/usr/bin/env python3
"""Static semantic contract for FairEmail reply-from-alias authority.

This contract is intentionally read-only. Spam Control shares alias inventory with
reply composition, so Spam refactors must preserve the independent reply path:
original envelope/recipient evidence -> ref.deliveredto -> re-observation ->
resolveReplyExtra -> draft.extra.
"""
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
require("ref.deliveredto = replyDeliveredTo;" in compose,
        "resolved reply alias is not written back to ref.deliveredto")
require("SpamIntelligence.observeMessage(context, refAccount, refFolder, ref);" in compose,
        "resolved historical alias is not re-observed before compose continues")

# Compose must resolve sender-extra from the selected reply identity and authoritative
# delivered-to alias, then write the resolved extra into the draft.
require("SpamIntelligence.resolveReplyExtra(context, selected, ref.deliveredto)" in compose,
        "compose no longer resolves reply extra from ref.deliveredto")
require("data.draft.extra = envelopeExtra;" in compose,
        "resolved reply alias extra is not written into the draft")

# SpamIntelligence validates capability, but does not redefine the desired alias.
require("String alias = AliasRegistry.normalizeAddress(deliveredTo);" in intelligence,
        "resolveReplyExtra no longer treats deliveredTo as its input authority")
require("isKnownAlias(context, identity, alias)" in intelligence,
        "resolveReplyExtra no longer validates reply-capable alias knowledge")
require("String local = alias.substring(0, aat);" in intelligence,
        "resolveReplyExtra no longer derives sender-extra from the delivered alias local-part")

print("PASS: reply-from-alias authority chain is intact")
print("PASS: original envelope evidence -> ref.deliveredto -> observe -> resolveReplyExtra -> draft.extra")

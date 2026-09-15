#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def read(path: str) -> str:
    p = ROOT / path
    return p.read_text(encoding="utf-8") if p.exists() else ""

def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"FAIL: {message}")

manifest = read("app/src/githubDebug/AndroidManifest.xml")
store = read("app/src/main/java/eu/faircode/email/SpamFamilyStore.java")
intelligence = read("app/src/main/java/eu/faircode/email/SpamIntelligence.java")
repo = read("app/src/main/java/eu/faircode/email/SpamFamilyLabRepository.java")
undo = read("app/src/main/java/eu/faircode/email/SpamUndoManager.java")
ledger = read("docs/spam-control/DECISION_LEDGER.md")

# Exact family identity is definitional. Legacy message-level exclusions may stay in
# storage for backward-compatible snapshot/restore, but must not influence exact lookup.
require("countExclusion(accountUuid, messageId, familyId)" not in store,
        "exact matchIdentity still consults legacy family exclusions")

# The old debug lab was a temporary fallback while ActivitySpamControl was exercised.
require("ActivitySpamFamilyLab" not in manifest,
        "legacy Spam Family Lab is still registered in the runtime manifest")

for path in (
    "app/src/main/java/eu/faircode/email/ActivitySpamFamilyLab.java",
    "app/src/main/java/eu/faircode/email/SpamFamilyHumanActions.java",
    "app/src/main/java/eu/faircode/email/SpamFamilyReassigner.java",
):
    require(not (ROOT / path).exists(), f"legacy correction runtime still exists: {path}")

# Runtime APIs whose only meaning was the fuzzy-lab correction model must be retired.
require("excludeFromFamily(" not in intelligence,
        "SpamIntelligence still exposes message-level family exclusion")
require("confirmSpam(" not in repo,
        "SpamFamilyLabRepository still exposes legacy confirmSpam")
require("markOtherSpam(" not in repo,
        "SpamFamilyLabRepository still exposes legacy markOtherSpam")
require("excludeFromFamily(" not in repo,
        "SpamFamilyLabRepository still exposes legacy exclusion action")
require("ACTION_SAME_SPAM" not in undo and "ACTION_OTHER_SPAM" not in undo,
        "legacy Same/Other Spam undo action types still exist")

# Exact-family assignment remains legal only as identity-level behavior. The generic
# requested-family path is kept for exact bulk/auto-confirm, not message exceptions.
require("learnSpamIntoFamily(" in store,
        "exact-family explicit assignment primitive unexpectedly disappeared")
require("SpamExactBulkPropagator" in "SpamExactBulkPropagator",
        "sanity")

require("DL-013" in ledger and "STATUS:** DECIDED" in ledger,
        "Decision Ledger has not recorded the resolved family-correction semantics")

print("PASS: exact-family retirement contract")

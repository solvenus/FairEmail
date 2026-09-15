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
bulk = read("app/src/main/java/eu/faircode/email/SpamExactBulkPropagator.java")
ledger = read("docs/spam-control/DECISION_LEDGER.md")

# Exact family identity is definitional. Legacy message-level exclusions may stay in
# storage for backward-compatible snapshot/restore, but must not influence exact lookup.
require("countExclusion(accountUuid, messageId, familyId)" not in store,
        "exact matchIdentity still consults legacy family exclusions")
require("SpamFamilyEngine.Score.exact()" in store,
        "exact matchIdentity no longer returns exact provenance")

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

# Identity-level family assignment remains legal only where exact provenance is explicit.
require("learnSpamIntoFamily(" in store,
        "exact-family explicit assignment primitive unexpectedly disappeared")

# Exact bulk must derive the canonical identity from the human-labeled seed, compare
# candidate identity keys, and only walk UNKNOWN canonical message rows before applying
# the already exact family id. This protects explicit HAM and prevents fuzzy propagation.
for needle, description in (
    ("SpamFamilyMessageAdapter.identityFromMessage(seed)",
     "bulk propagation no longer derives the seed exact identity"),
    ("messageDao.getUnknownAfter(",
     "bulk propagation no longer limits itself to UNKNOWN canonical messages"),
    ("identity.key.equals(candidate.key)",
     "bulk propagation no longer requires equal exact identity keys"),
    ("SpamIntelligence.learnSpam(context, account, message, familyId);",
     "bulk propagation no longer applies the exact seed family id"),
):
    require(needle in bulk, description)

# Auto-confirm may pass a requested family only after exact prediction has been stored.
# Guard both the exact matcher and the predicted-family handoff.
for needle, description in (
    ("SpamFamilyStore.matchIdentity(",
     "prediction path no longer derives family from exact identity"),
    ("state.predicted_family_id == null",
     "auto-confirm no longer requires a predicted family"),
    ("learnSpam(context, account, message, state.predicted_family_id);",
     "auto-confirm no longer hands off the exact predicted family"),
):
    require(needle in intelligence, description)

require("DL-013" in ledger and "DL-014" in ledger and
        ledger.count("STATUS:** DECIDED") >= 2,
        "Decision Ledger has not recorded the resolved family-correction semantics")

print("PASS: exact-family retirement contract")
print("PASS: exact bulk and auto-confirm retain explicit exact-identity provenance")

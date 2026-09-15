# CURRENT CHANGESET — Spam Control Semantic Architecture v2

**Branch:** `changeset/spam-control-semantic-architecture-v2`  
**Base:** `feature/spam-control-p0 @ dece32b7089e6447f6adf3b32449c03fcfb1c5dd`  
**Safety baseline:** `safety/spam-control-phone-checkpoint-6e7b6a52`  
**Type:** Architecture governance / semantic verification only  
**App source changes allowed:** **NO**

---

## 1. Intent

Make the v2 programming constitution operational inside the FairEmail/Spam Control repository before the next functional source change.

This ChangeSet establishes the parallel representation of system meaning that must live beside the code:

- Living Semantic System Model;
- Decision Ledger;
- Invariant Registry inside the system model;
- state ownership and producer/consumer maps;
- recovery semantics;
- semantic verification matrix;
- executable reset/undo and observability contracts;
- a CI coverage contract proving that semantic contract workflows actually trigger when their consumed source files change.

---

## 2. Explicit non-goals / forbidden changes

This ChangeSet must not modify:

- `app/src/**` application source;
- Room entities, DAOs, DB version or migrations;
- Spam/HAM runtime behavior;
- family correction behavior;
- cPanel runtime behavior;
- reply-from-alias behavior;
- UI behavior;
- APK packaging behavior.

If any app source change appears in the final base→candidate diff, this ChangeSet is invalid and must not be fast-forwarded.

---

## 3. Allowed paths

Expected additions/changes are restricted to:

```text
docs/spam-control/SEMANTIC_SYSTEM_MODEL.md
docs/spam-control/DECISION_LEDGER.md
docs/spam-control/CURRENT_CHANGESET.md
.github/scripts/verify_spam_control_reset_undo_contract.py
.github/workflows/verify-spam-control-reset-undo-contract.yml
.github/scripts/verify_spam_control_observability_contract.py
.github/workflows/verify-spam-control-observability-contract.yml
.github/scripts/verify_spam_control_contract_coverage.py
.github/workflows/verify-spam-control-contract-coverage.yml
```

Existing bootstrap/alias-sideeffect contract files may be updated **only** if required to make their CI triggers/coverage reflect their actual consumers. Their semantic assertions must not be weakened merely to obtain green CI.

---

## 4. Pre-change archaeology completed

For this governance ChangeSet we inspected and reconstructed at minimum:

- `SpamFamilyIdentity`
- `SpamFamilyStore`
- `SpamIntelligence`
- `SpamFamilyLabRepository`
- `SpamFamilyHumanActions`
- `SpamFamilyReassigner`
- `EntitySpamMessage`
- `EntityAliasDelivery`
- `EntityAlias`
- `SpamHistoricalScanner`
- `SpamMessageStore`
- `SpamResetManager`
- `SpamUndoManager`
- `SpamLearningSnapshot`
- bootstrap semantic contract/workflow
- alias-sideeffect semantic contract/workflow
- Android compile/migration/APK workflow
- safety phone checkpoint existence

The resulting semantics and open migration gaps are recorded in the Living System Model and Decision Ledger.

---

## 5. Decisions that block later functional code

No later correction/reassign implementation may start until these OPEN ledger entries are resolved explicitly:

- **DL-013:** Human `Other spam` override semantics;
- **DL-014:** Per-message assignment versus global exact-identity rebinding.

Known affected runtime paths:

- `SpamFamilyLabRepository.confirmSpam`
- `SpamIntelligence.excludeFromFamily`
- `SpamFamilyHumanActions.markOtherSpam`
- `SpamFamilyReassigner.reassign`

The next functional ChangeSet must begin from their semantic decision, not from replacing DAO calls.

---

## 6. Verification obligations for this ChangeSet

### Semantic contract gates

Must be green on the ChangeSet candidate:

1. Spam Control bootstrap/message-authority contract;
2. canonical-message + legacy alias-sideeffect contract;
3. reset/undo contract;
4. scan/Terminal/cPanel observability contract;
5. contract-coverage contract.

### Coverage contract must prove

For each guarded semantic contract:

- production branch `feature/spam-control-p0` is a push target;
- this ChangeSet branch is a push target while the ChangeSet is being verified;
- every critical app source file consumed/read by the contract script appears in that workflow's `paths:` list;
- the contract script and its workflow file trigger themselves.

### Diff gate

Base→candidate diff must contain **zero** `app/src/**` changes.

### Build gate

Because this ChangeSet changes no application source, Android build output is not semantic evidence for the docs themselves. However, before fast-forwarding to the operational branch we still verify that no app source entered the diff and that all relevant semantic workflows are green. The existing known APK baseline remains unchanged by construction.

---

## 7. Rollback

Rollback is a simple branch/ref operation because this ChangeSet adds governance/test artifacts only and changes no runtime/database state.

Known pre-ChangeSet operational commit:

`dece32b7089e6447f6adf3b32449c03fcfb1c5dd`

No force-push is permitted.

---

## 8. Definition of Done

This ChangeSet closes only when all are true:

- Living System Model exists in repo;
- Decision Ledger exists in repo;
- OPEN semantic conflicts are explicitly visible, not hidden behind runtime code;
- reset/undo contract is present and green;
- observability contract is present and green;
- bootstrap and alias-sideeffect contracts remain green;
- CI coverage contract proves workflow/source dependency coverage;
- base→candidate diff contains no app source changes;
- branch can be fast-forwarded without rewriting history.

After closure, the next action is **not automatically coding**. The next functional ChangeSet begins by resolving DL-013/DL-014, writing the correction regression matrix, then and only then modifying correction/reassign source.

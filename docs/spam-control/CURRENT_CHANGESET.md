# CURRENT CHANGESET — Exact Family Correction Retirement

**Branch:** `changeset/spam-control-family-correction-semantics`  
**Base:** `feature/spam-control-p0 @ c2c78a5dced58ae2d72a3383e080ded0d5bbdf55`  
**Safety baseline:** `safety/spam-control-phone-checkpoint-6e7b6a52`  
**Type:** Functional semantic retirement / canonicalization  
**App source changes allowed:** **YES, only within the explicit retirement scope below**

---

## 1. Intent

Resolve and implement DL-013/DL-014 after archaeology proved that `Samme spam / Annen spam` belongs to the old fuzzy-family debug lab, while production Spam Family identity is deterministic:

`normalize(sender display name) + normalize(subject)`

This ChangeSet retires the legacy message-level family-correction subsystem without changing the selected exact-family definition, Spam/HAM truth, alias enrichment, undo/reset architecture, reply-from-alias, cPanel behavior, or raw mail.

---

## 2. Semantic decision already made

### Exact family identity is definitional

Two messages with the same complete canonical sender-name + subject identity belong to the same Spam Family. Production exact lookup has no per-message “other family” exception.

### `Annen spam` is retired, not reimplemented

The old action meant “Spam, but not this fuzzy suggested group”. That concept is invalid once family membership is deterministic. We do not invent a per-message override that contradicts the family definition.

### Message label and family binding remain different scopes

- `Spam` / `Ikke spam` is message-level human truth.
- family binding is identity-level truth.
- exact bulk/auto-confirm may bind/use a family only from exact-identity provenance.
- a one-message UI action may not globally rebind an arbitrary canonical identity.

### Legacy exclusion storage is preserved temporarily

`spam_family_exclusion` schema/DAO/snapshot rows remain readable for backward-compatible recovery during this ChangeSet. They become inert for production exact matching. Physical schema removal requires a separate migration ChangeSet.

---

## 3. Archaeology evidence

The repository audit proved:

- `ActivitySpamFamilyLab` is registered only in `githubDebug` as a non-exported fallback;
- no app source launches it;
- `confirmSpam` is used only by that lab;
- `markOtherSpam` is used only by that lab through `SpamFamilyHumanActions`;
- `SpamFamilyReassigner` is used only by legacy `confirmSpam`;
- `ACTION_SAME_SPAM` / `ACTION_OTHER_SPAM` exist only for that lab/undo vocabulary;
- the fallback was introduced temporarily in commit `83469e1a9ed5b38cc26dcdfba4905f39297a546c` “while the new dashboard is exercised”;
- the main dashboard now contains the legitimate migrated capabilities: family list, rename, exact rescan, read-only family messages, global Spam/HAM review, undo/reset, details, and separate Spam Network diagnostics;
- `SpamFamilyStore.matchIdentity()` still consults legacy exclusion rows, so old fuzzy-lab state can currently suppress exact production identity and must be neutralized.

### Reply-alias closeout archaeology

The first final-head reply contract introduced in `41feaac0de7393aa6e54785d575f4dd6e57f4722` encoded an obsolete implementation shape: it required `resolveReplyExtra(context, selected, ref.deliveredto)`.

History proves that `2944d2f22b466c667f0a1111037ade4dc5abc856` had already intentionally changed the runtime to resolve from the reconstructed authoritative local value `replyDeliveredTo`, after writing that same value to `ref.deliveredto` and re-observing the historical message. `eb637ec8f6afa4b7a13b4d77b068d51f1a745b09` then strengthened the authority order so original envelope/recipient evidence outranks later routing `Delivered-To`.

Therefore the red final-head reply gate on `41feaac0` was a stale contract, not a runtime regression. The contract correction must preserve the actual v5 chain:

`original envelope/recipient evidence → replyDeliveredTo → ref.deliveredto persistence → re-observation → resolveReplyExtra(replyDeliveredTo) → draft.extra`

No Java/runtime change belongs to this correction.

---

## 4. Allowed runtime changes

Only these semantic changes are allowed:

1. `SpamFamilyStore.matchIdentity()` stops consulting `spam_family_exclusion`.
2. Remove `ActivitySpamFamilyLab` runtime source and its `githubDebug` manifest registration.
3. Remove `SpamFamilyHumanActions`.
4. Remove `SpamFamilyReassigner`.
5. Remove legacy `SpamFamilyLabRepository.confirmSpam`.
6. Remove legacy `SpamFamilyLabRepository.markOtherSpam`.
7. Remove legacy `SpamFamilyLabRepository.excludeFromFamily`.
8. Remove `SpamIntelligence.excludeFromFamily`.
9. Remove `SpamUndoManager.ACTION_SAME_SPAM` and `ACTION_OTHER_SPAM`.
10. Update the Spam Control entrypoint contract to require `ActivitySpamControl` as the sole runtime entrypoint.
11. Remove obsolete patch/audit workflows whose only purpose was the retired lab/correction subsystem.

---

## 5. Explicitly forbidden changes

This ChangeSet must not change:

- the canonical family normalization algorithm;
- family identity inputs;
- Spam Network/fuzzy diagnostics semantics;
- `spam_message` ownership of message truth;
- human Spam/HAM precedence;
- UNKNOWN-only exact bulk propagation;
- alias spam/ham side effects when an alias exists;
- alias compromise policy inputs;
- alias lifecycle / SMTP burn semantics;
- cPanel request/read-back/rollback behavior;
- reply-from-alias authority semantics;
- reset/undo snapshot semantics;
- Room DB version or migrations;
- `spam_family_exclusion` schema representation in this ChangeSet;
- mail deletion/movement behavior.

Any such diff invalidates this ChangeSet.

---

## 6. Expected changed/deleted paths

Runtime scope:

```text
app/src/githubDebug/AndroidManifest.xml
app/src/main/java/eu/faircode/email/SpamFamilyStore.java
app/src/main/java/eu/faircode/email/SpamIntelligence.java
app/src/main/java/eu/faircode/email/SpamFamilyLabRepository.java
app/src/main/java/eu/faircode/email/SpamUndoManager.java
app/src/main/java/eu/faircode/email/ActivitySpamFamilyLab.java          (delete)
app/src/main/java/eu/faircode/email/SpamFamilyHumanActions.java        (delete)
app/src/main/java/eu/faircode/email/SpamFamilyReassigner.java          (delete)
```

Governance/test scope:

```text
docs/spam-control/DECISION_LEDGER.md
docs/spam-control/SEMANTIC_SYSTEM_MODEL.md
docs/spam-control/CURRENT_CHANGESET.md
.github/scripts/verify_spam_exact_family_retirement.py
.github/workflows/verify-spam-exact-family-retirement.yml
.github/scripts/verify_reply_alias_authority.py
.github/workflows/verify-reply-alias-authority.yml
.github/workflows/audit-spam-control-entrypoints.yml
.github/workflows/spam-intelligence-android.yml   (gate trigger / path cleanup only)
```

Temporary archaeology/apply workflows may be removed after their evidence is captured in history.

---

## 7. Regression matrix

### Must go red before implementation

`verify_spam_exact_family_retirement.py` must fail on the pre-retirement source because:

- exact lookup still reads exclusion state;
- fallback lab is registered;
- correction runtime files/methods still exist.

### Must be green after implementation

1. exact-family retirement contract;
2. bootstrap/message-authority contract;
3. canonical-message + alias-sideeffect contract;
4. reset/undo contract;
5. scan/Terminal/cPanel observability contract;
6. reply-from-alias authority contract;
7. CI contract-coverage contract;
8. Spam Control entrypoint audit;
9. exact-family/core invariants;
10. Android Java + Room compile;
11. Room migration verification;
12. githubDebug APK assembly;
13. pinned signer verification;
14. checksummed artifact creation.

### Source invariants

- same normalized sender-name + subject → same family;
- different sender-name or subject → different family;
- incomplete identity → isolated per-message family;
- exact `matchIdentity()` ignores legacy exclusions;
- fuzzy similarity never becomes family authority;
- explicit HAM survives propagation/rescore;
- Spam/HAM review remains single-flight + undoable;
- legacy Same/Other Spam runtime symbols are absent;
- `ActivitySpamControl` is the sole Spam Control runtime entrypoint.

### Cross-feature invariant

Reply-from-alias must remain unchanged semantically. Original envelope evidence is authoritative ahead of later routing `Delivered-To`; the resolved alias is persisted to `ref.deliveredto`, re-observed, and the same authoritative value is consumed by reply-extra resolution.

---

## 8. Recovery / rollback

No DB migration occurs. Existing exclusion rows remain stored and snapshot-compatible, so rollback to the base commit does not require data reconstruction.

Rollback target:

`c2c78a5dced58ae2d72a3383e080ded0d5bbdf55`

No force-push is permitted. Candidate must fast-forward from the recorded base lineage.

---

## 9. Definition of Done

This ChangeSet closes only when all are true:

- DL-013/DL-014 are DECIDED and match runtime behavior;
- legacy fallback/correction runtime is gone;
- exact identity cannot be suppressed by legacy exclusion state;
- exclusion storage remains readable for rollback/snapshot compatibility;
- no unrelated app source changed;
- all semantic contracts, including reply-from-alias authority, are green on the same final candidate SHA;
- Android compile/migrations/APK/signing/checksum gates are green on that same SHA;
- base→candidate diff matches the explicit scope;
- `feature/spam-control-p0` has not moved unexpectedly;
- the candidate can be fast-forwarded without rewriting history.

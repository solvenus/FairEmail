# Spam Control — Decision Ledger

**Status:** Living decision authority  
**Rule:** No semantic decision may silently drift through implementation details.  
**Companion:** `SEMANTIC_SYSTEM_MODEL.md`

Each entry records the chosen meaning, why it exists, what it must not drift into, dependent flows, and the regression evidence required to change it.

---

## DL-001 — Canonical message truth is `spam_message`

**STATUS:** DECIDED

### Decision

Message-level Spam Control truth is owned by `spam_message`, identified by `(account_uuid, message_id)`.

### Why

Spam Control must classify/review a retained message even when FairEmail has no `Envelope-To`. Alias metadata is useful enrichment but not a prerequisite for message existence.

### Must not drift into

- `alias_delivery` as message admission gate;
- folder location as label truth;
- sender address as message identity;
- a review model that requires an already learned family.

### Dependent flows

Historical scan, live observation, review queue/count, Spam/HAM learning, exact family prediction, bulk propagation, reset/undo, diagnostics, family correction.

### Required regression evidence

- UNKNOWN Junk without Envelope-To is reviewable with zero families;
- canonical learning succeeds without alias delivery;
- reset preserves canonical index;
- scan before/after counts use `DaoSpamMessage`.

---

## DL-002 — Alias intelligence is additive enrichment

**STATUS:** DECIDED

### Decision

`alias` + `alias_delivery` own alias-specific history, evidence and lifecycle. When alias data exists, legacy semantic side effects remain active; when it does not exist, message truth still works.

### Why

The old alias path encoded valuable state: spam/ham counters, family counters, service/trusted-domain evidence, compromise policy, traffic assessment, reply-capable alias knowledge and server lifecycle.

### Must not drift into

- deleting alias behavior merely because `spam_message` is cleaner;
- making alias existence mandatory for message-level actions;
- duplicating a second independent message truth inside alias state.

### Dependent flows

Spam/HAM learning, exact bulk propagation, compromise review, alias dashboard, reply-from-alias, cPanel readiness/burn/restore.

### Required regression evidence

When alias exists, `SpamAliasStore` bookkeeping and compromise-policy evidence survive; when alias is missing, canonical action still succeeds.

---

## DL-003 — Spam Family identity is deterministic

**STATUS:** DECIDED

### Decision

Production Spam Family identity is:

`normalize(sender display name) + normalize(subject)`

Normalization uses NFKC, NBSP→space, lowercase, whitespace collapse and trim.

### Why

Real campaigns rotate sender addresses/body details while retaining the human-recognizable sender-name + subject identity. Family membership must be deterministic and explainable.

### Must not drift into

- sender email address as identity;
- body/template/link fuzzy similarity as family identity;
- nearest-neighbor score as provenance of an exact family;
- aggressive token stripping that merges distinct subjects.

### Dependent flows

Learning, prediction, bulk propagation, review provenance, family administration, auto-label exact, undo/reset, network analysis boundary.

### Required regression evidence

- same name + same subject → same family;
- same name + different subject → different family;
- different name + same subject → different family;
- incomplete identity → isolated per-message family;
- fuzzy legacy/network match never masquerades as exact prediction.

---

## DL-004 — Spam Network is not Spam Family

**STATUS:** DECIDED

### Decision

Fuzzy fingerprint/template/link/infrastructure similarity belongs to Spam Network/diagnostics. It may correlate families but never define production family identity.

### Why

Similarity answers “these campaigns resemble each other”; identity answers “these messages are the same deterministic family”. They are different concepts.

### Must not drift into

- fuzzy learning fallback in normal Spam action;
- fuzzy score used as exact provenance;
- network cluster replacing family mapping.

### Dependent flows

`SpamNetworkAnalyzer`, diagnostic/lab tools, legacy compatibility functions in `SpamFamilyStore`.

### Required regression evidence

Production label/prediction paths use exact identity APIs; fuzzy APIs remain outside family authority.

---

## DL-005 — Missing family identity creates an isolated family

**STATUS:** DECIDED

### Decision

If a Spam message lacks the complete sender-name + subject identity, it is learned using a deterministic per-message isolated key.

### Why

Missing data cannot justify joining another family. Fuzzy fallback would violate deterministic identity.

### Must not drift into

- fuzzy join;
- dropping the Spam truth;
- silently inventing sender-name/subject values.

### Dependent flows

Explicit Spam learning, reset/undo, later rescoring when raw message identity becomes available.

### Required regression evidence

Distinct message IDs yield distinct isolated keys; incomplete identity never calls fuzzy family ownership.

---

## DL-006 — Folder is evidence, not label truth

**STATUS:** DECIDED

### Decision

Junk placement admits review work. Inbox placement provides context. Neither is an explicit Spam/HAM label.

### Why

Existing mail placement may come from server/client filtering and cannot replace human truth.

### Must not drift into

- Junk → SPAM automatically;
- Inbox → HAM automatically.

### Required regression evidence

UNKNOWN Junk is reviewable; UNKNOWN Inbox remains UNKNOWN absent other evidence.

---

## DL-007 — Explicit human labels outrank derived state

**STATUS:** DECIDED

### Decision

Human Spam/HAM is explicit truth. Exact predictions and bulk propagation are derived/automation behavior and cannot overwrite explicit contradictory truth without a new explicit human action.

### Why

The product is a human-controlled intelligence layer.

### Must not drift into

- exact bulk converting HAM to SPAM;
- rescorer changing explicit labels;
- folder movement changing explicit labels.

### Required regression evidence

Bulk queries UNKNOWN-only; explicit HAM remains HAM after exact propagation/rescore.

---

## DL-008 — Alias compromise is not equivalent to Spam

**STATUS:** DECIDED

### Decision

A Spam label feeds compromise evidence but does not itself establish `COMPROMISED`.

`AliasCompromisePolicy` receives explicit Spam plus traffic suspicion, sender-known, expected-context-known, expected sender match and alias HAM history.

### Why

Expected service senders can send Spam/newsletter-like mail without proving the alias leaked.

### Must not drift into

- `Spam => COMPROMISED`;
- invented one-bit trusted-sender shortcut that discards the historical evidence set.

### Required regression evidence

Canonical + alias-sideeffect contract asserts the full compromise adapter inputs.

---

## DL-009 — SMTP rejection is physical server state

**STATUS:** DECIDED

### Decision

`COMPROMISED` is local alias lifecycle. Verified hard reject is separate SMTP/server state and requires cPanel read-back.

### Why

Intent/requested state is not remote truth.

### Must not drift into

- ordinary reply email bounce;
- local database flag presented as verified server behavior;
- destructive mutation before safe preflight unless user explicitly accepts an unsafe path.

### Required regression evidence

Read-only preflight, route-plan/rollback tests, post-mutation read-back, persistent sanitized diagnostics.

---

## DL-010 — Reset and Undo are semantic architecture

**STATUS:** DECIDED

### Decision

Every human/automation action that changes learned state must define its undo/reset semantics at the same time as the state change.

Reset clears learned/derived state in-place while preserving canonical message visibility and raw mail. Undo restores a pre-mutation semantic snapshot before the history entry is marked undone.

### Why

Recovery after the fact cannot be lossless if the original state owners were not captured.

### Must not drift into

- reset deleting `spam_message` rows;
- reset deleting FairEmail mail;
- account A undoing account B;
- adding a mutable state owner without adding it to the relevant snapshot.

### Required regression evidence

Reset/undo contract covers message, alias, delivery, families, exemplars, exclusions, exact identity meta and compromise meta.

---

## DL-011 — Observability is part of the operation

**STATUS:** DECIDED

### Decision

Historical scan and cPanel integration are born with persistent sanitized observability. A failure that cannot explain what it attempted and what state/response it observed is a product failure.

### Must not drift into

- one-line generic exception only;
- credentials/body logging;
- UI counts from the wrong state owner;
- parser error before response-shape telemetry.

### Required regression evidence

Observability contract mechanically protects scan ordering/counts, Terminal surface, log redaction and cPanel request/response shape logging.

---

## DL-012 — Reply-from-alias must remain independent and preserved

**STATUS:** DECIDED

### Decision

For replies, the incoming `Envelope-To` is the authoritative desired alias address. Spam Control alias knowledge may validate reply capability, but Spam Control refactors must not rewrite the meaning of reply identity.

### Why

Catchall/per-service aliases are a separate user capability that shares alias data but not Spam classification semantics.

### Must not drift into

- sender From-address used instead of Envelope-To;
- reply behavior made dependent on family learning;
- Spam reset destroying reply capability/inventory.

### Required regression evidence

Reply-from-alias regression must remain in the release matrix after Spam Control architecture changes.

---

## DL-013 — Exact family identity has no per-message “Other spam” override

**STATUS:** DECIDED

### Decision

In production, family membership is definitional: one complete normalized sender-display-name + normalized subject identity maps to one Spam Family.

The old `Annen spam` action belonged to the earlier fuzzy-family review model. It meant “Spam, but not the currently suggested fuzzy group”. Once family identity became deterministic, that action ceased to be a valid family operation. We do not create a per-message exception that contradicts the identity definition.

`spam_family_exclusion` may remain temporarily in storage/snapshots for backward-compatible recovery, but exact production lookup must ignore it. No production writer may create new message-level family exclusions after the legacy correction subsystem is retired.

### Why

A per-message family override would make the core family definition non-deterministic: two messages with the same canonical identity could belong to different families. That would reintroduce ambiguity precisely where exact-family was chosen to eliminate it.

The capability audit proved that the old `Samme spam / Annen spam / Ikke spam` workflow is confined to the non-exported `githubDebug` fallback lab. The production dashboard instead says that family organization is automatic from sender name + subject and exposes only Spam / Ikke spam in the global review queue.

### Must not drift into

- per-message exact-family overrides;
- legacy exclusion rows suppressing exact `matchIdentity()`;
- fuzzy similarity deciding family identity;
- translating “other spam” to HAM;
- keeping an unreachable debug workflow alive as semantic authority.

### Migration rule

The legacy exclusion table is preserved during this ChangeSet so existing undo/reset snapshots remain readable. It becomes inert for exact-family production. Physical schema/table removal is a separate migration with its own snapshot-compatibility plan.

### Required regression evidence

- `matchIdentity()` does not consult `spam_family_exclusion`;
- the legacy Spam Family Lab is not registered as a runtime activity;
- legacy `confirmSpam`, `markOtherSpam`, `excludeFromFamily`, `SpamFamilyHumanActions` and `SpamFamilyReassigner` runtime paths are absent;
- exact family learning/prediction still groups equal canonical identities;
- Spam/HAM review and undo/reset remain intact.

---

## DL-014 — Message labeling and identity-level family binding are distinct operations

**STATUS:** DECIDED

### Decision

A human message label (`Spam` / `Ikke spam`) is message-level truth. A family binding is identity-level truth.

A code path may bind an identity to a family only when the family-id already has exact-identity provenance for that same canonical identity, or when creating the initial exact mapping from that identity. Current legitimate examples are exact learning, exact bulk propagation and exact auto-confirm.

A UI action that addresses one message must never silently mean “rebind this canonical identity to a different family”. The old lab's `confirmSpam` / reassign flow is retired rather than repaired because its semantics came from the fuzzy-family model.

### Why

`learnSpamIntoFamily(...)` plus `bindIdentity(...)` is global state. Treating it as a generic one-message correction would alter future/twin messages and violate the user's action scope.

### Must not drift into

- arbitrary requested-family IDs from message-level UI;
- manual one-message correction globally rebinding future exact twins;
- exact bulk propagation accepting a family without exact provenance;
- a second family-assignment authority outside the canonical identity mapping.

### Required regression evidence

- no legacy message-level family assignment caller remains;
- exact bulk/auto-confirm source family comes from deterministic exact identity state;
- equal canonical identities remain bound consistently;
- explicit HAM is never overwritten by propagation.

---

## DL-015 — Migration is additive before substitutive

**STATUS:** DECIDED

### Decision

Canonical migration proceeds one consumer at a time. Old alias side effects remain where semantically necessary until equivalence/intentional retirement is proven.

### Current migration inventory

Already canonical:

- historical message index;
- review queue/count;
- basic Spam/HAM result checks;
- exact bulk seed/propagation;
- rescore paging/prediction authority;
- reset/undo snapshots for canonical message state.

Current retirement target:

- debug-only `ActivitySpamFamilyLab` fallback;
- `confirmSpam`;
- `excludeFromFamily`;
- `SpamFamilyHumanActions.markOtherSpam`;
- `SpamFamilyReassigner`;
- exact lookup's legacy exclusion read.

Preserved compatibility state during this ChangeSet:

- `spam_family_exclusion` schema/DAO/snapshot representation, inert for exact production, until a dedicated schema migration can prove backward-compatible recovery.

### Rule

Do not call Port A/canonical migration complete until the retirement target is empty and every remaining alias path is explicitly classified as a legitimate mirror/enrichment/diagnostic.

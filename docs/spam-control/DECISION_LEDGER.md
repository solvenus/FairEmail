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

## DL-013 — Human “Other spam” is a message-level contradiction of the current family

**STATUS:** OPEN — MUST BE RESOLVED BEFORE CODE CHANGE

### Established human meaning

`Other spam` means:

> This message is Spam, but it does not belong to the currently presented family.

It must never be translated to HAM.

### Current semantic conflict

- production exact identity maps normalized sender-name + subject to one family;
- a message-specific exclusion correctly says “not this family”;
- `matchIdentity()` respects that exclusion;
- `learnSpamExact()` follows the persistent identity mapping directly;
- current `Other spam` flow performs exclude/clear/relearn and can therefore return the same message to the excluded identity family.

### Forbidden “fixes”

- re-enable fuzzy family identity;
- delete/ignore the human exclusion;
- silently remap the global identity because one message is exceptional;
- call the exceptional message HAM;
- implement a patch before choosing the override state model.

### Candidate model to evaluate

A **per-message family override** appears semantically consistent:

1. preserve global account-level exact identity mapping for ordinary twins;
2. store the negative relation to the rejected family;
3. keep this message SPAM;
4. assign the exceptional message to an isolated/per-message family or explicit per-message target without rebinding the global identity;
5. exact prediction for this message must respect the override/exclusion;
6. undo/reset must snapshot/restore the override state;
7. alias family counters mirror the final canonical family only when alias exists.

This candidate is **not yet DECIDED**. Before implementation, inspect UI semantics and all callers that mean “assign this one message” versus “rebind this identity”.

### Required decision tests before implementation

- Other Spam never returns to the rejected family;
- message remains SPAM;
- global identity mapping for untouched twins remains unchanged;
- aliasless message can perform the correction;
- alias counters mirror only when alias exists;
- undo is lossless;
- reset clears learned override while preserving message index.

---

## DL-014 — Explicit assignment vs global identity rebinding are different operations

**STATUS:** OPEN — MUST BE RESOLVED WITH DL-013

### Problem

Current explicit-family path can call `learnSpamIntoFamily(...)` and then bind the message identity key to that family. That turns a message-level assignment into an account-wide identity remap.

### Required semantic distinction

We must decide whether the UI action means:

A. **Assign this message to this family** — per-message correction, global identity unchanged; or

B. **This exact identity belongs to this family** — account-level identity remap, affecting future/twin messages.

These operations cannot remain conflated.

### Forbidden state

A UI action worded as one-message correction silently rebinding all future messages with the same exact identity.

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

Not yet fully canonical:

- `confirmSpam`;
- `excludeFromFamily`;
- `SpamFamilyHumanActions.markOtherSpam`;
- `SpamFamilyReassigner`;
- remaining legacy DAO/query surfaces requiring classification.

### Rule

Do not call Port A/canonical migration complete until this inventory is empty or every remaining alias path is explicitly classified as a legitimate mirror/enrichment/diagnostic.

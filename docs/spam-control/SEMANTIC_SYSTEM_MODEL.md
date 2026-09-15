# Spam Control — Living Semantic System Model

**Status:** Living architecture authority  
**Method:** Semantic Architecture Engineering / Concept-Driven Software Engineering  
**Operational branch:** `feature/spam-control-p0`  
**Current reconstruction baseline:** `dece32b7089e6447f6adf3b32449c03fcfb1c5dd`  
**Known phone baseline:** `safety/spam-control-phone-checkpoint-6e7b6a52`  

This document exists beside the code and is part of programming, not post-hoc documentation. A ChangeSet that changes the semantics below must update this model, the Decision Ledger, the affected regression contract, and runtime observability in the same unit of work.

---

## 1. Project intent / capability model

Spam Control is a human-controlled intelligence layer over FairEmail. It must let one person:

1. review retained mail as spam/not-spam without requiring alias metadata;
2. learn deterministic spam families from explicit human truth;
3. propagate an exact family decision to matching retained UNKNOWN messages without overwriting explicit HAM;
4. maintain optional alias intelligence when `Envelope-To` exists;
5. distinguish spam classification from alias compromise;
6. distinguish local alias lifecycle from physical SMTP rejection state;
7. inspect and undo human learning actions;
8. reset learning without losing raw message visibility;
9. explain historical scans, classification, alias decisions and cPanel operations through persistent observability;
10. preserve FairEmail reply-from-alias behavior while Spam Control evolves.

The user is the final authority on explicit Spam / Ikke spam / family-correction actions. Derived predictions may assist but must never silently override explicit human truth.

---

## 2. Domain model

### 2.1 FairEmail Mail Message

Identity: FairEmail `message.id` within its owning account.

Raw source object. It owns mail content/folder membership in FairEmail's main DB. Spam Control does not duplicate bodies or raw EML.

### 2.2 Spam Message

Implementation: `EntitySpamMessage` / `spam_message`.

Identity: `(account_uuid, message_id)`.

Meaning: canonical per-message Spam Control representation whether or not `Envelope-To` exists.

Owns:

- raw observation context used by Spam Control: `received`, `folder_type`, normalized `delivered_to` when available;
- explicit/classifier message label: UNKNOWN / HAM / SPAM;
- confirmed `family_id` for SPAM;
- derived exact `predicted_family_id`, score and assessment time.

It does **not** own alias lifecycle or server state.

### 2.3 Alias

Implementation: `EntityAlias` / `alias`.

Identity: `(account_uuid, normalized address)`.

Meaning: persistent inventory entry for an observed envelope recipient.

Owns:

- service/service-domain knowledge;
- observed and trusted sender-domain knowledge;
- alias spam/ham evidence counters;
- family counters for alias context;
- local lifecycle state: ACTIVE / REPLACED / DISABLED / IGNORED / COMPROMISED;
- replacement metadata;
- SMTP rejection lifecycle and route snapshot metadata.

### 2.4 Alias Delivery

Implementation: `EntityAliasDelivery` / `alias_delivery`.

Identity: `(account_uuid, message_id)`.

Meaning: optional alias enrichment/idempotence/history ledger for one message whose envelope alias is known.

Owns alias-context observations and assessment snapshots. Its label/family fields are a **compatibility/enrichment mirror**, not canonical message truth.

A message without this row remains fully valid Spam Control state.

### 2.5 Spam Family

Implementation: `spam_family` + exemplars + exact identity mapping.

Meaning: deterministic human spam group.

Production identity:

`normalized sender display name + normalized subject`

Normalization currently uses Unicode NFKC, NBSP→space, lowercase, whitespace collapse and trim.

Explicitly excluded from family identity:

- sender email address;
- delivery alias;
- HTML/template similarity;
- link similarity;
- rotating product/body details.

### 2.6 Exact Family Identity Mapping

Implementation: `SpamFamilyIdentity` + `EntitySpamMeta` keys `family_identity:<account>:<identity-key>`.

Meaning: persistent binding from deterministic identity key to family id.

If sender-name or subject is missing, production spam learning uses a deterministic **isolated per-message key**. Missing identity must never reactivate fuzzy family joining.

### 2.7 Family Exclusion

Implementation: `spam_family_exclusion`.

Identity: `(account_uuid, message_id, family_id)`.

Meaning: explicit negative relation: this message must not be claimed by this family.

Production exact lookup respects this negative relation.

### 2.8 Family Exemplar

Implementation: `spam_family_exemplar`.

Meaning: bounded message fingerprint evidence stored for a family.

In production exact-family identity, exemplars do not choose the family. They remain useful for diagnostics, network/template analysis and historical compatibility.

### 2.9 Spam Network

Implementation: `SpamNetworkAnalyzer` and fuzzy fingerprint similarity.

Meaning: diagnostic/template/infrastructure relation across families.

A Spam Network is **not** a Spam Family and may never own family identity.

### 2.10 Server Route State

Implementation: cPanel/UAPI read-back through `CpanelAliasActuator` / transaction helpers.

Meaning: physical server truth about whether an alias is currently hard-rejected and what explicit routes exist.

Local database intent is not server truth. Verification requires read-back.

---

## 3. State ownership map

| State / truth | Canonical owner | Mirrors / derived views | Forbidden substitute |
|---|---|---|---|
| Message Spam/HAM/UNKNOWN | `spam_message.label` | `alias_delivery.label` when alias exists | folder placement |
| Confirmed family membership | `spam_message.family_id` + family/exemplar state | `alias_delivery.family_id`; alias counters | fuzzy best-match |
| Exact prediction | `spam_message.predicted_family_id` | alias delivery compatibility mirror | legacy fuzzy score |
| Message visibility in Spam Control | `spam_message` | review/UI projections | `alias_delivery` existence |
| Envelope alias observation | `alias` + `alias_delivery` | `spam_message.delivered_to` context | inferred sender address |
| Alias compromise lifecycle | `alias.state` | dashboard buckets | Spam label alone |
| SMTP rejection lifecycle | `alias.smtp_reject_*` + server read-back | UI readiness | `COMPROMISED` state |
| Family identity | normalized sender-name + subject mapping | family descriptor | body/template/link fuzziness |
| Family exclusion | `spam_family_exclusion` | review/correction UI | temporary prediction clearing |
| Physical route truth | cPanel read-back | local snapshot/audit metadata | requested operation |
| Reply-from-alias desired address | incoming message `Envelope-To` | observed Alias Registry for capability validation | sender From-address |

**Ownership rule:** Adding a table/cache/mirror does not transfer authority unless this document and Decision Ledger explicitly declare a migration.

---

## 4. Producer / consumer graph

### 4.1 `spam_message`

**Producers**

- `SpamHistoricalScanner` for retained Inbox/Junk;
- normal `SpamIntelligence.observeMessage` on newly observed mail;
- explicit Spam / Ikke spam through `SpamMessageStore.setLabel`;
- exact-family refresh / rescoring for predictions;
- reset/undo restore for learned fields.

**Consumers**

- global review queue and queue count;
- family candidate UI;
- exact bulk propagation;
- rescorer paging;
- learning snapshots and undo/reset;
- scan before/after diagnostics;
- family stats/correction paths as they complete canonical migration.

**Required side effects when alias exists**

- alias spam/ham counters;
- alias family counters;
- alias delivery mirror;
- traffic assessment refresh;
- compromise policy evaluation when explicit spam is learned;
- compromise-review metadata transitions.

### 4.2 `alias` / `alias_delivery`

**Producers**

- mail observation when `Envelope-To` exists;
- historical scan enrichment;
- explicit spam/ham mirror;
- traffic analyzer / assessment;
- human alias lifecycle actions;
- cPanel burn/restore state transitions.

**Consumers**

- alias dashboard and unresolved/compromise workflows;
- reply-capable alias lookup;
- compromise policy;
- cPanel readiness/burn/restore;
- review admission only for the optional `SUSPICIOUS` evidence path.

**Invariant:** failure to produce an alias row must not block message classification, review, family identity, reset or undo.

### 4.3 Exact family mapping

**Producers**

- first explicit SPAM learning for a complete identity;
- explicit assignment into an existing family when human semantics allow it;
- restore from undo snapshot.

**Consumers**

- `matchIdentity` production prediction;
- exact bulk propagation;
- auto-label exact when enabled;
- review provenance validation;
- family administration.

### 4.4 cPanel server state

**Producers**

- remote cPanel server only.

**Consumers**

- verification/read-back;
- local SMTP rejection state after verified operation;
- rollback decisions.

---

## 5. Behavioral model / state transitions

### 5.1 Message learning

```text
RETAINED MAIL
    ↓ observe/index
spam_message UNKNOWN
    ├─ human Ikke spam ─────────────→ HAM
    ├─ human Spam ──────────────────→ SPAM + exact/isolated family
    └─ exact prediction ────────────→ UNKNOWN + predicted family
                                         └─ optional auto-label policy → SPAM
```

Folder placement never performs UNKNOWN→HAM or UNKNOWN→SPAM by itself.

### 5.2 Review admission

An indexed message is review work when policy permits reviewed items and at least one admission reason exists:

- it is in Junk; or
- it has a revalidated exact family prediction; or
- optional alias enrichment says traffic is `SUSPICIOUS`.

`Envelope-To` is never required.

Plain UNKNOWN Inbox with no other evidence is indexed but not automatically review work.

### 5.3 Explicit Spam

Current intended transition:

1. ensure canonical message row exists;
2. derive deterministic family identity;
3. learn into mapped exact family, or create/bind family;
4. if identity incomplete, learn into isolated per-message family;
5. set canonical `spam_message` SPAM + family;
6. if alias delivery exists, mirror through `SpamAliasStore` preserving counters;
7. reconcile family lifecycle;
8. evaluate alias compromise using traffic/service/trusted-domain/alias-HAM evidence;
9. refresh optional alias assessment;
10. refresh exact family prediction;
11. rescore affected family state.

### 5.4 Explicit HAM

Current intended transition:

1. set canonical message HAM and clear confirmed family id;
2. remove message exemplar / reconcile prior family if it was SPAM;
3. if alias exists, mirror HAM counters and delivery label;
4. preserve explicit HAM against later bulk propagation;
5. refresh derived predictions/assessment.

### 5.5 Exact bulk propagation

One human Spam decision may propagate only to retained **UNKNOWN** messages with the exact deterministic identity. Explicit HAM is a hard exception.

Propagation must reuse the normal Spam learning path so optional alias side effects remain intact.

### 5.6 Alias compromise

`SPAM` and `COMPROMISED` are different truths.

Explicit spam feeds `AliasCompromisePolicy` with:

- explicitSpam=true;
- current suspicious traffic verdict;
- whether sender domain is known;
- service/trusted-domain context;
- whether sender matches expected context;
- accumulated alias HAM evidence.

Possible semantic outcomes: KEEP / REVIEW / COMPROMISE.

### 5.7 SMTP burn

`COMPROMISED` is local lifecycle evidence. `SMTP_REJECT_VERIFIED` is physical server state.

Burn/restore follows preflight → mutation → read-back → verified local state. Ordinary reply-email bounces are not equivalent to server hard rejection.

### 5.8 Reset

Reset means **erase learned/derived Spam Control state while preserving raw visibility and passive inventory required by the defined contract**.

It must preserve:

- FairEmail mail;
- canonical `spam_message` rows / raw observation context;
- passive alias inventory and manually configured domain context unless explicitly declared otherwise;
- server truth / cPanel state.

It clears/restores through semantic snapshots:

- message labels/family predictions;
- delivery learned fields;
- alias learned counters/state covered by snapshot contract;
- families/exemplars/exclusions;
- exact identity meta;
- compromise-review meta;
- derived rescore work.

Reset itself is undoable.

### 5.9 Undo

Undo is semantic, persistent and account-local for normal account actions.

The snapshot is captured **before** mutation. History is marked undone only after restore succeeds. Restored accounts are rescored afterwards.

---

## 6. Invariant registry

### MESSAGE

**INV-M01** Every retained message selected for Spam Control import can exist in `spam_message` without `Envelope-To`.

**INV-M02** Junk is review evidence, not automatic SPAM truth.

**INV-M03** Inbox is context, not automatic HAM truth.

**INV-M04** Explicit HAM is never overwritten by exact bulk propagation.

**INV-M05** Canonical message learning succeeds independently of alias availability.

### FAMILY

**INV-F01** Production family identity = normalized sender display name + normalized subject.

**INV-F02** Same name + same subject → same identity family unless an explicit human override relation says otherwise.

**INV-F03** Same name + different subject → different identity.

**INV-F04** Different name + same subject → different identity.

**INV-F05** Missing complete identity → isolated message family, never fuzzy join.

**INV-F06** Fuzzy body/template/link/sender-address similarity may power Spam Network diagnostics; it may never own production family identity.

**INV-F07** Disabled/inactive family is not an automatic exact match; reactivation must be an explicit lifecycle consequence of valid learning/admin behavior.

**INV-F08** A human family correction must not be silently reversed by automatic relearning in the same action.

### ALIAS

**INV-A01** Alias enrichment is additive. It may never become the admission ticket to Spam Control message truth.

**INV-A02** When alias delivery exists, legacy spam/ham/family counter side effects must be preserved unless Decision Ledger explicitly retires them.

**INV-A03** Spam does not imply compromised alias.

**INV-A04** `SMTP-DEAD` / verified hard rejection is not synonymous with `COMPROMISED`.

### RECOVERY

**INV-R01** Every human action that fans out across learned state snapshots every state owner it can mutate.

**INV-R02** Reset must not delete canonical message visibility.

**INV-R03** Undo restores before history is marked undone.

**INV-R04** Reset and undo do not mutate mail bodies/raw EML.

### INTEGRATION / OBSERVABILITY

**INV-O01** Historical scan logs raw examined, canonical indexed/new, alias enriched/new, Inbox/Junk, missing Envelope-To and missing folder.

**INV-O02** cPanel logs sanitized request/response shape before throwing parser/shape errors.

**INV-O03** Credentials, Authorization headers, passwords and mail bodies are never written to Spam Control diagnostics.

### REPLY ALIAS

**INV-P01** Reply-to-mail uses the incoming `Envelope-To` alias as the desired From alias when it is valid/reply-capable.

**INV-P02** Spam Control changes must not regress reply-from-alias behavior.

---

## 7. Forbidden / impossible states

The following states are semantically invalid even if Java/Room can represent them:

1. a Junk message with no `Envelope-To` being invisible solely because no alias row exists;
2. a fuzzy prediction presented as exact-family provenance;
3. explicit HAM later relabeled SPAM by bulk propagation without a new human action;
4. local `COMPROMISED` being presented as verified SMTP hard rejection;
5. cPanel mutation marked verified without read-back;
6. reset deleting the canonical message index or raw mail;
7. `Other spam` action ending in the same excluded family because exact relearning ignored the human correction;
8. one human action creating multiple undo points because of rapid duplicate UI execution;
9. a UI work bucket with no exit action unless it is deliberately read-only and labeled as such.

---

## 8. Recovery semantics

| Operation | Snapshot scope | Must survive | Must rebuild/refresh |
|---|---|---|---|
| Spam / Ikke spam single message | message + affected families + exact/compromise meta + optional delivery/alias | raw message observation | family prediction / affected rescore |
| Exact bulk Spam | full account learned state | raw account message index | affected/all active family rescore |
| Alias lifecycle action | full account learned state where action can affect counters/meta | mail + server truth unless server action is part of operation | dashboard/assessment |
| Reset all learning | full global learned state | raw mail, canonical index, passive inventory per contract, server state | rescore after undo |
| cPanel burn | route snapshot + local server-action metadata | original routes until verified mutation | server read-back |
| cPanel restore | stored route snapshot | intended pre-burn route semantics | server read-back |

---

## 9. Observability contract

### Historical scan

Must expose at minimum:

```text
SCAN START account=… inbox=… junk=…
PAGE afterMessageId=… rows=…
INDEX message=… folder=… new=… envelope=present|missing
DONE examined=… messageIndexed=… messageNew=… aliasObserved=… aliasNew=… inbox=… junk=… noEnvelope=… missingFolder=…
```

UI before/after counts must come from canonical `DaoSpamMessage`, not alias ledger counts.

### cPanel

Before UAPI shape validation fails, diagnostics must expose sanitized:

- module/function and final endpoint;
- HTTP status;
- Content-Type;
- top-level JSON keys;
- sanitized response body at trace level;
- explicit distinction between UAPI `result`, legacy API2 `cpanelresult`, and WHM/API `metadata/data` shapes.

### Human actions

The system must retain enough action history to explain what user truth changed and support account-local undo.

---

## 10. Semantic verification matrix

| Case | Expected semantic result | Mechanical owner |
|---|---|---|
| Junk + no Envelope-To + UNKNOWN + no family | visible in review | bootstrap contract |
| Junk + Envelope-To + UNKNOWN | visible in review | bootstrap contract |
| Inbox + UNKNOWN + no evidence | indexed, not automatically review/HAM | bootstrap contract |
| Inbox + suspicious alias evidence | review candidate | bootstrap contract |
| exact prediction | review candidate after exact provenance revalidation | repository/runtime contract |
| human Spam with no alias row | canonical SPAM/family succeeds | alias-sideeffect contract |
| human Spam with alias row | canonical truth + old alias counters/policy | alias-sideeffect contract |
| human HAM | canonical HAM; bulk may not overwrite | bootstrap + bulk contract |
| reset | message index remains; learning clears | reset/undo contract |
| undo | full affected semantic state restored before history marked undone | reset/undo contract |
| cPanel unexpected response shape | Terminal explains actual shape | observability contract |
| exact family same name+subject | same family | spam-family core |
| exact family same name+different subject | different family | spam-family core |
| exact family different name+same subject | different family | spam-family core |
| missing family identity | isolated family | spam-family core |
| reply to catchall alias | exact Envelope-To local-part becomes From extra | reply-alias regression |

---

## 11. Architecture map

```text
FairEmail main DB / retained mail
        │
        ├── live observation ────────────────┐
        └── historical Inbox/Junk scanner ──┤
                                            ▼
                                  SpamMessageStore
                                            │
                                            ▼
                                  spam_message  ← canonical message truth
                                            │
                      ┌─────────────────────┼─────────────────────┐
                      │                     │                     │
                      ▼                     ▼                     ▼
                 Review Queue          Exact Family         Undo / Reset
                                            │
                         sender-name + subject identity
                                            │
                                            ▼
                                 family identity mapping
                                            │
                             ┌──────────────┴──────────────┐
                             ▼                             ▼
                        spam_family                    exclusions
                             │
                             ▼
                         exemplars ─── fuzzy/network diagnostics only

Envelope-To present
        │
        ▼
Alias Registry / alias_delivery  ← optional enrichment + compatibility mirror
        │
        ├── traffic assessment
        ├── spam/ham/family counters
        ├── compromise policy
        ├── reply-capable alias knowledge
        └── cPanel lifecycle
                 │
                 ▼
          cPanel remote read-back  ← physical server truth
```

Dependency direction rule: optional alias state may enrich canonical message behavior; canonical message existence must not depend on alias state.

---

## 12. Current archaeological findings / open migration gaps

These are **not** declared finished merely because the app compiles.

### OPEN-GAP-01 — `confirmSpam` still has alias ownership leakage

`SpamFamilyLabRepository.confirmSpam()` decides reassign need from `alias_delivery` and validates success by reading `alias_delivery`. Aliasless canonical messages therefore cannot be treated as fully migrated through this path.

### OPEN-GAP-02 — `excludeFromFamily` still requires alias delivery

`SpamIntelligence.excludeFromFamily()` returns false when `alias_delivery` is absent. Exclusion is a message↔family relation and therefore should not semantically require alias enrichment.

### OPEN-GAP-03 — `Other spam` conflicts with exact identity learning

Historical meaning is correct: **spam, but not this family**.

Current implementation is alias-led and performs exclusion/clear/relearn. `matchIdentity()` respects exclusion, but `learnSpamExact()` follows the persistent identity mapping directly. Relearning can therefore contradict the human correction.

This requires an explicit human-override model before code modification. Do not solve by re-enabling fuzzy family identity.

### OPEN-GAP-04 — `SpamFamilyReassigner` is alias-canonical

Reassign currently requires `alias_delivery` and `alias`, moves alias counters and delivery family, and its post-commit prediction refresh uses the legacy fuzzy matcher. It does not currently express `spam_message` as the primary message truth.

Migration must preserve:

- exemplar movement;
- optional alias family counters;
- canonical message family;
- alias delivery mirror when present;
- exclusions;
- family confirmed counts/lifecycle;
- prediction clearing/rescore behavior;
- exact identity semantics.

### OPEN-GAP-05 — legacy DAO/query surfaces remain

Some `DaoSpamFamily` / compatibility paths still operate on alias delivery. Each must be classified as either legitimate alias mirror/diagnostic or a consumer that must migrate to message truth.

### OPEN-GAP-06 — artifact checksum manifest path

The Android artifact currently writes a CI-internal path into `SHA256SUMS.txt`; after unzip at artifact root, direct `sha256sum -c SHA256SUMS.txt` cannot resolve that path. APK digest is correct, but artifact self-verification UX should be corrected in a separate scoped ChangeSet.

---

## 13. Change discipline

Before any OPEN-GAP is changed:

1. identify the human/domain meaning;
2. identify all current state owners;
3. map producers, consumers and side effects;
4. read the phone baseline/current history for why the path exists;
5. state the target semantic transition here or in Decision Ledger;
6. define the regression matrix first;
7. create a safety/ChangeSet branch;
8. change one ownership boundary at a time;
9. run semantic contracts + Android/Room/migration gate when relevant;
10. compare baseline→candidate;
11. verify the real phone workflow before declaring the migration complete.

If any step is unknown, the next action is archaeology/modeling, not patching.

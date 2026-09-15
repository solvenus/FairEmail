# Spam Control — Living Semantic System Model

**Status:** Living architecture authority  
**Method:** Semantic Architecture Engineering / Concept-Driven Software Engineering  
**Operational branch:** `feature/spam-control-p0`  
**Active ChangeSet:** `changeset/spam-control-family-correction-semantics`  
**ChangeSet base:** `c2c78a5dced58ae2d72a3383e080ded0d5bbdf55`  
**Runtime retirement commit:** `fcd3fce536fe770c084922d7187b58a16fb18555`  
**Known phone baseline:** `safety/spam-control-phone-checkpoint-6e7b6a52`

This document lives beside the code and is part of programming. A ChangeSet that changes the semantics below must update this model, the Decision Ledger, the affected regression contracts, and runtime observability in the same unit of work.

---

## 1. Capability model

Spam Control is a human-controlled intelligence layer over FairEmail. It must let one person:

1. review retained mail as Spam / Ikke spam without requiring alias metadata;
2. learn deterministic Spam Families from explicit human truth;
3. propagate an exact-family Spam decision to retained UNKNOWN twins without overwriting explicit HAM;
4. maintain optional alias intelligence when `Envelope-To` exists;
5. distinguish Spam classification from alias compromise;
6. distinguish local alias lifecycle from physical SMTP rejection state;
7. inspect, undo and reset learned state without losing raw mail/message visibility;
8. explain historical scans, classification, alias decisions and cPanel operations through persistent observability;
9. keep fuzzy/template similarity in Spam Network rather than family identity;
10. preserve FairEmail reply-from-alias behavior while Spam Control evolves.

The human is authoritative for explicit Spam / Ikke spam. Family organization is deterministic from the selected family identity definition, not a second manual classifier.

---

## 2. Domain model and authority

### 2.1 FairEmail Mail Message

FairEmail `message.id` inside its account is the raw mail object. It owns mail content and folder membership in FairEmail's main DB. Spam Control does not duplicate mail bodies or raw EML.

### 2.2 Spam Message

Implementation: `EntitySpamMessage` / `spam_message`.

Identity: `(account_uuid, message_id)`.

Canonical owner of per-message Spam Control truth:

- observation context: received time, folder type, normalized delivered-to when known;
- label: UNKNOWN / HAM / SPAM;
- confirmed `family_id` for SPAM;
- derived exact `predicted_family_id`, score and assessed time.

A message is valid Spam Control state even when no alias row exists.

### 2.3 Alias and Alias Delivery

`EntityAlias` / `alias` owns persistent alias inventory, service/domain knowledge, sender-domain evidence, counters, lifecycle, replacement metadata and SMTP rejection lifecycle.

`EntityAliasDelivery` / `alias_delivery` is optional per-message alias enrichment/idempotence/history when an envelope alias is known. Its label/family fields are compatibility/enrichment mirrors, not canonical message truth.

### 2.4 Spam Family

Production family identity is exactly:

`normalize(sender display name) + normalize(subject)`

Normalization: Unicode NFKC, NBSP→space, lowercase, whitespace collapse, trim.

Not family identity:

- sender email address;
- delivery alias;
- body/HTML/template similarity;
- link similarity;
- rotating product/body details.

### 2.5 Exact Family Identity Mapping

Implementation: `SpamFamilyIdentity` + persistent `family_identity:<account>:<identity-key>` meta mapping.

A complete canonical identity maps to one family. If sender-name or subject is missing, production Spam learning uses a deterministic isolated per-message key. Missing identity never reactivates fuzzy family joining.

### 2.6 Legacy Family Exclusion

`spam_family_exclusion` remains physically present during this ChangeSet for backward-compatible snapshot/undo/rollback readability.

It is **legacy inert recovery state**. Production exact `matchIdentity()` does not consult it, and production has no writer for new per-message family exclusions.

Schema/table removal is a separate migration ChangeSet.

### 2.7 Family Exemplar and Spam Network

Family exemplars are bounded fingerprint evidence. They may support diagnostics, historical compatibility and `SpamNetworkAnalyzer`.

Spam Network may relate multiple exact families by template/link/infrastructure/fingerprint similarity. It never defines or merges family identity.

### 2.8 Server Route State

Physical SMTP truth comes from cPanel/UAPI read-back through `CpanelAliasActuator` and transaction helpers. Local requested state is not verified server state.

---

## 3. State ownership map

| State / truth | Canonical owner | Mirror / derived view | Forbidden substitute |
|---|---|---|---|
| Message Spam/HAM/UNKNOWN | `spam_message.label` | `alias_delivery.label` when alias exists | folder placement |
| Confirmed family | `spam_message.family_id` + exact identity/family state | alias delivery + alias family counters | fuzzy best-match |
| Exact prediction | `spam_message.predicted_family_id` | alias delivery prediction mirror | legacy fuzzy score |
| Spam Control message visibility | `spam_message` | review/UI projections | alias existence |
| Envelope alias observation | `alias` + `alias_delivery` | `spam_message.delivered_to` context | sender From-address |
| Alias compromise lifecycle | `alias.state` | dashboard buckets | Spam label alone |
| SMTP rejection lifecycle | verified server read-back + `alias.smtp_reject_*` | UI/readiness | local COMPROMISED state |
| Family identity | canonical sender-name + subject mapping | family descriptor | body/template/link fuzziness |
| Legacy exclusion rows | recovery compatibility only | snapshots | exact-family authority |
| Reply desired From alias | original envelope/recipient evidence resolved to `ref.deliveredto` | Alias Registry capability validation | sender From-address |

**Ownership rule:** a table/cache/mirror does not gain authority because it exists. Authority changes require this model + Decision Ledger + migration + regression evidence.

---

## 4. Producer / consumer graph

### `spam_message`

**Producers:** historical Inbox/Junk scan; live observation; explicit Spam/HAM; exact prediction/rescore; reset/undo restore.

**Consumers:** global review queue/count; exact bulk; family inspection/stats; rescorer; snapshots; scan diagnostics.

When alias enrichment exists, message learning must preserve alias counters, delivery mirror, traffic assessment and compromise-policy side effects.

### `alias` / `alias_delivery`

**Producers:** envelope observation; historical enrichment; Spam/HAM mirror; traffic analysis; human alias lifecycle; cPanel operations.

**Consumers:** alias dashboard; compromise workflow; reply-capability validation; cPanel readiness/burn/restore; optional suspicious-traffic review admission.

**Invariant:** failure to create an alias row never blocks canonical message classification/review/family/reset/undo.

### Exact family mapping

**Producers:** first explicit SPAM for a complete identity; exact identity-level assignment with proven exact provenance; undo restore.

**Consumers:** exact `matchIdentity`; exact bulk; optional exact auto-label; review provenance; family administration.

A one-message UI action is not permission to globally rebind an arbitrary canonical identity.

### cPanel server state

**Producer:** remote server.  
**Consumers:** read-back verification, local verified SMTP state, rollback planning.

### Reply alias

**Producer chain:** original envelope headers / original recipients / retained delivered-to evidence → resolved `ref.deliveredto` → per-message re-observation/alias synchronization.

**Consumer chain:** `SpamIntelligence.resolveReplyExtra(context, selected, ref.deliveredto)` → `draft.extra` → FairEmail sender-extra From address.

Spam classification/family state is not reply-alias authority.

---

## 5. Behavioral model

### 5.1 Message learning

```text
RETAINED MAIL
    ↓ observe/index
spam_message UNKNOWN
    ├─ human Ikke spam ─────────────→ HAM
    ├─ human Spam ──────────────────→ SPAM + exact/isolated family
    └─ exact prediction ────────────→ UNKNOWN + predicted family
                                         └─ optional exact auto-label → SPAM
```

Folder location never performs UNKNOWN→HAM or UNKNOWN→SPAM by itself.

### 5.2 Review admission

An indexed message becomes review work when policy permits and at least one admission reason exists:

- Junk placement; or
- revalidated exact family prediction; or
- optional alias enrichment reports suspicious traffic.

`Envelope-To` is never required. Plain UNKNOWN Inbox with no other evidence remains indexed but is not automatically review work.

### 5.3 Explicit Spam

1. ensure canonical message row;
2. derive canonical family identity;
3. map/create exact family, or isolated family if identity incomplete;
4. set canonical SPAM + family;
5. mirror to alias state when present;
6. reconcile family lifecycle;
7. evaluate alias compromise from full evidence set;
8. refresh derived assessment/prediction/rescore;
9. exact bulk may propagate only to retained UNKNOWN messages whose canonical identity key equals the seed identity.

### 5.4 Explicit HAM

1. set canonical HAM and clear confirmed family;
2. remove/reconcile prior SPAM exemplar/family state;
3. mirror HAM to alias state when present;
4. preserve HAM against exact bulk/rescore;
5. refresh derived state.

### 5.5 Family administration

Family membership is automatic from canonical identity. Administrative operations may rename, activate/deactivate, exact-rescan and inspect family messages.

The retired fuzzy-lab operations `Samme spam` / `Annen spam`, `confirmSpam`, `markOtherSpam`, message-level family exclusion and `SpamFamilyReassigner` are not production concepts.

### 5.6 Alias compromise and SMTP burn

SPAM and COMPROMISED are different truths. Compromise policy consumes Spam plus traffic/service/trusted-domain/sender/HAM context and yields KEEP / REVIEW / COMPROMISE.

COMPROMISED and verified SMTP hard rejection are also different truths. Burn/restore is preflight → mutation → read-back → verified local state. Ordinary email replies/bounces are not server hard rejection.

### 5.7 Reset and Undo

Reset erases learned/derived Spam Control state while preserving FairEmail mail, canonical message visibility/raw observation, passive inventory required by contract, and physical server truth.

Snapshots cover every mutable owner in the operation: messages, optional alias/delivery state, families/exemplars, legacy exclusion rows for compatibility, exact identity meta, compromise meta and affected derived state.

Undo snapshots **before** mutation and marks history undone only after restore succeeds. Reset itself is undoable.

---

## 6. Invariant registry

### MESSAGE

- **INV-M01** Retained Spam Control messages can exist without `Envelope-To`.
- **INV-M02** Junk is review evidence, not automatic SPAM.
- **INV-M03** Inbox is context, not automatic HAM.
- **INV-M04** Explicit HAM is never overwritten by exact bulk/rescore.
- **INV-M05** Canonical learning succeeds without alias enrichment.

### FAMILY

- **INV-F01** Family identity = normalized sender display name + normalized subject.
- **INV-F02** Same complete canonical identity → same family. No per-message family exception.
- **INV-F03** Same name + different subject → different identity/family.
- **INV-F04** Different name + same subject → different identity/family.
- **INV-F05** Incomplete identity → isolated per-message family, never fuzzy join.
- **INV-F06** Fuzzy similarity belongs to Spam Network/diagnostics, never family authority.
- **INV-F07** Disabled family is not an automatic exact match.
- **INV-F08** Legacy exclusion rows cannot suppress exact `matchIdentity()`.
- **INV-F09** One-message UI actions cannot silently globally rebind an arbitrary exact identity.

### ALIAS / SERVER

- **INV-A01** Alias enrichment is additive, never an admission gate for message truth.
- **INV-A02** Existing alias side effects/counters survive canonical message learning.
- **INV-A03** Spam does not imply compromised alias.
- **INV-A04** COMPROMISED does not imply verified SMTP hard rejection.
- **INV-A05** Server mutation is not verified until read-back proves remote state.

### RECOVERY

- **INV-R01** Every mutating human/automation action snapshots every state owner it can mutate.
- **INV-R02** Reset preserves canonical message visibility and raw mail.
- **INV-R03** Undo restores before history is marked undone.
- **INV-R04** Schema compatibility state is not removed inside an unrelated semantic retirement.

### OBSERVABILITY

- **INV-O01** Historical scan reports raw examined, canonical indexed/new, alias enriched/new, folders and missing Envelope-To/folder.
- **INV-O02** cPanel logs sanitized request/response shape before parser/shape failure.
- **INV-O03** Credentials, Authorization values, passwords and mail bodies never enter Spam Control diagnostics.

### REPLY ALIAS

- **INV-P01** Original envelope evidence outranks later routing `Delivered-To` when recovering the reply alias.
- **INV-P02** The resolved original delivery alias becomes `ref.deliveredto` and is re-observed before reply-extra resolution.
- **INV-P03** `resolveReplyExtra` uses `ref.deliveredto`, and the resolved extra is written to the draft.
- **INV-P04** Spam Control family/classification refactors may not redefine reply-alias authority.

---

## 7. Forbidden states

The following are invalid even if Java/Room can represent them:

1. a retained Junk message is invisible solely because no alias row exists;
2. fuzzy/template similarity is presented as exact-family provenance;
3. explicit HAM is changed to SPAM by bulk/rescore without a new explicit human action;
4. two complete equal canonical family identities are split by a per-message legacy exclusion;
5. a one-message action silently rebinds future/twin messages to an arbitrary family;
6. local COMPROMISED is presented as verified SMTP hard rejection;
7. cPanel mutation is marked verified without read-back;
8. reset deletes raw mail or canonical message index;
9. one user action creates multiple undo points through duplicate execution;
10. a UI work bucket has no exit action unless deliberately read-only and labeled;
11. a Spam Control refactor breaks reply-from-alias while its own tests remain green.

---

## 8. Recovery semantics

| Operation | Snapshot scope | Must survive | Refresh after restore |
|---|---|---|---|
| Spam / Ikke spam | message + affected family/meta + optional alias/delivery | raw message observation | family prediction/rescore |
| Exact bulk Spam | full affected learned account state | raw canonical message index | active-family rescore |
| Alias lifecycle action | every touched alias/meta owner | mail; remote server truth unless operation explicitly mutates it | dashboard/assessment |
| Reset learning | global learned state | raw mail, canonical message visibility, passive inventory/server truth per contract | active-family rescore |
| cPanel burn/restore | local route snapshot + remote read-back | recoverable pre-operation route when safe path promised | verified SMTP state |

No ChangeSet may delete recovery state before a replacement migration proves rollback and snapshot compatibility.

---

## 9. Verification architecture

Semantic contracts currently protect:

- bootstrap / canonical message authority;
- canonical message + alias side effects;
- reset/undo state ownership;
- scan/Terminal/cPanel observability;
- exact-family legacy-retirement + exact bulk/auto-confirm provenance;
- reply-from-alias authority chain;
- meta-coverage proving each contract workflow triggers on every Java consumer it reads.

Additional gates:

- `Spam Family Core` pure-Java labs for family identity/prediction/scoring/alias/cPanel invariants;
- `Spam Intelligence Android Compile` for Java+Room compile, migration verification, APK assembly, pinned signer and checksummed artifact;
- Spam Control entrypoint audit proving `ActivitySpamControl` is the sole runtime entrypoint.

A green test on a different commit is not evidence for the candidate. Release evidence is tied to one exact candidate SHA.

---

## 10. Resolved archaeology — exact family correction retirement

The old `ActivitySpamFamilyLab` was introduced as a `githubDebug` review surface and later retained as a non-exported fallback only while the new dashboard was exercised. Caller/reachability archaeology proved:

- no app code launched it;
- `confirmSpam`, `markOtherSpam`, `SpamFamilyHumanActions` and `SpamFamilyReassigner` were confined to that correction island;
- `Samme spam / Annen spam` expressed the prior fuzzy-family classifier model;
- the main dashboard already contained the legitimate migrated capabilities: global Spam/HAM review, family list/rename/activation, exact rescan, read-only family inspection, undo/reset/details and separate Spam Network analysis.

Decision:

- the fuzzy correction island is retired rather than adapted;
- exact identity is definitional;
- legacy exclusion storage remains inert for rollback/snapshot compatibility;
- no new per-message exact-family override is introduced.

Runtime implementation landed in `fcd3fce536fe770c084922d7187b58a16fb18555`.

---

## 11. Remaining open migration gaps

### OPEN-GAP-01 — remaining legacy DAO/query surfaces

Some `DaoSpamFamily` / compatibility queries still operate on alias-delivery-era structures. Each remaining path must be classified as legitimate alias mirror/diagnostic/recovery state or migrated to canonical message truth. Do not mass-replace DAO calls.

### OPEN-GAP-02 — physical removal of legacy exclusion schema

`spam_family_exclusion` is now inert for production exact matching but remains part of DB/snapshot compatibility. Removing it requires a dedicated Room/schema/snapshot migration with rollback evidence.

### OPEN-GAP-03 — artifact checksum manifest path

The Android artifact currently writes a CI-internal path into `SHA256SUMS.txt`; after extracting the artifact at its root, direct `sha256sum -c SHA256SUMS.txt` may not resolve that original CI path. APK digest/signing are independently verified; artifact self-verification UX belongs in a separate scoped ChangeSet.

---

## 12. Change discipline

Before changing an open gap or ownership boundary:

1. identify the human/domain meaning;
2. identify all current state owners;
3. map producers, consumers and side effects;
4. inspect current code + relevant history/baselines for why the path exists;
5. record the decision in this model / Decision Ledger;
6. define the regression matrix first;
7. work on a scoped ChangeSet branch with explicit allowed/forbidden files;
8. migrate additively before substitution/destruction;
9. preserve recovery/rollback state until replacement equivalence is proven;
10. run semantic contracts + relevant core/Android/Room gates on the exact candidate SHA;
11. compare base→candidate and reject out-of-scope drift;
12. verify the real phone workflow before declaring user-facing completion.

If a semantic fact is unknown, the next action is archaeology/modeling, not patching.

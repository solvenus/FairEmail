# Spam Families / Alias Registry — progress log

Last updated: 2026-09-15
Branch: `feature/spam-families`

## Project goal

Extend FairEmail with a local-first, explainable spam-family engine that recognizes mutating campaigns across changes in sender, domain, product name, CTA, copy, tracking IDs, layout and images. In parallel, make the original SMTP recipient alias a first-class concept throughout the app.

## User mail routing model

- Server catchall ultimately delivers to `sd_1@solvenus.no`.
- Services use aliases like `sd_(service)@solvenus.no`.
- Spam is concentrated on a small subset of aliases.
- Compromised aliases can be rotated at the service and disabled in cPanel, cutting the spam line at the source.

## Architectural decision: Alias Registry is general infrastructure

Do not hide aliases inside the spam classifier. FairEmail should register every original-recipient alias observed in synchronized mail, regardless of folder.

Planned alias profile fields include:

- original recipient alias
- first/last seen
- total traffic count
- spam/ham counts
- related spam-family IDs
- first/last abuse
- later user status such as clean, suspicious, compromised, replaced

This registry is intended to support future alias operations beyond spam.

## Original-recipient extraction

`Delivered-To: sd_1@solvenus.no` may only represent the catchall endpoint. Preferred extraction order is currently:

1. `Envelope-To`
2. relevant `Received: ... for <alias>`
3. `X-Original-To`
4. `Delivered-To`
5. `To`

This must be validated against real EML samples before integration.

## Real spam corpus

The user supplied a ZIP of real spam EMLs for local replay/evaluation. Raw EML data must never be committed to GitHub. Only sanitized fixtures or synthetic derivatives may enter the repository.

Current observation from the corpus: 16 EML files, one apparent duplicate, leaving 15 unique messages; 13 of 15 unique messages are concentrated on two original recipient aliases. Visible `To:` can be unrelated while envelope/Received routing identifies the real user alias.

## Existing FairEmail code mapped

- Existing classifier: `app/src/main/java/eu/faircode/email/MessageClassifier.java`
- Main method: `MessageClassifier.classify(EntityMessage message, EntityFolder folder, boolean added, Context context)`
- Existing classifier uses addresses, subject and full message text.
- Call sites were found in `Core.java`.
- Raw headers are available through `message.headers` and are populated through existing message processing.
- Existing rule/expression code already parses raw headers using `InternetHeaders`.
- Jsoup already exists in the codebase.
- Debug build already uses `applicationIdSuffix '.debug'`, allowing side-by-side installation with the official app.

## Current branch state

Base commit when branch was created:

`baaaf0d9b72b896b0aff519f1f6a70d01f56f6c3`

First implementation commit:

`2bc6a592ab10aa819f92b9d467e12d58beeb03e0`

Added:

`app/src/main/java/eu/faircode/email/SpamFamilyEngine.java`

Important: the branch is not yet compile-verified. `SpamFamilyEngine` currently references `SpamFamilyFingerprint`, which has not yet been added. Do not claim build success until that dependency and tests exist.

## Spam-family scoring direction

A single linear score is intentionally avoided. The prototype uses multiple recognition routes:

- balanced
- template / structure-heavy
- content-heavy
- infrastructure-heavy

This is intended to let a strong invariant survive when other parts of a spam campaign mutate. Thresholds are experimental and must be calibrated on replay data.

## Next steps

1. Enumerate original-recipient header variants in the real EML corpus.
2. Implement robust `OriginalRecipientExtractor`.
3. Implement `SpamFamilyFingerprint`.
4. Add sanitized/adversarial fixtures and a local replay harness.
5. Compile-test the pure Java core before Android integration.
6. Perform chronological replay: learn older spam, predict newer unseen spam.
7. Add hard-negative legitimate mail so false-positive rate can be measured.
8. Design persistent Alias Registry entities/DAO/migrations after inspecting current Room schema.
9. Integrate observer-only collection/matching into the message pipeline.
10. Add a debug/lab UI showing clusters, aliases and explainable match evidence.
11. Only after replay quality is strong, enable high-confidence automatic move-to-spam.

## Engineering rules

- Keep `master` and upstream source pristine.
- Preserve raw mail data locally; never commit it.
- Prefer reversible observer mode before automated actions.
- Test adversarial drift: product names, senders, domains, CTAs, text order, tracking parameters, DOM changes and images.
- Measure precision and recall separately.
- Do not declare success from superficial green tests.
- Keep progress checkpointed in both repo and Library.

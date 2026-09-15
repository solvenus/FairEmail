#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


activity = Path("app/src/main/java/eu/faircode/email/ActivitySpamControl.java")
replace_once(
    activity,
    '''import java.util.concurrent.ExecutorService;\nimport java.util.concurrent.atomic.AtomicLong;''',
    '''import java.util.concurrent.ExecutorService;\nimport java.util.concurrent.atomic.AtomicBoolean;\nimport java.util.concurrent.atomic.AtomicLong;''',
    "review single-flight import",
)
replace_once(
    activity,
    '''    private final AtomicLong reviewGeneration = new AtomicLong();\n    private final Map<Section, Button> navButtons = new EnumMap<>(Section.class);''',
    '''    private final AtomicLong reviewGeneration = new AtomicLong();\n    private final AtomicBoolean reviewActionRunning = new AtomicBoolean(false);\n    private final Map<Section, Button> navButtons = new EnumMap<>(Section.class);''',
    "review single-flight field",
)
replace_once(
    activity,
    '''        Button spam = primaryButton("Spam");\n        spam.setOnClickListener(v -> runReviewAction(candidate, true));\n        actions.addView(spam, weightedButton());\n        Button ham = primaryButton("Ikke spam");\n        ham.setOnClickListener(v -> runReviewAction(candidate, false));\n        actions.addView(ham, weightedButton());\n        content.addView(actions, matchWrap());\n\n        Button skip = secondaryButton("Hopp over");\n        skip.setOnClickListener(v -> {\n            reviewIndex++;\n            if (reviewIndex >= reviewQueue.size())\n                reviewIndex = 0;\n            renderCurrent();\n        });''',
    '''        boolean actionIdle = !reviewActionRunning.get();\n        Button spam = primaryButton("Spam");\n        spam.setEnabled(actionIdle && candidate.label != EntityAliasDelivery.LABEL_SPAM);\n        spam.setOnClickListener(v -> runReviewAction(candidate, true));\n        actions.addView(spam, weightedButton());\n        Button ham = primaryButton("Ikke spam");\n        ham.setEnabled(actionIdle && candidate.label != EntityAliasDelivery.LABEL_HAM);\n        ham.setOnClickListener(v -> runReviewAction(candidate, false));\n        actions.addView(ham, weightedButton());\n        content.addView(actions, matchWrap());\n\n        Button skip = secondaryButton("Hopp over");\n        skip.setEnabled(actionIdle);\n        skip.setOnClickListener(v -> {\n            reviewIndex++;\n            if (reviewIndex >= reviewQueue.size())\n                reviewIndex = 0;\n            renderCurrent();\n        });''',
    "disable duplicate review actions",
)
replace_once(
    activity,
    '''    private void runReviewAction(SpamFamilyLabRepository.Candidate candidate, boolean spam) {\n        EntityAccount account = selectedAccount;\n        if (account == null || account.uuid == null)\n            return;\n        tvStatus.setText(spam ? "Lagrer spam …" : "Lagrer ikke spam …");\n        long familyContext = candidate.predictedFamilyId == null ? 0L : candidate.predictedFamilyId;\n        executor.execute(() -> {\n            SpamFamilyLabRepository.ActionResult result = SpamUndoManager.runMessageAction(\n                    getApplicationContext(), account.uuid, familyContext, candidate.messageId,\n                    spam ? SpamUndoManager.ACTION_SPAM : SpamUndoManager.ACTION_NOT_SPAM,\n                    spam ? "Spam" : "Ikke spam",\n                    () -> spam\n                            ? SpamFamilyLabRepository.markSpam(getApplicationContext(), account.uuid, candidate.messageId)\n                            : SpamFamilyLabRepository.markLegitimate(getApplicationContext(), account.uuid, candidate.messageId));\n            runOnUiThread(() -> {\n                if (!isSelected(account) || isFinishing() || isDestroyed())\n                    return;\n                if (result == SpamFamilyLabRepository.ActionResult.APPLIED) {\n                    String text = spam ? "Lagret som spam." : "Lagret som ikke spam.";\n                    tvStatus.setText(text);\n                    Snackbar.make(svContent, text, Snackbar.LENGTH_LONG)\n                            .setAction("ANGRE", v -> undoLatest())\n                            .show();\n                    if (SpamControlPolicy.autoAdvance(this) &&\n                            reviewIndex < reviewQueue.size())\n                        reviewQueue.remove(reviewIndex);\n                    reviewCount = reviewQueue.size();\n                    if (reviewIndex >= reviewQueue.size())\n                        reviewIndex = 0;\n                    renderCurrent();\n                    loadReviewQueue(account, false);\n                } else {\n                    tvStatus.setText("Kunne ikke lagre endringen.");\n                    loadReviewQueue(account, false);\n                }\n            });\n        });\n    }''',
    '''    private void runReviewAction(SpamFamilyLabRepository.Candidate candidate, boolean spam) {\n        EntityAccount account = selectedAccount;\n        if (account == null || account.uuid == null)\n            return;\n        if ((spam && candidate.label == EntityAliasDelivery.LABEL_SPAM) ||\n                (!spam && candidate.label == EntityAliasDelivery.LABEL_HAM))\n            return;\n        if (!reviewActionRunning.compareAndSet(false, true))\n            return;\n\n        tvStatus.setText(spam ? "Lagrer spam …" : "Lagrer ikke spam …");\n        if (section == Section.REVIEW)\n            renderCurrent();\n        long familyContext = candidate.predictedFamilyId == null ? 0L : candidate.predictedFamilyId;\n        executor.execute(() -> {\n            SpamFamilyLabRepository.ActionResult result = SpamUndoManager.runMessageAction(\n                    getApplicationContext(), account.uuid, familyContext, candidate.messageId,\n                    spam ? SpamUndoManager.ACTION_SPAM : SpamUndoManager.ACTION_NOT_SPAM,\n                    spam ? "Spam" : "Ikke spam",\n                    () -> spam\n                            ? SpamFamilyLabRepository.markSpam(getApplicationContext(), account.uuid, candidate.messageId)\n                            : SpamFamilyLabRepository.markLegitimate(getApplicationContext(), account.uuid, candidate.messageId));\n            runOnUiThread(() -> {\n                reviewActionRunning.set(false);\n                if (isFinishing() || isDestroyed())\n                    return;\n                if (!isSelected(account)) {\n                    if (section == Section.REVIEW)\n                        renderCurrent();\n                    return;\n                }\n                if (result == SpamFamilyLabRepository.ActionResult.APPLIED) {\n                    String text = spam ? "Lagret som spam." : "Lagret som ikke spam.";\n                    tvStatus.setText(text);\n                    Snackbar.make(svContent, text, Snackbar.LENGTH_LONG)\n                            .setAction("ANGRE", v -> undoLatest())\n                            .show();\n                    if (SpamControlPolicy.autoAdvance(this) &&\n                            reviewIndex < reviewQueue.size())\n                        reviewQueue.remove(reviewIndex);\n                    reviewCount = reviewQueue.size();\n                    if (reviewIndex >= reviewQueue.size())\n                        reviewIndex = 0;\n                    renderCurrent();\n                    loadReviewQueue(account, false);\n                } else {\n                    tvStatus.setText("Kunne ikke lagre endringen.");\n                    renderCurrent();\n                    loadReviewQueue(account, false);\n                }\n            });\n        });\n    }''',
    "single-flight review action",
)

identity = Path("app/src/main/java/eu/faircode/email/SpamFamilyIdentity.java")
replace_once(
    identity,
    '''    public static String metaKey(String accountUuid, String identityKey) {''',
    '''    /**\n     * Deterministic per-message key for spam that lacks the complete\n     * sender-name + subject pair. It deliberately cannot match another message.\n     */\n    public static String isolatedMessageKey(long messageId) {\n        if (messageId <= 0)\n            return null;\n        return VERSION + ":isolated-message:" + Long.toUnsignedString(messageId);\n    }\n\n    public static String metaKey(String accountUuid, String identityKey) {''',
    "isolated exact identity key",
)

intelligence = Path("app/src/main/java/eu/faircode/email/SpamIntelligence.java")
replace_once(
    intelligence,
    '''                } else if (fingerprint != null) {\n                    // Conservative fallback only when a message genuinely lacks\n                    // the sender-name/subject pair needed for exact identity.\n                    familyLearn = SpamFamilyStore.learnSpam(\n                            context, account.uuid, message.id, fingerprint);\n                    familyId = familyLearn.familyId;\n                }''',
    '''                } else if (fingerprint != null) {\n                    // Missing identity data must never re-enable fuzzy family joining.\n                    // Keep the message in a deterministic one-message family until a\n                    // complete sender-name + subject identity becomes available.\n                    familyLearn = SpamFamilyStore.learnSpamExact(\n                            context, account.uuid, message.id, fingerprint,\n                            SpamFamilyIdentity.isolatedMessageKey(message.id));\n                    familyId = familyLearn.familyId;\n                }''',
    "remove production fuzzy fallback",
)

repository = Path("app/src/main/java/eu/faircode/email/SpamFamilyLabRepository.java")
replace_once(
    repository,
    '''            if (message == null)\n                continue;\n\n            EntityAlias alias = null;\n            try {\n                alias = aliasDao.getAlias(account, delivery.address);\n            } catch (Throwable ex) {\n                Log.w(ex);\n            }\n            Long contextFamily = delivery.predicted_family_id != null\n                    ? delivery.predicted_family_id : delivery.family_id;\n            result.add(Candidate.from(delivery, message, alias, contextFamily));''',
    '''            if (message == null)\n                continue;\n\n            boolean suspiciousAlias = "SUSPICIOUS".equals(delivery.traffic_verdict);\n            boolean exactPrediction = false;\n            if (delivery.predicted_family_id != null) {\n                if (SpamControlPolicy.exactFamilyDetection(context)) {\n                    SpamFamilyIdentity.Identity identity =\n                            SpamFamilyMessageAdapter.identityFromMessage(message);\n                    if (identity != null) {\n                        SpamFamilyStore.Match exact = SpamFamilyStore.matchIdentity(\n                                context, account, delivery.message_id, identity.key);\n                        exactPrediction = exact.familyId != null &&\n                                exact.familyId.longValue() == delivery.predicted_family_id.longValue();\n                    }\n                }\n\n                if (!exactPrediction) {\n                    // Legacy fuzzy predictions are derived cache, never human truth.\n                    // Clear them eagerly so even a historical 100% fuzzy score cannot\n                    // masquerade as an exact sender-name + subject match.\n                    intelligence.family().clearFamilyMatch(\n                            account, delivery.message_id, System.currentTimeMillis());\n                    delivery = aliasDao.getDelivery(account, delivery.message_id);\n                    if (delivery == null)\n                        continue;\n                }\n            }\n\n            if (!suspiciousAlias && !exactPrediction)\n                continue;\n\n            EntityAlias alias = null;\n            try {\n                alias = aliasDao.getAlias(account, delivery.address);\n            } catch (Throwable ex) {\n                Log.w(ex);\n            }\n            Long contextFamily = exactPrediction\n                    ? delivery.predicted_family_id : delivery.family_id;\n            result.add(Candidate.from(delivery, message, alias, contextFamily));''',
    "validate exact prediction provenance",
)

lab = Path("tools/spam-family-lab/SpamFamilyIdentityLab.java")
replace_once(
    lab,
    '''        require(SpamFamilyIdentity.fromRaw("Sender", "   ") == null,\n                "blank subject cannot define exact family");\n\n        String key = SpamFamilyIdentity.metaKey("account-1", base.key);''',
    '''        require(SpamFamilyIdentity.fromRaw("Sender", "   ") == null,\n                "blank subject cannot define exact family");\n\n        String isolatedA = SpamFamilyIdentity.isolatedMessageKey(101L);\n        String isolatedB = SpamFamilyIdentity.isolatedMessageKey(102L);\n        require(isolatedA != null && isolatedB != null && !isolatedA.equals(isolatedB),\n                "incomplete identities must be isolated per message");\n        require(!isolatedA.equals(base.key),\n                "isolated fallback key must never collide with normal exact identity");\n        require(SpamFamilyIdentity.isolatedMessageKey(0L) == null,\n                "invalid message id cannot define isolated family");\n\n        String key = SpamFamilyIdentity.metaKey("account-1", base.key);''',
    "isolated identity regression",
)

print("PASS: Spam Control runtime hardening applied")

#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path, old, new):
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly 1 match, got {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"patched {path}")


# ---------- FairEmail message DAO: paged historical Inbox/Junk scan ----------
replace_once(
    "app/src/main/java/eu/faircode/email/DaoMessage.java",
    '''    @Query("SELECT *" +\n            " FROM message" +\n            " WHERE id = :id")\n    EntityMessage getMessage(long id);\n\n''',
    '''    @Query("SELECT *" +\n            " FROM message" +\n            " WHERE id = :id")\n    EntityMessage getMessage(long id);\n\n    @Query("SELECT message.* FROM message" +\n            " JOIN folder ON folder.id = message.folder" +\n            " WHERE message.account = :account" +\n            " AND folder.type IN (:folderTypes)" +\n            " AND message.id > :afterMessageId" +\n            " AND NOT message.ui_hide" +\n            " ORDER BY message.id" +\n            " LIMIT :limit")\n    List<EntityMessage> getSpamControlHistoricalPage(\n            long account, List<String> folderTypes, long afterMessageId, int limit);\n\n''')

# ---------- Review queue: all unknown historical Junk is reviewable ----------
replace_once(
    "app/src/main/java/eu/faircode/email/DaoAlias.java",
    '''            " AND (traffic_verdict = 'SUSPICIOUS'" +\n            "   OR (predicted_family_id IS NOT NULL AND family_score >= 0.999999))" +\n''',
    '''            " AND (traffic_verdict = 'SUSPICIOUS'" +\n            "   OR folder_type = '" + EntityFolder.JUNK + "'" +\n            "   OR (predicted_family_id IS NOT NULL AND family_score >= 0.999999))" +\n''')

replace_once(
    "app/src/main/java/eu/faircode/email/DaoAlias.java",
    '''            " AND (traffic_verdict = 'SUSPICIOUS'" +\n            "   OR (predicted_family_id IS NOT NULL AND family_score >= 0.999999))")\n    int countReviewQueue(String accountUuid, boolean includeReviewed);\n''',
    '''            " AND (traffic_verdict = 'SUSPICIOUS'" +\n            "   OR folder_type = '" + EntityFolder.JUNK + "'" +\n            "   OR (predicted_family_id IS NOT NULL AND family_score >= 0.999999))")\n    int countReviewQueue(String accountUuid, boolean includeReviewed);\n''')

# Repository has a provenance guard after the SQL query. Junk is an explicit
# review source, so it must survive even when it has no exact prediction yet.
replace_once(
    "app/src/main/java/eu/faircode/email/SpamFamilyLabRepository.java",
    '''            boolean suspiciousAlias = "SUSPICIOUS".equals(delivery.traffic_verdict);\n            boolean exactPrediction = false;\n''',
    '''            boolean suspiciousAlias = "SUSPICIOUS".equals(delivery.traffic_verdict);\n            boolean historicalJunk = EntityFolder.JUNK.equals(delivery.folder_type);\n            boolean exactPrediction = false;\n''')

replace_once(
    "app/src/main/java/eu/faircode/email/SpamFamilyLabRepository.java",
    '''            if (!suspiciousAlias && !exactPrediction)\n                continue;\n''',
    '''            if (!suspiciousAlias && !historicalJunk && !exactPrediction)\n                continue;\n''')

# ---------- Spam Control UI: visible SMTP lifecycle ----------
replace_once(
    "app/src/main/java/eu/faircode/email/ActivitySpamControl.java",
    '''        AliasBurnPolicy.Result readiness = AliasBurnReadiness.evaluate(alias);\n        body.addView(bodyText("SMTP: " + smtpState(alias) + " · " + readiness.verdict),\n                matchWrapWithMargin(0, 3, 0, 0));\n\n        LinearLayout actions = new LinearLayout(this);\n''',
    '''        AliasBurnPolicy.Result readiness = AliasBurnReadiness.evaluate(this, alias);\n        TextView lifecycle = bodyText("Neste: " + aliasLifecycleText(alias, readiness));\n        lifecycle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);\n        body.addView(lifecycle, matchWrapWithMargin(0, 4, 0, 0));\n        body.addView(bodyText("SMTP: " + smtpState(alias)),\n                matchWrapWithMargin(0, 2, 0, 0));\n\n        LinearLayout actions = new LinearLayout(this);\n''')

replace_once(
    "app/src/main/java/eu/faircode/email/ActivitySpamControl.java",
    '''        if (readiness.burnAllowed && SpamControlPolicy.smtpBurnEnabled(this)) {\n            Button burn = secondaryButton("SMTP-død");\n            burn.setOnClickListener(v -> confirmBurn(alias));\n            actions.addView(burn, weightedButton());\n        } else if (alias.smtp_reject_state == EntityAlias.SMTP_REJECT_VERIFIED) {\n            Button restore = secondaryButton("Gjenopprett SMTP");\n            restore.setOnClickListener(v -> confirmRestore(alias));\n            actions.addView(restore, weightedButton());\n        }\n''',
    '''        if (alias.smtp_reject_state == EntityAlias.SMTP_REJECT_VERIFIED) {\n            Button restore = secondaryButton("Gjenopprett SMTP");\n            restore.setOnClickListener(v -> confirmRestore(alias));\n            actions.addView(restore, weightedButton());\n        } else if (readiness.verdict == AliasBurnPolicy.Verdict.REVIEW_COMPROMISE) {\n            Button review = secondaryButton("Vurder alias");\n            review.setOnClickListener(v -> showCompromiseReview(alias));\n            actions.addView(review, weightedButton());\n        } else if (readiness.verdict == AliasBurnPolicy.Verdict.COMPROMISED ||\n                readiness.verdict == AliasBurnPolicy.Verdict.ROTATE_FIRST) {\n            Button replacement = secondaryButton("Sett replacement");\n            replacement.setOnClickListener(v -> showAliasEditor(alias));\n            actions.addView(replacement, weightedButton());\n        } else if (readiness.verdict == AliasBurnPolicy.Verdict.VERIFY_REPLACEMENT) {\n            Button verify = secondaryButton("Skann Innboks");\n            verify.setOnClickListener(v -> runHistoricalScan(true, false));\n            actions.addView(verify, weightedButton());\n        } else if (readiness.burnAllowed) {\n            Button burn = secondaryButton(SpamControlPolicy.hasCpanelConfig(this)\n                    ? "SMTP-død" : "Konfigurer cPanel");\n            burn.setOnClickListener(v -> {\n                if (SpamControlPolicy.hasCpanelConfig(this))\n                    confirmBurn(alias);\n                else\n                    showCpanelDialog();\n            });\n            actions.addView(burn, weightedButton());\n        }\n''')

replace_once(
    "app/src/main/java/eu/faircode/email/ActivitySpamControl.java",
    '''        AliasBurnPolicy.Result readiness = AliasBurnReadiness.evaluate(alias);\n        if (!readiness.burnAllowed) {\n''',
    '''        AliasBurnPolicy.Result readiness = AliasBurnReadiness.evaluate(this, alias);\n        if (!readiness.burnAllowed) {\n''')

# Alias editor should expose lifecycle state even if no burn button is available.
replace_once(
    "app/src/main/java/eu/faircode/email/ActivitySpamControl.java",
    '''        form.addView(replacement, matchWrap());\n        form.addView(note, matchWrap());\n\n        Spinner state = new Spinner(this);\n''',
    '''        form.addView(replacement, matchWrap());\n        form.addView(note, matchWrap());\n\n        AliasBurnPolicy.Result readiness = AliasBurnReadiness.evaluate(this, alias);\n        TextView lifecycle = bodyText("SMTP-livssyklus: " + aliasLifecycleText(alias, readiness));\n        lifecycle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);\n        lifecycle.setPadding(0, dp(8), 0, dp(6));\n        form.addView(lifecycle, matchWrap());\n\n        Spinner state = new Spinner(this);\n''')

# Historical scan controls in Settings.
replace_once(
    "app/src/main/java/eu/faircode/email/ActivitySpamControl.java",
    '''        CardView cpanel = card(10, 1);\n''',
    '''        CardView historical = card(10, 1);\n        LinearLayout hb = cardBody(14, 12);\n        hb.addView(valueText("Historisk skanning", 17f, true), matchWrap());\n        hb.addView(bodyText("Les eksisterende e-post inn i Spamkontroll. Spam-mappen blir en gjennomgangskilde; Innboks bygger alias- og avsenderhistorikk. Mappeplassering blir aldri automatisk spam/ikke-spam-sannhet."),\n                matchWrapWithMargin(0, 5, 0, 0));\n        Button scanBoth = primaryButton("Skann Spam + Innboks");\n        scanBoth.setOnClickListener(v -> runHistoricalScan(true, true));\n        hb.addView(scanBoth, matchWrapWithMargin(0, 8, 0, 0));\n        LinearLayout scanRow = new LinearLayout(this);\n        scanRow.setOrientation(LinearLayout.HORIZONTAL);\n        Button scanSpam = secondaryButton("Kun Spam");\n        scanSpam.setOnClickListener(v -> runHistoricalScan(false, true));\n        scanRow.addView(scanSpam, weightedButton());\n        Button scanInbox = secondaryButton("Kun Innboks");\n        scanInbox.setOnClickListener(v -> runHistoricalScan(true, false));\n        scanRow.addView(scanInbox, weightedButton());\n        hb.addView(scanRow, matchWrapWithMargin(0, 5, 0, 0));\n        historical.addView(hb, matchWrap());\n        llPage.addView(historical, matchWrapWithMargin(0, 0, 0, 12));\n\n        CardView cpanel = card(10, 1);\n''')

# Add lifecycle/review/scan helpers before actionHistoryCard.
replace_once(
    "app/src/main/java/eu/faircode/email/ActivitySpamControl.java",
    '''    private View actionHistoryCard(EntitySpamActionHistory action) {\n''',
    '''    private String aliasLifecycleText(EntityAlias alias, AliasBurnPolicy.Result readiness) {\n        if (alias == null || readiness == null)\n            return "ukjent";\n        switch (readiness.verdict) {\n            case HEALTHY:\n                return "Aliaset er aktivt. Ingen SMTP-handling nødvendig.";\n            case REVIEW_COMPROMISE:\n                return "Spam finnes, men alias-lekkasje er ikke avgjort.";\n            case COMPROMISED:\n                return "Kompromittert. Sett et replacement-alias.";\n            case ROTATE_FIRST:\n                return "Bytt alias hos tjenesten og registrer replacement her.";\n            case VERIFY_REPLACEMENT:\n                return "Replacement er satt. Venter på legitim/forventet mail til det nye aliaset.";\n            case READY_TO_BURN:\n                return SpamControlPolicy.hasCpanelConfig(this)\n                        ? "Replacement er verifisert. Klar for SMTP-død."\n                        : "Replacement er verifisert. Konfigurer cPanel for SMTP-død.";\n            case SERVER_PENDING:\n                return "Serveroperasjon pågår.";\n            case SMTP_DEAD:\n                return "SMTP hard reject er verifisert (550).";\n            case SERVER_FAILED:\n                return "Siste serveroperasjon feilet. Åpne aliaset for detaljer.";\n            default:\n                return readiness.verdict.toString();\n        }\n    }\n\n    private void showCompromiseReview(EntityAlias alias) {\n        if (alias == null)\n            return;\n        new AlertDialog.Builder(this)\n                .setTitle("Er aliaset kompromittert?")\n                .setMessage(alias.address + "\\n\\nSpam er bekreftet, men det er ikke nok alene til å konkludere med at aliasadressen har lekket.")\n                .setNegativeButton("Behold aktivt", (d, w) -> {\n                    AliasCompromiseReviewStore.markReviewedHealthy(getApplicationContext(), alias);\n                    tvStatus.setText("Aliaset beholdes aktivt. Ny spam-evidens kan åpne spørsmålet igjen.");\n                    renderCurrent();\n                })\n                .setPositiveButton("Marker kompromittert", (d, w) -> {\n                    executor.execute(() -> {\n                        DaoAlias dao = SpamIntelligenceDB.getInstance(getApplicationContext()).alias();\n                        int changed = dao.markCompromised(alias.account_uuid, alias.address);\n                        runOnUiThread(() -> {\n                            tvStatus.setText(changed > 0 ? "Alias markert kompromittert." : "Aliasstatus var allerede oppdatert.");\n                            renderCurrent();\n                        });\n                    });\n                })\n                .show();\n    }\n\n    private void runHistoricalScan(boolean inbox, boolean junk) {\n        EntityAccount account = selectedAccount;\n        if (account == null)\n            return;\n        tvStatus.setText("Skanner eksisterende " +\n                (inbox && junk ? "Spam + Innboks" : junk ? "Spam" : "Innboks") + " …");\n        executor.execute(() -> {\n            SpamHistoricalScanner.Result result = SpamHistoricalScanner.scan(\n                    getApplicationContext(), account, inbox, junk);\n            runOnUiThread(() -> {\n                if (!isSelected(account) || isFinishing() || isDestroyed())\n                    return;\n                if (result.success) {\n                    tvStatus.setText("Historisk skann ferdig · " + result.examined +\n                            " lest · " + result.newlyImported + " nye observasjoner · " +\n                            result.junk + " Spam · " + result.inbox + " Innboks" +\n                            (result.skippedNoEnvelope > 0\n                                    ? " · " + result.skippedNoEnvelope + " uten Envelope-To" : ""));\n                    loadReviewQueue(account, true);\n                    loadDashboardExtras(account);\n                } else\n                    tvStatus.setText("Historisk skann feilet: " + result.error);\n            });\n        });\n    }\n\n    private View actionHistoryCard(EntitySpamActionHistory action) {\n''')

print("Historical scan + SMTP lifecycle patch complete")

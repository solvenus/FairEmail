from pathlib import Path


def replace_one(path_s, old, new, label):
    path = Path(path_s)
    text = path.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path_s} {label}: expected exactly 1 match, got {count}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')
    print('patched', path_s, label)

# Physical SMTP state must not silently rewrite semantic alias lifecycle.
replace_one(
    'app/src/main/java/eu/faircode/email/AliasBurnManager.java',
'''        if (entity.state == EntityAlias.STATE_IGNORED)\n            return Outcome.failed("ignored-alias");\n        if (entity.smtp_reject_state == EntityAlias.SMTP_REJECT_VERIFIED)''',
'''        if (entity.smtp_reject_state == EntityAlias.SMTP_REJECT_VERIFIED)''',
    'remove ignored burn gate')
replace_one(
    'app/src/main/java/eu/faircode/email/AliasBurnManager.java',
'''        dao.markCompromised(accountUuid, alias);\n        dao.markSmtpRejectPending(accountUuid, alias, actuator.provider(), reason, now);''',
'''        // Explicit SMTP burn changes physical server state only. It must not\n        // invent a semantic reason such as COMPROMISED on the user's behalf.\n        dao.markSmtpRejectPending(accountUuid, alias, actuator.provider(), reason, now);''',
    'do not invent compromise state')
replace_one(
    'app/src/main/java/eu/faircode/email/AliasBurnManager.java',
'''                // A restored compromised alias stays COMPROMISED. Restoring SMTP\n                // acceptance is not evidence that the leak disappeared.\n                return Outcome.verified(result.changed);''',
'''                // Restore changes physical SMTP acceptance only. Semantic alias\n                // state stays exactly as the user/learning layer left it.\n                return Outcome.verified(result.changed);''',
    'restore semantic-state comment')

# Undo must respect the selected account.
replace_one(
    'app/src/main/java/eu/faircode/email/SpamUndoManager.java',
'''    public static final String ACTION_ALIAS_COMPROMISED = "ALIAS_COMPROMISED";\n    public static final String ACTION_RESET_ALL = "RESET_ALL";''',
'''    public static final String ACTION_ALIAS_COMPROMISED = "ALIAS_COMPROMISED";\n    public static final String ACTION_ALIAS_STATE = "ALIAS_STATE";\n    public static final String ACTION_RESET_ALL = "RESET_ALL";''',
    'alias state action constant')
replace_one(
    'app/src/main/java/eu/faircode/email/SpamUndoManager.java',
'''    public static EntitySpamActionHistory latest(Context context, String accountUuid) {\n        return latest(context);\n    }''',
'''    public static EntitySpamActionHistory latest(Context context, String accountUuid) {\n        if (context == null || accountUuid == null || accountUuid.trim().isEmpty())\n            return null;\n        try {\n            return SpamIntelligenceDB.getInstance(context.getApplicationContext())\n                    .actions().getLatestUndoable(accountUuid.trim());\n        } catch (Throwable ex) {\n            Log.e(ex);\n            return null;\n        }\n    }''',
    'account-scoped latest')
replace_one(
    'app/src/main/java/eu/faircode/email/SpamUndoManager.java',
'''        Context app = context.getApplicationContext();\n        SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);\n        EntitySpamActionHistory history = db.actions().getLatestUndoable();\n        if (history == null || history.id == null || history.before_json == null)''',
'''        if (accountUuid == null || accountUuid.trim().isEmpty())\n            return UndoResult.FAILED;\n\n        Context app = context.getApplicationContext();\n        SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);\n        EntitySpamActionHistory history = db.actions().getLatestUndoable(accountUuid.trim());\n        if (history == null || history.id == null || history.before_json == null)''',
    'account-scoped undo')

# Truthful veto wording in Rules.
replace_one(
    'app/src/main/java/eu/faircode/email/ActivitySpamControl.java',
'''        llPage.addView(policyCheck("Tillat manuell SMTP-burn",\n                "Viser serverhandlingen når burn-policyen sier READY_TO_BURN. Krever fortsatt bekreftelse og read-back.",''',
'''        llPage.addView(policyCheck("Tillat manuell SMTP-burn",\n                "Manuell serverhandling er tilgjengelig når cPanel er konfigurert. Readiness gir anbefalinger, ikke sperrer. Hver burn krever eksplisitt bekreftelse og read-back.",''',
    'truthful manual burn wording')

old_save = '''    private void saveAlias(String address, String service, String domain,\n                           String trusted, String replacement, String note, int statePosition) {\n        EntityAccount account = selectedAccount;\n        if (account == null || account.uuid == null)\n            return;\n        tvStatus.setText("Lagrer alias …");\n        executor.execute(() -> {\n            boolean ok = false;\n            try {\n                DaoAlias dao = SpamIntelligenceDB.getInstance(getApplicationContext()).alias();\n                EntityAlias fresh = dao.getAlias(account.uuid, address);\n                if (fresh != null) {\n                    fresh.service = cleanNullable(service);\n                    fresh.service_domain = cleanNullable(domain);\n                    fresh.trusted_domains = csvToJsonArray(trusted);\n                    fresh.replaced_by = cleanNullable(replacement);\n                    fresh.note = cleanNullable(note);\n                    fresh.state = stateFromPosition(statePosition);\n                    ok = dao.updateAlias(fresh) == 1;\n                    // Sender regex will also be synchronized naturally on next delivery/reply.\n                }\n            } catch (Throwable ex) {\n                Log.e(ex);\n            }\n            final boolean saved = ok;\n            runOnUiThread(() -> tvStatus.setText(saved ? "Alias lagret." : "Kunne ikke lagre alias."));\n        });\n    }'''
new_save = '''    private void saveAlias(String address, String service, String domain,\n                           String trusted, String replacement, String note, int statePosition) {\n        EntityAccount account = selectedAccount;\n        if (account == null || account.uuid == null)\n            return;\n        tvStatus.setText("Lagrer alias …");\n        executor.execute(() -> {\n            boolean ok = false;\n            boolean stateChanged = false;\n            try {\n                DaoAlias dao = SpamIntelligenceDB.getInstance(getApplicationContext()).alias();\n                EntityAlias fresh = dao.getAlias(account.uuid, address);\n                if (fresh != null) {\n                    int newState = stateFromPosition(statePosition);\n                    int oldState = fresh.state == null ? EntityAlias.STATE_ACTIVE : fresh.state;\n                    stateChanged = oldState != newState;\n\n                    fresh.service = cleanNullable(service);\n                    fresh.service_domain = cleanNullable(domain);\n                    fresh.trusted_domains = csvToJsonArray(trusted);\n                    fresh.replaced_by = cleanNullable(replacement);\n                    fresh.note = cleanNullable(note);\n                    fresh.state = newState;\n\n                    if (stateChanged) {\n                        final EntityAlias target = fresh;\n                        final int targetState = newState;\n                        ok = SpamUndoManager.runAccountAction(\n                                getApplicationContext(), account.uuid,\n                                SpamUndoManager.ACTION_ALIAS_STATE,\n                                "Endre aliasstatus",\n                                () -> {\n                                    boolean updated = dao.updateAlias(target) == 1;\n                                    if (updated && targetState == EntityAlias.STATE_ACTIVE)\n                                        AliasCompromiseReviewStore.markReviewedHealthy(\n                                                getApplicationContext(), target);\n                                    return updated;\n                                });\n                    } else\n                        ok = dao.updateAlias(fresh) == 1;\n                    // Sender regex will also be synchronized naturally on next delivery/reply.\n                }\n            } catch (Throwable ex) {\n                Log.e(ex);\n            }\n            final boolean saved = ok;\n            final boolean lifecycleChanged = stateChanged;\n            runOnUiThread(() -> {\n                tvStatus.setText(saved ? "Alias lagret." : "Kunne ikke lagre alias.");\n                if (saved && lifecycleChanged)\n                    Snackbar.make(svContent, "Aliasstatus endret.", Snackbar.LENGTH_LONG)\n                            .setAction("ANGRE", v -> undoLatest()).show();\n                if (saved)\n                    loadDashboardExtras(account);\n            });\n        });\n    }'''
replace_one('app/src/main/java/eu/faircode/email/ActivitySpamControl.java', old_save, new_save,
            'undoable manual alias lifecycle')

print('control-integrity patch complete')

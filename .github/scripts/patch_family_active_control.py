from pathlib import Path

ACT = Path('app/src/main/java/eu/faircode/email/ActivitySpamControl.java')


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one match, found {count}')
    return text.replace(old, new, 1)

text = ACT.read_text()

old = '''        TextView title = valueText(name, 17f, true);\n        body.addView(title, matchWrap());\n        body.addView(bodyText(family.confirmed_count + " bekreftet · " +\n                family.strong_unknown_count + " eksakte nye treff"), matchWrapWithMargin(0, 5, 0, 0));\n'''
new = '''        TextView title = valueText(name, 17f, true);\n        body.addView(title, matchWrap());\n        TextView activeState = bodyText(family.active\n                ? "● AKTIV · brukes av exact match"\n                : "○ INAKTIV · brukes ikke til nye exact-match treff");\n        activeState.setTypeface(Typeface.DEFAULT, Typeface.BOLD);\n        body.addView(activeState, matchWrapWithMargin(0, 4, 0, 0));\n        body.addView(bodyText(family.confirmed_count + " bekreftet · " +\n                family.strong_unknown_count + " eksakte nye treff"), matchWrapWithMargin(0, 5, 0, 0));\n'''
text = replace_once(text, old, new, 'family-active-state')

old = '''        Button rescore = secondaryButton("Skann på nytt");\n        rescore.setOnClickListener(v -> {\n            EntityAccount account = selectedAccount;\n            if (account != null)\n                SpamFamilyLabRepository.requestRescore(getApplicationContext(), account.uuid, family.family_id);\n            tvStatus.setText("Eksakt rescan er lagt i kø.");\n        });\n        actions.addView(rescore, weightedButton());\n        body.addView(actions, matchWrap());\n        card.addView(body, matchWrap());\n'''
new = '''        Button rescore = secondaryButton("Skann på nytt");\n        rescore.setEnabled(family.active);\n        rescore.setOnClickListener(v -> {\n            EntityAccount account = selectedAccount;\n            if (account != null)\n                SpamFamilyLabRepository.requestRescore(getApplicationContext(), account.uuid, family.family_id);\n            tvStatus.setText("Eksakt rescan er lagt i kø.");\n        });\n        actions.addView(rescore, weightedButton());\n        body.addView(actions, matchWrap());\n\n        Button active = secondaryButton(family.active ? "Deaktiver spamgruppe" : "Gjenoppliv spamgruppe");\n        active.setOnClickListener(v -> setFamilyActive(family, !family.active));\n        body.addView(active, matchWrapWithMargin(0, 5, 0, 0));\n        card.addView(body, matchWrap());\n'''
text = replace_once(text, old, new, 'family-active-button')

marker = '''    private void showFamilyMessages(TupleSpamFamilyOverview family) {\n'''
method = '''    private void setFamilyActive(TupleSpamFamilyOverview family, boolean active) {\n        EntityAccount account = selectedAccount;\n        if (account == null || account.uuid == null || family == null)\n            return;\n\n        tvStatus.setText(active ? "Gjenoppliver spamgruppe …" : "Deaktiverer spamgruppe …");\n        executor.execute(() -> {\n            boolean changed = SpamUndoManager.runAccountAction(\n                    getApplicationContext(), account.uuid, "FAMILY_ACTIVE",\n                    active ? "Gjenoppliv spamgruppe" : "Deaktiver spamgruppe",\n                    () -> {\n                        DaoSpamFamily dao = SpamIntelligenceDB.getInstance(\n                                getApplicationContext()).family();\n                        EntitySpamFamily current = dao.getFamily(family.family_id);\n                        if (current == null || current.id == null ||\n                                !account.uuid.equals(current.account_uuid) ||\n                                Boolean.TRUE.equals(current.active) == active)\n                            return false;\n\n                        long now = System.currentTimeMillis();\n                        if (dao.setFamilyActive(family.family_id, active, now) != 1)\n                            return false;\n                        if (!active)\n                            dao.clearPredictionsForFamily(family.family_id, now);\n                        return true;\n                    });\n\n            if (changed && active)\n                SpamFamilyLabRepository.requestRescore(\n                        getApplicationContext(), account.uuid, family.family_id);\n\n            runOnUiThread(() -> {\n                if (!isSelected(account) || isFinishing() || isDestroyed())\n                    return;\n                if (changed) {\n                    String message = active\n                            ? "Spamgruppen er gjenopplivet og rescan er lagt i kø."\n                            : "Spamgruppen er deaktivert. Bekreftet spam er beholdt.";\n                    tvStatus.setText(message);\n                    Snackbar.make(svContent, message, Snackbar.LENGTH_LONG)\n                            .setAction("ANGRE", v -> undoLatest()).show();\n                } else\n                    tvStatus.setText("Spamgruppestatus var allerede oppdatert.");\n            });\n        });\n    }\n\n'''
if text.count(marker) != 1:
    raise SystemExit(f'family-active-method-marker: expected one match, found {text.count(marker)}')
text = text.replace(marker, method + marker, 1)

ACT.write_text(text)
print('family active control patch applied')

#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/eu/faircode/email/ActivitySpamFamilyLab.java")
text = path.read_text(encoding="utf-8")
marker = 'Button undo = secondaryButton("Angre siste valg")'
if marker in text:
    print("Spam Control undo UI patch already present")
    raise SystemExit(0)

replacements = []

replacements.append((
'''import androidx.lifecycle.LiveData;\n\nimport java.util.ArrayList;''',
'''import androidx.lifecycle.LiveData;\n\nimport com.google.android.material.snackbar.Snackbar;\n\nimport java.util.ArrayList;'''))

replacements.append((
'''        tvStatus.setText("Laster e-postkonto …");\n        root.addView(tvStatus, matchWrap());\n\n        svContent = new ScrollView(this);''',
'''        tvStatus.setText("Laster e-postkonto …");\n        root.addView(tvStatus, matchWrap());\n\n        LinearLayout historyRow = new LinearLayout(this);\n        historyRow.setOrientation(LinearLayout.HORIZONTAL);\n        historyRow.setPadding(0, 0, 0, dp(8));\n\n        Button undo = secondaryButton("Angre siste valg");\n        undo.setOnClickListener(v -> {\n            EntityAccount account = selectedAccount;\n            if (account != null)\n                undoLatest(account);\n        });\n        historyRow.addView(undo, weightedButton());\n\n        Button reset = secondaryButton("Nullstill læring");\n        reset.setOnClickListener(v -> {\n            EntityAccount account = selectedAccount;\n            if (account != null)\n                showResetDialog(account);\n        });\n        historyRow.addView(reset, weightedButton());\n        root.addView(historyRow, matchWrap());\n\n        svContent = new ScrollView(this);'''))

replacements.append((
'''                .setPositiveButton("Lagre", (dialog, which) -> {\n                    String name = input.getText().toString();\n                    executor.execute(() -> SpamFamilyLabRepository.renameFamily(\n                            getApplicationContext(), group.family_id, name));\n                })''',
'''                .setPositiveButton("Lagre", (dialog, which) -> {\n                    String name = input.getText().toString();\n                    EntityAccount account = selectedAccount;\n                    if (account == null || account.uuid == null)\n                        return;\n                    executor.execute(() -> {\n                        boolean changed = SpamUndoManager.renameFamily(\n                                getApplicationContext(), account.uuid, group.family_id, name);\n                        runOnUiThread(() -> {\n                            if (changed) {\n                                tvStatus.setText("Navnet er lagret.");\n                                Snackbar.make(svContent, "Navnet er lagret.", Snackbar.LENGTH_LONG)\n                                        .setAction("ANGRE", v -> undoLatest(account))\n                                        .show();\n                            } else\n                                tvStatus.setText("Kunne ikke endre navnet.");\n                        });\n                    });\n                })'''))

replacements.append((
'''                    () -> SpamFamilyLabRepository.confirmSpam(\n                            getApplicationContext(), account.uuid,\n                            group.family_id, candidate.messageId)))''',
'''                    () -> SpamUndoManager.runMessageAction(\n                            getApplicationContext(), account.uuid,\n                            group.family_id, candidate.messageId,\n                            SpamUndoManager.ACTION_SAME_SPAM, "Samme spam",\n                            () -> SpamFamilyLabRepository.confirmSpam(\n                                    getApplicationContext(), account.uuid,\n                                    group.family_id, candidate.messageId))))'''))

replacements.append((
'''                    () -> SpamFamilyLabRepository.markOtherSpam(\n                            getApplicationContext(), account.uuid,\n                            group.family_id, candidate.messageId)))''',
'''                    () -> SpamUndoManager.runMessageAction(\n                            getApplicationContext(), account.uuid,\n                            group.family_id, candidate.messageId,\n                            SpamUndoManager.ACTION_OTHER_SPAM, "Annen spam",\n                            () -> SpamFamilyLabRepository.markOtherSpam(\n                                    getApplicationContext(), account.uuid,\n                                    group.family_id, candidate.messageId))))'''))

replacements.append((
'''                    () -> SpamFamilyLabRepository.markLegitimate(\n                            getApplicationContext(), account.uuid, candidate.messageId)))''',
'''                    () -> SpamUndoManager.runMessageAction(\n                            getApplicationContext(), account.uuid,\n                            group.family_id, candidate.messageId,\n                            SpamUndoManager.ACTION_NOT_SPAM, "Ikke spam",\n                            () -> SpamFamilyLabRepository.markLegitimate(\n                                    getApplicationContext(), account.uuid, candidate.messageId))))'''))

replacements.append((
'''                if (finalResult == SpamFamilyLabRepository.ActionResult.APPLIED)\n                    tvStatus.setText(successText);\n                else\n                    tvStatus.setText(actionFailure(finalResult));''',
'''                if (finalResult == SpamFamilyLabRepository.ActionResult.APPLIED) {\n                    tvStatus.setText(successText);\n                    Snackbar.make(svContent, successText, Snackbar.LENGTH_LONG)\n                            .setAction("ANGRE", v -> undoLatest(account))\n                            .show();\n                } else\n                    tvStatus.setText(actionFailure(finalResult));'''))

methods = '''\n    private void undoLatest(EntityAccount account) {\n        if (account == null || account.uuid == null)\n            return;\n        final int scrollY = svContent == null ? 0 : svContent.getScrollY();\n        tvStatus.setText("Angrer siste valg …");\n        executor.execute(() -> {\n            SpamUndoManager.UndoResult result = SpamUndoManager.undoLatest(\n                    getApplicationContext(), account.uuid);\n            runOnUiThread(() -> {\n                if (isFinishing() || isDestroyed() || !isSelected(account))\n                    return;\n                if (result == SpamUndoManager.UndoResult.APPLIED) {\n                    tvStatus.setText("Siste valg er angret.");\n                    if (selectedGroup != null && selectedGroupId != null)\n                        loadCandidates(account, selectedGroup, scrollY, false);\n                } else if (result == SpamUndoManager.UndoResult.NOTHING_TO_UNDO)\n                    tvStatus.setText("Det er ingen flere valg å angre.");\n                else\n                    tvStatus.setText("Kunne ikke angre siste valg.");\n            });\n        });\n    }\n\n    private void showResetDialog(EntityAccount account) {\n        new AlertDialog.Builder(this)\n                .setTitle("Nullstill all læring?")\n                .setMessage("Dette sletter spam/ikke-spam-valgene dine, spamgruppene, " +\n                        "modelltreffene og lærte spam-tellere for denne kontoen.\\n\\n" +\n                        "E-postene dine, aliasadressene, manuelle alias-domener, replacement-status " +\n                        "og SMTP/cPanel-regler blir ikke rørt. Undo-historikken slettes også.")\n                .setNegativeButton(android.R.string.cancel, null)\n                .setPositiveButton("Nullstill", (dialog, which) -> performReset(account))\n                .show();\n    }\n\n    private void performReset(EntityAccount account) {\n        if (account == null || account.uuid == null)\n            return;\n        tvStatus.setText("Nullstiller Spamkontroll …");\n        executor.execute(() -> {\n            boolean reset = SpamResetManager.resetLearning(\n                    getApplicationContext(), account.uuid);\n            runOnUiThread(() -> {\n                if (isFinishing() || isDestroyed() || !isSelected(account))\n                    return;\n                if (reset) {\n                    selectedGroupId = null;\n                    selectedGroup = null;\n                    candidateGeneration.incrementAndGet();\n                    showChooseGroupMessage();\n                    tvStatus.setText("All spam-læring er nullstilt. Du har blanke ark.");\n                    if (svContent != null)\n                        svContent.post(() -> svContent.scrollTo(0, 0));\n                } else\n                    tvStatus.setText("Kunne ikke nullstille læringen.");\n            });\n        });\n    }\n\n'''
replacements.append((
'''    private void loadAccounts() {''',
methods + '''    private void loadAccounts() {'''))

for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one patch anchor, found {count}: {old[:80]!r}")
    text = text.replace(old, new, 1)

path.write_text(text, encoding="utf-8")
print("Patched Spam Control with persistent undo and safe learning reset UI")

#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


activity = Path("app/src/main/java/eu/faircode/email/ActivitySpamFamilyLab.java")
compose = Path("app/src/main/java/eu/faircode/email/FragmentCompose.java")

replace_once(
    activity,
    '        Button reset = secondaryButton("Nullstill læring");',
    '        Button reset = secondaryButton("Nullstill all læring");',
    "reset button",
)

replace_once(
    activity,
    '''                .setMessage("Dette sletter spam/ikke-spam-valgene dine, spamgruppene, " +
                        "modelltreffene og lærte spam-tellere for denne kontoen.\\n\\n" +
                        "E-postene dine, aliasadressene, manuelle alias-domener, replacement-status " +
                        "og SMTP/cPanel-regler blir ikke rørt. Undo-historikken slettes også.")''',
    '''                .setMessage("Dette nullstiller all spamlæring i Spamkontroll på alle e-postkontoene " +
                        "i denne testappen. Spam/ikke-spam-valg, spamgrupper, modelltreff og lærte " +
                        "spam-tellere blir blanke ark.\\n\\n" +
                        "E-postene dine, aliasadressene, forventede og godkjente domener, replacement-status " +
                        "og SMTP/cPanel-regler blir ikke rørt. Hele nullstillingen kan angres med " +
                        "Angre siste valg.")''',
    "reset explanation",
)

replace_once(
    activity,
    '''    private void performReset(EntityAccount account) {
        if (account == null || account.uuid == null)
            return;
        tvStatus.setText("Nullstiller Spamkontroll …");''',
    '''    private void performReset(EntityAccount account) {
        if (account == null || account.uuid == null)
            return;
        final int scrollY = svContent == null ? 0 : svContent.getScrollY();
        tvStatus.setText("Nullstiller Spamkontroll …");''',
    "reset scroll capture",
)

replace_once(
    activity,
    '''                    tvStatus.setText("All spam-læring er nullstilt. Du har blanke ark.");
                    if (svContent != null)
                        svContent.post(() -> svContent.scrollTo(0, 0));''',
    '''                    tvStatus.setText("All spamlæring er nullstilt. Du har blanke ark.");
                    Snackbar.make(svContent,
                                    "All spamlæring er nullstilt.",
                                    Snackbar.LENGTH_LONG)
                            .setAction("ANGRE", v -> undoLatest(account))
                            .show();
                    if (svContent != null)
                        svContent.post(() -> svContent.scrollTo(0, scrollY));''',
    "reset undo and scroll restore",
)

replace_once(
    compose,
    '                        draft.extra = (identity.sender_extra ? extra : null);',
    '                        draft.extra = (SpamIntelligence.permitsExtra(context, identity, extra) ? extra : null);',
    "reply alias persistence",
)

print("PASS: patched Spam Control reset UX and reply alias persistence")

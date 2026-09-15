#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


activity = Path("app/src/main/java/eu/faircode/email/ActivitySpamFamilyLab.java")

replace_once(
    activity,
    '''        TextView groupHelp = bodyText(
                "En spamgruppe er en samling meldinger som ser ut til å komme fra samme spamkampanje. " +
                        "Velg en gruppe for å kontrollere treffene.");''',
    '''        TextView groupHelp = bodyText(
                "Velg en spamgruppe for å se meldingene som systemet mener hører sammen.");''',
    "compact group help",
)

replace_once(
    activity,
    '''        TextView title = new TextView(this);
        title.setText("Slik bruker du Spamkontroll");
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title, matchWrap());

        TextView text = bodyText(
                "1. Velg en spamgruppe.\\n" +
                        "2. Se på emne, avsender og alias.\\n" +
                        "3. Trykk Samme spam, Annen spam eller Ikke spam.\\n\\n" +
                        "Filteret lærer av valgene dine. Denne skjermen sletter ikke e-post og brenner ikke alias.");''',
    '''        TextView title = new TextView(this);
        title.setText("Hva skal jeg se på?");
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title, matchWrap());

        TextView text = bodyText(
                "EMNE · AVSENDERNAVN · AVSENDERADRESSE · ALIAS\\n\\n" +
                        "Dette er hovedsignalene. Velg deretter Samme spam, Annen spam eller Ikke spam. " +
                        "Alle valg kan angres.");''',
    "human help card",
)

replace_once(
    activity,
    '''        if (candidate.preview != null && !candidate.preview.trim().isEmpty()) {
            TextView preview = bodyText(candidate.preview.trim());
            preview.setMaxLines(3);
            preview.setEllipsize(TextUtils.TruncateAt.END);
            preview.setPadding(0, dp(10), 0, 0);
            content.addView(preview, matchWrap());
        }

''',
    '''''',
    "remove preview from primary card",
)

replace_once(
    activity,
    '        Button details = secondaryButton("Tekniske detaljer");',
    '        Button details = secondaryButton("Detaljer");',
    "rename details button",
)

replace_once(
    activity,
    '''            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(0, dp(12), 0, 0);

            Button same = actionButton(candidate.confirmedThisFamily ? "Samme spam ✓" : "Samme spam");
            same.setEnabled(!candidate.confirmedThisFamily);
            same.setOnClickListener(v -> runCandidateAction(
                    account, group, candidate,
                    "Lagrer: samme spam …",
                    "Lagret som samme spam.",
                    () -> SpamUndoManager.runMessageAction(
                            getApplicationContext(), account.uuid,
                            group.family_id, candidate.messageId,
                            SpamUndoManager.ACTION_SAME_SPAM, "Samme spam",
                            () -> SpamFamilyLabRepository.confirmSpam(
                                    getApplicationContext(), account.uuid,
                                    group.family_id, candidate.messageId))));
            actions.addView(same, weightedButton());

            Button other = actionButton("Annen spam");
            other.setOnClickListener(v -> runCandidateAction(
                    account, group, candidate,
                    "Lagrer: annen spam …",
                    "Lagret som spam, men ikke denne spamgruppen.",
                    () -> SpamUndoManager.runMessageAction(
                            getApplicationContext(), account.uuid,
                            group.family_id, candidate.messageId,
                            SpamUndoManager.ACTION_OTHER_SPAM, "Annen spam",
                            () -> SpamFamilyLabRepository.markOtherSpam(
                                    getApplicationContext(), account.uuid,
                                    group.family_id, candidate.messageId))));
            actions.addView(other, weightedButton());

            Button legitimate = actionButton(candidate.explicitHam ? "Ikke spam ✓" : "Ikke spam");
            legitimate.setEnabled(!candidate.explicitHam);
            legitimate.setOnClickListener(v -> runCandidateAction(
                    account, group, candidate,
                    "Lagrer: ikke spam …",
                    "Lagret som ikke spam.",
                    () -> SpamUndoManager.runMessageAction(
                            getApplicationContext(), account.uuid,
                            group.family_id, candidate.messageId,
                            SpamUndoManager.ACTION_NOT_SPAM, "Ikke spam",
                            () -> SpamFamilyLabRepository.markLegitimate(
                                    getApplicationContext(), account.uuid, candidate.messageId))));
            actions.addView(legitimate, weightedButton());

            content.addView(actions, matchWrap());''',
    '''            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.VERTICAL);
            actions.setPadding(0, dp(14), 0, 0);

            Button same = primaryActionButton(candidate.confirmedThisFamily ? "Samme spam ✓" : "Samme spam");
            same.setEnabled(!candidate.confirmedThisFamily);
            same.setOnClickListener(v -> runCandidateAction(
                    account, group, candidate,
                    "Lagrer: samme spam …",
                    "Lagret som samme spam.",
                    () -> SpamUndoManager.runMessageAction(
                            getApplicationContext(), account.uuid,
                            group.family_id, candidate.messageId,
                            SpamUndoManager.ACTION_SAME_SPAM, "Samme spam",
                            () -> SpamFamilyLabRepository.confirmSpam(
                                    getApplicationContext(), account.uuid,
                                    group.family_id, candidate.messageId))));
            actions.addView(same, matchWrap());

            LinearLayout secondaryActions = new LinearLayout(this);
            secondaryActions.setOrientation(LinearLayout.HORIZONTAL);
            secondaryActions.setPadding(0, dp(6), 0, 0);

            Button other = actionButton("Annen spam");
            other.setOnClickListener(v -> runCandidateAction(
                    account, group, candidate,
                    "Lagrer: annen spam …",
                    "Lagret som spam, men ikke denne spamgruppen.",
                    () -> SpamUndoManager.runMessageAction(
                            getApplicationContext(), account.uuid,
                            group.family_id, candidate.messageId,
                            SpamUndoManager.ACTION_OTHER_SPAM, "Annen spam",
                            () -> SpamFamilyLabRepository.markOtherSpam(
                                    getApplicationContext(), account.uuid,
                                    group.family_id, candidate.messageId))));
            secondaryActions.addView(other, weightedButton());

            Button legitimate = actionButton(candidate.explicitHam ? "Ikke spam ✓" : "Ikke spam");
            legitimate.setEnabled(!candidate.explicitHam);
            legitimate.setOnClickListener(v -> runCandidateAction(
                    account, group, candidate,
                    "Lagrer: ikke spam …",
                    "Lagret som ikke spam.",
                    () -> SpamUndoManager.runMessageAction(
                            getApplicationContext(), account.uuid,
                            group.family_id, candidate.messageId,
                            SpamUndoManager.ACTION_NOT_SPAM, "Ikke spam",
                            () -> SpamFamilyLabRepository.markLegitimate(
                                    getApplicationContext(), account.uuid, candidate.messageId))));
            secondaryActions.addView(legitimate, weightedButton());

            actions.addView(secondaryActions, matchWrap());
            content.addView(actions, matchWrap());''',
    "mobile decision hierarchy",
)

replace_once(
    activity,
    '''                .append("Unsubscribe: ").append(candidate.hasUnsubscribe ? "ja" : "nei");''',
    '''                .append("Unsubscribe: ").append(candidate.hasUnsubscribe ? "ja" : "nei")
                .append("\\n\\nForhåndsvisning:\\n")
                .append(empty(candidate.preview, "(ingen)"));''',
    "move preview into details",
)

replace_once(
    activity,
    '''    private Button secondaryButton(String text) {
        Button button = actionButton(text);
        button.setMinHeight(dp(42));
        return button;
    }
''',
    '''    private Button primaryActionButton(String text) {
        Button button = actionButton(text);
        button.setMinHeight(dp(56));
        button.setTextSize(16f);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = actionButton(text);
        button.setMinHeight(dp(42));
        return button;
    }
''',
    "primary decision button",
)

print("PASS: polished Spam Control for fast human scanning")

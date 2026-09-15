from pathlib import Path

ACT = Path('app/src/main/java/eu/faircode/email/ActivitySpamControl.java')
text = ACT.read_text()


def replace_once(old, new, label):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one match, found {count}')
    text = text.replace(old, new, 1)

old = '''        TextView history = bodyText("Først sett: " + formatWhen(alias.first_seen) +\n                "\\nSist sett: " + formatWhen(alias.last_seen) +\n                "\\nSiste spam: " + formatWhen(alias.last_spam));\n        history.setPadding(0, dp(3), 0, dp(8));\n        form.addView(history, matchWrap());\n'''
new = '''        TextView timelineTitle = valueText("Tidslinje", 15f, true);\n        timelineTitle.setPadding(0, dp(5), 0, dp(2));\n        form.addView(timelineTitle, matchWrap());\n        TextView history = bodyText(aliasTimelineText(alias));\n        history.setTextIsSelectable(true);\n        history.setPadding(0, dp(1), 0, dp(8));\n        form.addView(history, matchWrap());\n'''
replace_once(old, new, 'alias-timeline-view')

marker = '''    private void showAliasEditor(EntityAlias alias) {\n'''
helper = '''    private String aliasTimelineText(EntityAlias alias) {\n        if (alias == null)\n            return "Ingen tidslinje tilgjengelig.";\n        StringBuilder text = new StringBuilder();\n        text.append("Først sett: ").append(formatWhen(alias.first_seen))\n                .append("\\nSist sett: ").append(formatWhen(alias.last_seen))\n                .append("\\nSiste legitime trafikk: ").append(formatWhen(alias.last_ham))\n                .append("\\nSiste spam: ").append(formatWhen(alias.last_spam))\n                .append("\\nTrafikk: ")\n                .append(alias.messages == null ? 0 : alias.messages).append(" meldinger · ")\n                .append(alias.ham_hits == null ? 0 : alias.ham_hits).append(" legit · ")\n                .append(alias.spam_hits == null ? 0 : alias.spam_hits).append(" spam");\n\n        if (!TextUtils.isEmpty(alias.replaced_by))\n            text.append("\\nReplacement: ").append(alias.replaced_by);\n        if (alias.smtp_reject_requested_at != null)\n            text.append("\\nSMTP-operasjon forespurt: ")\n                    .append(formatWhen(alias.smtp_reject_requested_at));\n        if (alias.smtp_reject_verified_at != null)\n            text.append("\\nSMTP read-back verifisert: ")\n                    .append(formatWhen(alias.smtp_reject_verified_at));\n        if (!TextUtils.isEmpty(alias.smtp_reject_error))\n            text.append("\\nSiste SMTP-feil: ").append(alias.smtp_reject_error);\n\n        text.append("\\nNå: ").append(aliasState(alias))\n                .append(" · SMTP ").append(smtpState(alias));\n        return text.toString();\n    }\n\n'''
if text.count(marker) != 1:
    raise SystemExit(f'alias-timeline-helper-marker: expected one match, found {text.count(marker)}')
text = text.replace(marker, helper + marker, 1)

ACT.write_text(text)
print('alias timeline patch applied')

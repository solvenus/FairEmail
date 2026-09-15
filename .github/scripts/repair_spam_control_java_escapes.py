#!/usr/bin/env python3
from pathlib import Path

p = Path("app/src/main/java/eu/faircode/email/ActivitySpamControl.java")
text = p.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    '''        TextView history = bodyText("Først sett: " + formatWhen(alias.first_seen) +
                "
Sist sett: " + formatWhen(alias.last_seen) +
                "
Siste spam: " + formatWhen(alias.last_spam));''',
    r'''        TextView history = bodyText("Først sett: " + formatWhen(alias.first_seen) +
                "\nSist sett: " + formatWhen(alias.last_seen) +
                "\nSiste spam: " + formatWhen(alias.last_spam));''',
    "alias timeline escapes",
)

replace_once(
    '''        body.addView(bodyText(TextUtils.join("
", labels)), matchWrapWithMargin(0, 4, 0, 0));''',
    r'''        body.addView(bodyText(TextUtils.join("\n", labels)), matchWrapWithMargin(0, 4, 0, 0));''',
    "network label join escape",
)

replace_once(
    '''                if (!TextUtils.isEmpty(family.name))
                    return family.name.trim() + (descriptor == null ? "" : " · " + descriptor.replace('
', ' '));
                if (descriptor != null)
                    return descriptor.replace('
', ' ');''',
    r'''                if (!TextUtils.isEmpty(family.name))
                    return family.name.trim() + (descriptor == null ? "" : " · " + descriptor.replace('\n', ' '));
                if (descriptor != null)
                    return descriptor.replace('\n', ' ');''',
    "family descriptor newline char escape",
)

replace_once(
    '''        StringBuilder text = new StringBuilder();
        for (EntitySpamActionHistory action : recentActions) {
            if (text.length() > 0)
                text.append("

");
            text.append(formatWhen(action.created_at)).append(" · ")
                    .append(action.description == null ? action.action : action.description);
            if (action.message_id != null)
                text.append("
Melding: ").append(action.message_id);
            text.append("
Status: ").append(action.undone_at == null ? "aktiv" : "angret");
        }''',
    r'''        StringBuilder text = new StringBuilder();
        for (EntitySpamActionHistory action : recentActions) {
            if (text.length() > 0)
                text.append("\n\n");
            text.append(formatWhen(action.created_at)).append(" · ")
                    .append(action.description == null ? action.action : action.description);
            if (action.message_id != null)
                text.append("\nMelding: ").append(action.message_id);
            text.append("\nStatus: ").append(action.undone_at == null ? "aktiv" : "angret");
        }''',
    "learning history escapes",
)

replace_once(
    '''        StringBuilder text = new StringBuilder();
        text.append("Spamkontroll status
")
                .append("Konto: ").append(accountLabel(account)).append('
')
                .append("Arbeidskø: ").append(reviewCount).append('
')
                .append("Aliaser: ").append(aliases.size()).append('
')
                .append("Spamgrupper: ").append(families.size()).append('
')
                .append("Spamnettverk: ").append(networks.size()).append("

")
                .append("Regler
")
                .append("Exact family: ").append(SpamControlPolicy.exactFamilyDetection(this)).append('
')
                .append("Auto-label exact: ").append(SpamControlPolicy.autoLabelExact(this)).append('
')
                .append("Spam kompromitterer alias: ").append(SpamControlPolicy.markAliasCompromised(this)).append('
')
                .append("Fremmed sender evidens: ").append(SpamControlPolicy.foreignSenderEvidence(this)).append('
')
                .append("SMTP-burn: ").append(SpamControlPolicy.smtpBurnEnabled(this)).append("

")
                .append("Aliasstatus
");
        for (EntityAlias alias : aliases)
            text.append(alias.address).append(" · ").append(aliasState(alias))
                    .append(" · spam=").append(alias.spam_hits == null ? 0 : alias.spam_hits)
                    .append(" · ham=").append(alias.ham_hits == null ? 0 : alias.ham_hits)
                    .append(" · smtp=").append(smtpState(alias)).append('
');''',
    r'''        StringBuilder text = new StringBuilder();
        text.append("Spamkontroll status\n")
                .append("Konto: ").append(accountLabel(account)).append('\n')
                .append("Arbeidskø: ").append(reviewCount).append('\n')
                .append("Aliaser: ").append(aliases.size()).append('\n')
                .append("Spamgrupper: ").append(families.size()).append('\n')
                .append("Spamnettverk: ").append(networks.size()).append("\n\n")
                .append("Regler\n")
                .append("Exact family: ").append(SpamControlPolicy.exactFamilyDetection(this)).append('\n')
                .append("Auto-label exact: ").append(SpamControlPolicy.autoLabelExact(this)).append('\n')
                .append("Spam kompromitterer alias: ").append(SpamControlPolicy.markAliasCompromised(this)).append('\n')
                .append("Fremmed sender evidens: ").append(SpamControlPolicy.foreignSenderEvidence(this)).append('\n')
                .append("SMTP-burn: ").append(SpamControlPolicy.smtpBurnEnabled(this)).append("\n\n")
                .append("Aliasstatus\n");
        for (EntityAlias alias : aliases)
            text.append(alias.address).append(" · ").append(aliasState(alias))
                    .append(" · spam=").append(alias.spam_hits == null ? 0 : alias.spam_hits)
                    .append(" · ham=").append(alias.ham_hits == null ? 0 : alias.ham_hits)
                    .append(" · smtp=").append(smtpState(alias)).append('\n');''',
    "status export escapes",
)

p.write_text(text, encoding="utf-8")
print("PASS: repaired Spam Control Java escape sequences")

from pathlib import Path
import re

path = Path('app/src/main/java/eu/faircode/email/ActivitySpamControl.java')
text = path.read_text(encoding='utf-8')


def replace_once(old, new, label):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly 1 match, got {count}')
    text = text.replace(old, new, 1)
    print('patched', label)

replace_once(
'''            llPage.addView(attentionCard(replacementsMissing + " kompromitterte aliaser mangler replacement",\n                    "De kan ikke bli trygt SMTP-døde før du har rotert dem.",\n                    "Åpne", v -> setSection(Section.ALIASES)), matchWrapWithMargin(0, 0, 0, 6));''',
'''            llPage.addView(attentionCard(replacementsMissing + " kompromitterte aliaser mangler replacement",\n                    "Replacement anbefales før SMTP-burn, men du kan overstyre dette.",\n                    "Åpne", v -> setSection(Section.ALIASES)), matchWrapWithMargin(0, 0, 0, 6));''',
'overview replacement wording')

pattern = re.compile(r'''    private void renderAliases\(\) \{.*?\n    private View aliasCard\(EntityAlias alias\) \{''', re.S)
match = pattern.search(text)
if not match:
    raise SystemExit('renderAliases block not found')
new_block = r'''    private void renderAliases() {
        llPage.addView(sectionTitle("Aliaser"), matchWrap());
        TextView intro = bodyText("Legitime aliaser og spam-/kompromitterte aliaser holdes i separate arbeidsflater. Uavklarte aliaser ligger for seg selv.");
        intro.setPadding(0, dp(2), 0, dp(8));
        llPage.addView(intro, matchWrap());

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Søk alias eller tjeneste");
        llPage.addView(search, matchWrapWithMargin(0, 0, 0, 10));

        LinearLayout spamList = new LinearLayout(this);
        spamList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout legitList = new LinearLayout(this);
        legitList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout unresolvedList = new LinearLayout(this);
        unresolvedList.setOrientation(LinearLayout.VERTICAL);

        llPage.addView(sectionTitleSmall("Spam / kompromitterte"), matchWrapWithMargin(0, 6, 0, 4));
        llPage.addView(spamList, matchWrap());
        llPage.addView(sectionTitleSmall("Legitime"), matchWrapWithMargin(0, 14, 0, 4));
        llPage.addView(legitList, matchWrap());
        llPage.addView(sectionTitleSmall("Uavklarte"), matchWrapWithMargin(0, 14, 0, 4));
        llPage.addView(unresolvedList, matchWrap());

        Runnable repopulate = () -> {
            String query = search.getText().toString();
            populateAliasBucket(spamList, query, 0,
                    "Ingen spam-/kompromitterte aliaser matcher søket.");
            populateAliasBucket(legitList, query, 1,
                    "Ingen legitime aliaser matcher søket.");
            populateAliasBucket(unresolvedList, query, 2,
                    "Ingen uavklarte aliaser matcher søket.");
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { repopulate.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        repopulate.run();
    }

    private void populateAliasBucket(LinearLayout list, String query, int bucket, String emptyText) {
        list.removeAllViews();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (EntityAlias alias : aliases) {
            if (alias == null || aliasBucket(alias) != bucket)
                continue;
            String hay = (alias.address + " " + empty(alias.service, "") + " " +
                    empty(alias.service_domain, "")).toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && !hay.contains(needle))
                continue;
            list.addView(aliasCard(alias), matchWrapWithMargin(0, 0, 0, 8));
            shown++;
        }
        if (shown == 0)
            list.addView(bodyText(emptyText), matchWrap());
    }

    /** 0=spam/problem, 1=legitimate, 2=unresolved. */
    private int aliasBucket(EntityAlias alias) {
        int spam = alias.spam_hits == null ? 0 : alias.spam_hits;
        int ham = alias.ham_hits == null ? 0 : alias.ham_hits;
        int smtp = alias.smtp_reject_state == null
                ? EntityAlias.SMTP_REJECT_NONE : alias.smtp_reject_state;

        if (spam > 0 ||
                alias.state == EntityAlias.STATE_COMPROMISED ||
                alias.state == EntityAlias.STATE_REPLACED ||
                smtp != EntityAlias.SMTP_REJECT_NONE)
            return 0;

        if (alias.state == EntityAlias.STATE_ACTIVE && ham > 0)
            return 1;

        return 2;
    }

    private View aliasCard(EntityAlias alias) {'''
text = text[:match.start()] + new_block + text[match.end():]
print('patched alias bucket UI')

old_actions = '''        if (alias.smtp_reject_state == EntityAlias.SMTP_REJECT_VERIFIED) {\n            Button restore = secondaryButton("Gjenopprett SMTP");\n            restore.setOnClickListener(v -> confirmRestore(alias));\n            actions.addView(restore, weightedButton());\n        } else if (readiness.verdict == AliasBurnPolicy.Verdict.REVIEW_COMPROMISE) {\n            Button review = secondaryButton("Vurder alias");\n            review.setOnClickListener(v -> showCompromiseReview(alias));\n            actions.addView(review, weightedButton());\n        } else if (readiness.verdict == AliasBurnPolicy.Verdict.COMPROMISED ||\n                readiness.verdict == AliasBurnPolicy.Verdict.ROTATE_FIRST) {\n            Button replacement = secondaryButton("Sett replacement");\n            replacement.setOnClickListener(v -> showAliasEditor(alias));\n            actions.addView(replacement, weightedButton());\n        } else if (readiness.verdict == AliasBurnPolicy.Verdict.VERIFY_REPLACEMENT) {\n            Button verify = secondaryButton("Skann Innboks");\n            verify.setOnClickListener(v -> runHistoricalScan(true, false));\n            actions.addView(verify, weightedButton());\n        } else if (readiness.burnAllowed) {\n            Button burn = secondaryButton(SpamControlPolicy.hasCpanelConfig(this)\n                    ? "SMTP-død" : "Konfigurer cPanel");\n            burn.setOnClickListener(v -> {\n                if (SpamControlPolicy.hasCpanelConfig(this))\n                    confirmBurn(alias);\n                else\n                    showCpanelDialog();\n            });\n            actions.addView(burn, weightedButton());\n        }\n        body.addView(actions, matchWrap());'''
new_actions = '''        if (alias.smtp_reject_state == EntityAlias.SMTP_REJECT_VERIFIED) {\n            Button restore = secondaryButton("Gjenopprett SMTP");\n            restore.setOnClickListener(v -> confirmRestore(alias));\n            actions.addView(restore, weightedButton());\n        } else {\n            if (readiness.verdict == AliasBurnPolicy.Verdict.REVIEW_COMPROMISE) {\n                Button review = secondaryButton("Vurder alias");\n                review.setOnClickListener(v -> showCompromiseReview(alias));\n                actions.addView(review, weightedButton());\n            } else if (readiness.verdict == AliasBurnPolicy.Verdict.COMPROMISED ||\n                    readiness.verdict == AliasBurnPolicy.Verdict.ROTATE_FIRST) {\n                Button replacement = secondaryButton("Sett replacement");\n                replacement.setOnClickListener(v -> showAliasEditor(alias));\n                actions.addView(replacement, weightedButton());\n            } else if (readiness.verdict == AliasBurnPolicy.Verdict.VERIFY_REPLACEMENT) {\n                Button verify = secondaryButton("Skann Innboks");\n                verify.setOnClickListener(v -> runHistoricalScan(true, false));\n                actions.addView(verify, weightedButton());\n            }\n        }\n        body.addView(actions, matchWrap());\n\n        if (alias.smtp_reject_state != EntityAlias.SMTP_REJECT_VERIFIED) {\n            Button burn = secondaryButton(!SpamControlPolicy.smtpBurnEnabled(this)\n                    ? "Aktiver manuell SMTP-burn"\n                    : !SpamControlPolicy.hasCpanelConfig(this)\n                    ? "Konfigurer cPanel for SMTP-burn"\n                    : readiness.burnAllowed ? "SMTP-død" : "SMTP-død likevel");\n            burn.setOnClickListener(v -> {\n                if (!SpamControlPolicy.smtpBurnEnabled(this)) {\n                    SpamControlPolicy.setBoolean(this, SpamControlPolicy.PREF_SMTP_BURN_ENABLED, true);\n                    tvStatus.setText("Manuell SMTP-burn er slått på.");\n                    renderCurrent();\n                } else if (!SpamControlPolicy.hasCpanelConfig(this))\n                    showCpanelDialog();\n                else\n                    confirmBurn(alias);\n            });\n            body.addView(burn, matchWrapWithMargin(0, 6, 0, 0));\n        }'''
replace_once(old_actions, new_actions, 'alias actions with veto')

old_confirm = '''        AliasBurnPolicy.Result readiness = AliasBurnReadiness.evaluate(this, alias);\n        if (!readiness.burnAllowed) {\n            tvStatus.setText("Aliaset er ikke klart for SMTP-burn: " + readiness.verdict);\n            return;\n        }\n        new AlertDialog.Builder(this)\n                .setTitle("Gjør alias SMTP-dødt?")\n                .setMessage(alias.address + "\\n\\nServeren skal svare med hard feil ved RCPT TO. " +\n                        "Spamkontroll verifiserer regelen med read-back før den kalles aktiv.")\n                .setNegativeButton(android.R.string.cancel, null)\n                .setPositiveButton("Gjør SMTP-dødt", (d, w) -> burnAlias(alias))\n                .show();'''
new_confirm = '''        AliasBurnPolicy.Result readiness = AliasBurnReadiness.evaluate(this, alias);\n        StringBuilder message = new StringBuilder();\n        message.append(alias.address)\n                .append("\\n\\nServeren skal svare med hard feil ved RCPT TO. ")\n                .append("Spamkontroll verifiserer regelen med read-back før den kalles aktiv.");\n        if (!readiness.burnAllowed) {\n            message.append("\\n\\n⚠ ANBEFALING, IKKE LÅS: ")\n                    .append(aliasLifecycleText(alias, readiness));\n            if (TextUtils.isEmpty(alias.replaced_by))\n                message.append("\\nReplacement er ikke registrert.");\n            else if (!AliasReplacementVerifier.isVerified(this, alias))\n                message.append("\\nReplacement er registrert, men ikke verifisert fra mottatt legitim trafikk.");\n            message.append("\\n\\nDu kan overstyre dette og burne aliaset nå.");\n        }\n        new AlertDialog.Builder(this)\n                .setTitle(readiness.burnAllowed ? "Gjør alias SMTP-dødt?" : "Overstyr og gjør alias SMTP-dødt?")\n                .setMessage(message.toString())\n                .setNegativeButton(android.R.string.cancel, null)\n                .setPositiveButton(readiness.burnAllowed ? "Gjør SMTP-dødt" : "BURN LIKEVEL",\n                        (d, w) -> burnAlias(alias))\n                .show();'''
replace_once(old_confirm, new_confirm, 'burn veto dialog')

old_lifecycle = '''            case COMPROMISED:\n                return "Kompromittert. Sett et replacement-alias.";\n            case ROTATE_FIRST:\n                return "Bytt alias hos tjenesten og registrer replacement her.";\n            case VERIFY_REPLACEMENT:\n                return "Replacement er satt. Venter på legitim/forventet mail til det nye aliaset.";\n            case READY_TO_BURN:\n                return SpamControlPolicy.hasCpanelConfig(this)\n                        ? "Replacement er verifisert. Klar for SMTP-død."\n                        : "Replacement er verifisert. Konfigurer cPanel for SMTP-død.";'''
new_lifecycle = '''            case COMPROMISED:\n                return "Kompromittert. Replacement anbefales før burn; du kan overstyre.";\n            case ROTATE_FIRST:\n                return "Bytt alias anbefales før burn; du kan overstyre.";\n            case VERIFY_REPLACEMENT:\n                return "Replacement er registrert, ikke verifisert. Du kan burne nå eller verifisere først.";\n            case READY_TO_BURN:\n                return SpamControlPolicy.hasCpanelConfig(this)\n                        ? "Replacement er verifisert. SMTP-død er klar."\n                        : "Replacement er verifisert. Konfigurer cPanel for SMTP-død.";'''
replace_once(old_lifecycle, new_lifecycle, 'non-paternalistic lifecycle wording')

path.write_text(text, encoding='utf-8')
print('alias separation/veto patch complete')

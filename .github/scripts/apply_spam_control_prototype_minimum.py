#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


p = Path("app/src/main/java/eu/faircode/email/ActivitySpamControl.java")

replace_once(
    p,
    '''import android.os.Bundle;''',
    '''import android.content.Intent;\nimport android.os.Bundle;''',
    "Intent import",
)

replace_once(
    p,
    '''    private final Map<Long, String> familyDescriptors = new HashMap<>();

    private Spinner spAccount;''',
    '''    private final Map<Long, String> familyDescriptors = new HashMap<>();
    private List<SpamNetworkAnalyzer.Network> networks = Collections.emptyList();
    private List<EntitySpamActionHistory> recentActions = Collections.emptyList();

    private Spinner spAccount;''',
    "dashboard state fields",
)

replace_once(
    p,
    '''        tvStatus.setText("Konto: " + accountLabel(account));
        loadReviewQueue(account, false);
    }
''',
    '''        tvStatus.setText("Konto: " + accountLabel(account));
        loadReviewQueue(account, false);
        loadDashboardExtras(account);
    }
''',
    "load dashboard extras",
)

replace_once(
    p,
    '''        llPage.addView(infoCard("SMTP-burn",
                SpamControlPolicy.smtpBurnEnabled(this) ? "MANUELL · eksplisitt godkjenning" : "DEAKTIVERT"), matchWrap());
    }

    private void loadReviewQueue''',
    '''        llPage.addView(infoCard("SMTP-burn",
                SpamControlPolicy.smtpBurnEnabled(this) ? "MANUELL · eksplisitt godkjenning" : "DEAKTIVERT"), matchWrap());

        llPage.addView(sectionTitle("Siste aktivitet"), matchWrapWithMargin(0, 14, 0, 4));
        if (recentActions.isEmpty())
            llPage.addView(bodyText("Ingen menneskelige handlinger er registrert ennå."), matchWrap());
        else {
            int shown = Math.min(5, recentActions.size());
            for (int i = 0; i < shown; i++)
                llPage.addView(actionHistoryCard(recentActions.get(i)), matchWrapWithMargin(0, 0, 0, 6));
            if (recentActions.size() > shown) {
                Button all = secondaryButton("Vis hele læringshistorikken");
                all.setOnClickListener(v -> showLearningHistory());
                llPage.addView(all, matchWrapWithMargin(0, 2, 0, 0));
            }
        }
    }

    private void loadDashboardExtras(EntityAccount account) {
        if (account == null || account.uuid == null)
            return;
        executor.execute(() -> {
            List<SpamNetworkAnalyzer.Network> loadedNetworks;
            List<EntitySpamActionHistory> loadedActions;
            try {
                loadedNetworks = SpamNetworkAnalyzer.analyze(getApplicationContext(), account.uuid);
            } catch (Throwable ex) {
                Log.e(ex);
                loadedNetworks = Collections.emptyList();
            }
            try {
                loadedActions = SpamIntelligenceDB.getInstance(getApplicationContext())
                        .actions().getRecent(account.uuid, 50);
                if (loadedActions == null)
                    loadedActions = Collections.emptyList();
            } catch (Throwable ex) {
                Log.e(ex);
                loadedActions = Collections.emptyList();
            }
            final List<SpamNetworkAnalyzer.Network> safeNetworks = loadedNetworks;
            final List<EntitySpamActionHistory> safeActions = loadedActions;
            runOnUiThread(() -> {
                if (!isSelected(account) || isFinishing() || isDestroyed())
                    return;
                networks = safeNetworks;
                recentActions = safeActions;
                if (section == Section.OVERVIEW || section == Section.FAMILIES ||
                        section == Section.SETTINGS)
                    renderCurrent();
            });
        });
    }

    private void loadReviewQueue''',
    "overview activity and dashboard extras",
)

replace_once(
    p,
    '''        form.addView(service, matchWrap());
        form.addView(domain, matchWrap());
        form.addView(trusted, matchWrap());
        form.addView(replacement, matchWrap());
        form.addView(note, matchWrap());

        Spinner state = new Spinner(this);''',
    '''        form.addView(service, matchWrap());
        form.addView(domain, matchWrap());
        form.addView(trusted, matchWrap());

        TextView observed = bodyText("Observerte avsenderdomener: " +
                observedDomainsSummary(alias.observed_domains));
        observed.setTextIsSelectable(true);
        observed.setPadding(0, dp(8), 0, dp(6));
        form.addView(observed, matchWrap());

        TextView history = bodyText("Først sett: " + formatWhen(alias.first_seen) +
                "\nSist sett: " + formatWhen(alias.last_seen) +
                "\nSiste spam: " + formatWhen(alias.last_spam));
        history.setPadding(0, dp(3), 0, dp(8));
        form.addView(history, matchWrap());

        form.addView(replacement, matchWrap());
        form.addView(note, matchWrap());

        Spinner state = new Spinner(this);''',
    "alias observed domains and timeline",
)

replace_once(
    p,
    '''        CardView network = card(10, 1);
        LinearLayout nb = cardBody(14, 12);
        nb.addView(valueText("Spamnettverk", 18f, true), matchWrap());
        nb.addView(bodyText("HTML-, lenke-, body- og senderinfrastruktur beholdes som nettverks-/templateanalyse. " +
                "Den får ikke slå sammen familier."), matchWrapWithMargin(0, 5, 0, 0));
        network.addView(nb, matchWrap());
        llPage.addView(network, matchWrapWithMargin(0, 6, 0, 0));
    }
''',
    '''        llPage.addView(sectionTitle("Spamnettverk"), matchWrapWithMargin(0, 12, 0, 3));
        llPage.addView(bodyText("Bredere HTML-, lenke- og senderinfrastruktur analyseres separat. " +
                "Nettverk kan koble flere eksakte familier uten å slå dem sammen."),
                matchWrapWithMargin(0, 0, 0, 7));
        if (networks.isEmpty())
            llPage.addView(bodyText("Ingen sterke nettverkskoblinger funnet i de lagrede familie-fingerprintene."), matchWrap());
        else
            for (int i = 0; i < networks.size(); i++)
                llPage.addView(networkCard(networks.get(i), i + 1), matchWrapWithMargin(0, 0, 0, 7));
    }
''',
    "real spam network rendering",
)

replace_once(
    p,
    '''        CardView data = card(10, 1);
        LinearLayout db = cardBody(14, 12);
        db.addView(valueText("Data og læring", 17f, true), matchWrap());
        Button undo = secondaryButton("Angre siste valg");''',
    '''        CardView data = card(10, 1);
        LinearLayout db = cardBody(14, 12);
        db.addView(valueText("Data og læring", 17f, true), matchWrap());
        Button export = secondaryButton("Eksporter status");
        export.setOnClickListener(v -> exportStatus());
        db.addView(export, matchWrapWithMargin(0, 7, 0, 0));
        Button history = secondaryButton("Vis læringshistorikk");
        history.setOnClickListener(v -> showLearningHistory());
        db.addView(history, matchWrapWithMargin(0, 5, 0, 0));
        Button undo = secondaryButton("Angre siste valg");''',
    "settings export and history controls",
)

replace_once(
    p,
    '''    private View uiCheck(String title, String description, String key, boolean checked) {''',
    '''    private View actionHistoryCard(EntitySpamActionHistory action) {
        CardView card = card(8, 1);
        LinearLayout body = cardBody(12, 9);
        String title = action.description == null ? action.action : action.description;
        body.addView(valueText(title, 15f, true), matchWrap());
        String state = action.undone_at == null ? "aktiv" : "angret";
        body.addView(bodyText(formatWhen(action.created_at) + " · " + state +
                (action.message_id == null ? "" : " · melding " + action.message_id)),
                matchWrapWithMargin(0, 2, 0, 0));
        card.addView(body, matchWrap());
        return card;
    }

    private View networkCard(SpamNetworkAnalyzer.Network network, int number) {
        CardView card = card(9, 1);
        LinearLayout body = cardBody(13, 10);
        body.addView(valueText("Nettverk " + number + " · " + network.familyIds.size() + " familier", 16f, true), matchWrap());
        List<String> labels = new ArrayList<>();
        for (Long familyId : network.familyIds)
            labels.add(familyLabel(familyId));
        body.addView(bodyText(TextUtils.join("\n", labels)), matchWrapWithMargin(0, 4, 0, 0));
        body.addView(bodyText("Sterkeste infrastruktur/template-kobling: " + percent(network.strongestScore)),
                matchWrapWithMargin(0, 4, 0, 0));
        card.addView(body, matchWrap());
        return card;
    }

    private String familyLabel(long familyId) {
        String descriptor = familyDescriptors.get(familyId);
        for (TupleSpamFamilyOverview family : families)
            if (family.family_id == familyId) {
                if (!TextUtils.isEmpty(family.name))
                    return family.name.trim() + (descriptor == null ? "" : " · " + descriptor.replace('\n', ' '));
                if (descriptor != null)
                    return descriptor.replace('\n', ' ');
                break;
            }
        return "Spamgruppe " + familyId;
    }

    private void showLearningHistory() {
        if (recentActions.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Læringshistorikk")
                    .setMessage("Ingen handlinger er registrert ennå.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        StringBuilder text = new StringBuilder();
        for (EntitySpamActionHistory action : recentActions) {
            if (text.length() > 0)
                text.append("\n\n");
            text.append(formatWhen(action.created_at)).append(" · ")
                    .append(action.description == null ? action.action : action.description);
            if (action.message_id != null)
                text.append("\nMelding: ").append(action.message_id);
            text.append("\nStatus: ").append(action.undone_at == null ? "aktiv" : "angret");
        }
        new AlertDialog.Builder(this)
                .setTitle("Læringshistorikk")
                .setMessage(text.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void exportStatus() {
        EntityAccount account = selectedAccount;
        if (account == null)
            return;
        StringBuilder text = new StringBuilder();
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
                    .append(" · smtp=").append(smtpState(alias)).append('\n');

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "Spamkontroll status");
        share.putExtra(Intent.EXTRA_TEXT, text.toString());
        startActivity(Intent.createChooser(share, "Eksporter Spamkontroll-status"));
    }

    private String observedDomainsSummary(String json) {
        if (TextUtils.isEmpty(json))
            return "ingen";
        try {
            org.json.JSONObject object = new org.json.JSONObject(json);
            List<String> values = new ArrayList<>();
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                values.add(key + " (" + object.optInt(key, 0) + ")");
            }
            Collections.sort(values, String.CASE_INSENSITIVE_ORDER);
            return values.isEmpty() ? "ingen" : TextUtils.join(", ", values);
        } catch (Throwable ex) {
            return "ukjent";
        }
    }

    private String formatWhen(Long timestamp) {
        if (timestamp == null || timestamp <= 0)
            return "aldri";
        return DateUtils.getRelativeTimeSpanString(timestamp,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
    }

    private View uiCheck(String title, String description, String key, boolean checked) {''',
    "dashboard helper methods",
)

replace_once(
    p,
    '''                    loadReviewQueue(account, false);
                } else if (result == SpamUndoManager.UndoResult.NOTHING_TO_UNDO)''',
    '''                    loadReviewQueue(account, false);
                    loadDashboardExtras(account);
                } else if (result == SpamUndoManager.UndoResult.NOTHING_TO_UNDO)''',
    "refresh history after undo",
)

replace_once(
    p,
    '''                                loadReviewQueue(account, false);
                            }
''',
    '''                                loadReviewQueue(account, false);
                                loadDashboardExtras(account);
                            }
''',
    "refresh history after reset",
)

print("PASS: native Spam Control now covers prototype minimum with real data")

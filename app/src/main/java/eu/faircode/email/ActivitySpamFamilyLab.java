package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.cardview.widget.CardView;
import androidx.lifecycle.LiveData;

import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import javax.mail.internet.InternetAddress;

/** Human-facing review and training surface for Spam Intelligence. */
public class ActivitySpamFamilyLab extends ActivityBase {
    private static final int CANDIDATE_LIMIT = 200;
    private static final ExecutorService executor =
            Helper.getBackgroundExecutor(1, "spam-control");

    private final AtomicLong candidateGeneration = new AtomicLong();

    private Spinner spAccount;
    private TextView tvStatus;
    private TextView tvReviewTitle;
    private TextView tvReviewHelp;
    private ScrollView svContent;
    private LinearLayout llGroups;
    private LinearLayout llCandidates;

    private List<EntityAccount> accounts = Collections.emptyList();
    private EntityAccount selectedAccount;
    private LiveData<List<TupleSpamFamilyOverview>> liveOverview;
    private Long selectedGroupId;
    private TupleSpamFamilyOverview selectedGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(buildUi());

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setSubtitle("Spamkontroll");
        }

        loadAccounts();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout accountRow = new LinearLayout(this);
        accountRow.setOrientation(LinearLayout.HORIZONTAL);
        accountRow.setGravity(Gravity.CENTER_VERTICAL);

        spAccount = new Spinner(this);
        accountRow.addView(spAccount, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button refresh = secondaryButton("Oppdater");
        refresh.setOnClickListener(v -> {
            EntityAccount account = selectedAccount;
            if (account != null && account.uuid != null) {
                tvStatus.setText("Oppdaterer spamtreff …");
                SpamFamilyRescorer.start(getApplicationContext());
                observeAccount(account);
            }
        });
        accountRow.addView(refresh, wrapWrap());
        root.addView(accountRow, matchWrap());

        tvStatus = new TextView(this);
        tvStatus.setPadding(0, dp(6), 0, dp(8));
        tvStatus.setText("Laster e-postkonto …");
        root.addView(tvStatus, matchWrap());

        LinearLayout historyRow = new LinearLayout(this);
        historyRow.setOrientation(LinearLayout.HORIZONTAL);
        historyRow.setPadding(0, 0, 0, dp(8));

        Button undo = secondaryButton("Angre siste valg");
        undo.setOnClickListener(v -> {
            EntityAccount account = selectedAccount;
            if (account != null)
                undoLatest(account);
        });
        historyRow.addView(undo, weightedButton());

        Button reset = secondaryButton("Nullstill all læring");
        reset.setOnClickListener(v -> {
            EntityAccount account = selectedAccount;
            if (account != null)
                showResetDialog(account);
        });
        historyRow.addView(reset, weightedButton());
        root.addView(historyRow, matchWrap());

        svContent = new ScrollView(this);
        svContent.setFillViewport(true);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(helpCard(), matchWrapWithMargin(0, 0, 0, 12));

        body.addView(sectionTitle("Spamgrupper"), matchWrap());
        TextView groupHelp = bodyText(
                "Velg en spamgruppe for å se meldingene som systemet mener hører sammen.");
        groupHelp.setPadding(0, dp(2), 0, dp(8));
        body.addView(groupHelp, matchWrap());

        llGroups = new LinearLayout(this);
        llGroups.setOrientation(LinearLayout.VERTICAL);
        body.addView(llGroups, matchWrap());

        tvReviewTitle = sectionTitle("Gjennomgå meldinger");
        tvReviewTitle.setPadding(0, dp(18), 0, 0);
        body.addView(tvReviewTitle, matchWrap());

        tvReviewHelp = bodyText(
                "Velg en spamgruppe. For hver melding trenger du bare å avgjøre: " +
                        "Samme spam, Annen spam eller Ikke spam.");
        tvReviewHelp.setPadding(0, dp(2), 0, dp(8));
        body.addView(tvReviewHelp, matchWrap());

        llCandidates = new LinearLayout(this);
        llCandidates.setOrientation(LinearLayout.VERTICAL);
        body.addView(llCandidates, matchWrap());
        showChooseGroupMessage();

        svContent.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(svContent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    private View helpCard() {
        CardView card = card(8, 2);
        LinearLayout content = cardBody(14, 12);

        TextView title = new TextView(this);
        title.setText("Hva skal jeg se på?");
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title, matchWrap());

        TextView text = bodyText(
                "EMNE · AVSENDERNAVN · AVSENDERADRESSE · ALIAS\n\n" +
                        "Dette er hovedsignalene. Velg deretter Samme spam, Annen spam eller Ikke spam. " +
                        "Alle valg kan angres.");
        text.setPadding(0, dp(6), 0, 0);
        content.addView(text, matchWrap());
        card.addView(content, matchWrap());
        return card;
    }


    private void undoLatest(EntityAccount account) {
        if (account == null || account.uuid == null)
            return;
        final int scrollY = svContent == null ? 0 : svContent.getScrollY();
        tvStatus.setText("Angrer siste valg …");
        executor.execute(() -> {
            SpamUndoManager.UndoResult result = SpamUndoManager.undoLatest(
                    getApplicationContext(), account.uuid);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !isSelected(account))
                    return;
                if (result == SpamUndoManager.UndoResult.APPLIED) {
                    tvStatus.setText("Siste valg er angret.");
                    if (selectedGroup != null && selectedGroupId != null)
                        loadCandidates(account, selectedGroup, scrollY, false);
                } else if (result == SpamUndoManager.UndoResult.NOTHING_TO_UNDO)
                    tvStatus.setText("Det er ingen flere valg å angre.");
                else
                    tvStatus.setText("Kunne ikke angre siste valg.");
            });
        });
    }

    private void showResetDialog(EntityAccount account) {
        new AlertDialog.Builder(this)
                .setTitle("Nullstill all læring?")
                .setMessage("Dette nullstiller all spamlæring i Spamkontroll på alle e-postkontoene " +
                        "i denne testappen. Spam/ikke-spam-valg, spamgrupper, modelltreff og lærte " +
                        "spam-tellere blir blanke ark.\n\n" +
                        "E-postene dine, aliasadressene, forventede og godkjente domener, replacement-status " +
                        "og SMTP/cPanel-regler blir ikke rørt. Hele nullstillingen kan angres med " +
                        "Angre siste valg.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Nullstill", (dialog, which) -> performReset(account))
                .show();
    }

    private void performReset(EntityAccount account) {
        if (account == null || account.uuid == null)
            return;
        final int scrollY = svContent == null ? 0 : svContent.getScrollY();
        tvStatus.setText("Nullstiller Spamkontroll …");
        executor.execute(() -> {
            boolean reset = SpamResetManager.resetLearning(
                    getApplicationContext(), account.uuid);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !isSelected(account))
                    return;
                if (reset) {
                    selectedGroupId = null;
                    selectedGroup = null;
                    candidateGeneration.incrementAndGet();
                    showChooseGroupMessage();
                    tvStatus.setText("All spamlæring er nullstilt. Du har blanke ark.");
                    Snackbar.make(svContent,
                                    "All spamlæring er nullstilt.",
                                    Snackbar.LENGTH_LONG)
                            .setAction("ANGRE", v -> undoLatest(account))
                            .show();
                    if (svContent != null)
                        svContent.post(() -> svContent.scrollTo(0, scrollY));
                } else
                    tvStatus.setText("Kunne ikke nullstille læringen.");
            });
        });
    }

    private void loadAccounts() {
        executor.execute(() -> {
            List<EntityAccount> loaded;
            try {
                loaded = DB.getInstance(getApplicationContext()).account().getAccounts();
                if (loaded == null)
                    loaded = Collections.emptyList();
            } catch (Throwable ex) {
                Log.e(ex);
                loaded = Collections.emptyList();
            }

            List<EntityAccount> safe = new ArrayList<>();
            for (EntityAccount account : loaded)
                if (account != null && account.uuid != null)
                    safe.add(account);

            runOnUiThread(() -> bindAccounts(safe));
        });
    }

    private void bindAccounts(List<EntityAccount> loaded) {
        if (isFinishing() || isDestroyed())
            return;

        accounts = loaded;
        List<String> labels = new ArrayList<>();
        for (EntityAccount account : accounts)
            labels.add(accountLabel(account));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spAccount.setAdapter(adapter);
        spAccount.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                       int position, long id) {
                if (position >= 0 && position < accounts.size())
                    observeAccount(accounts.get(position));
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        if (accounts.isEmpty()) {
            tvStatus.setText("Ingen FairEmail-konto funnet i denne testappen.");
            llGroups.removeAllViews();
            showChooseGroupMessage();
        }
    }

    private void observeAccount(EntityAccount account) {
        boolean accountChanged = selectedAccount == null ||
                selectedAccount.uuid == null ||
                !selectedAccount.uuid.equals(account.uuid);
        selectedAccount = account;

        if (accountChanged) {
            selectedGroupId = null;
            selectedGroup = null;
            candidateGeneration.incrementAndGet();
            showChooseGroupMessage();
        }

        if (liveOverview != null)
            liveOverview.removeObservers(this);

        liveOverview = SpamFamilyLabRepository.liveOverview(this, account.uuid);
        if (liveOverview == null) {
            tvStatus.setText("Kunne ikke åpne spamdata for " + accountLabel(account));
            return;
        }

        tvStatus.setText("Konto: " + accountLabel(account));
        liveOverview.observe(this, families -> renderGroups(account, families));
    }

    private void renderGroups(EntityAccount account, List<TupleSpamFamilyOverview> groups) {
        if (!isSelected(account))
            return;

        llGroups.removeAllViews();
        if (groups == null || groups.isEmpty()) {
            TextView empty = bodyText(
                    "Ingen spamgrupper ennå. Når du markerer en melding som spam i FairEmail, " +
                            "lærer Spamkontroll den og begynner å lete etter lignende meldinger.");
            empty.setPadding(0, dp(6), 0, dp(10));
            llGroups.addView(empty, matchWrap());
            tvStatus.setText("Ingen innlærte spamgrupper ennå.");
            selectedGroupId = null;
            selectedGroup = null;
            showChooseGroupMessage();
            return;
        }

        int confirmed = 0;
        int review = 0;
        int possibleErrors = 0;
        TupleSpamFamilyOverview stillSelected = null;
        for (TupleSpamFamilyOverview group : groups) {
            confirmed += group.confirmed_count;
            review += group.strong_unknown_count;
            possibleErrors += group.strong_ham_count;
            if (selectedGroupId != null && group.family_id == selectedGroupId)
                stillSelected = group;
            llGroups.addView(groupCard(account, group),
                    matchWrapWithMargin(0, 0, 0, 8));
        }

        selectedGroup = stillSelected;
        if (selectedGroupId != null && stillSelected == null) {
            selectedGroupId = null;
            showChooseGroupMessage();
        }

        StringBuilder status = new StringBuilder();
        status.append(groups.size()).append(groups.size() == 1 ? " spamgruppe" : " spamgrupper")
                .append(" · ").append(confirmed).append(" bekreftet spam")
                .append(" · ").append(review).append(" sterke treff å kontrollere");
        if (possibleErrors > 0)
            status.append(" · ⚠ ").append(possibleErrors).append(" mulig feil");
        tvStatus.setText(status.toString());
    }

    private View groupCard(EntityAccount account, TupleSpamFamilyOverview group) {
        CardView card = card(9, selectedGroupId != null && group.family_id == selectedGroupId ? 5 : 2);
        LinearLayout content = cardBody(14, 12);

        TextView title = new TextView(this);
        title.setText(groupName(group));
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title, matchWrap());

        TextView summary = bodyText(
                group.confirmed_count + " bekreftet spam · " +
                        group.strong_unknown_count + " sterke nye treff");
        summary.setPadding(0, dp(4), 0, 0);
        content.addView(summary, matchWrap());

        if (group.strong_ham_count > 0) {
            TextView warning = bodyText(
                    "⚠ " + group.strong_ham_count +
                            " melding du har merket som ikke-spam ligner denne gruppen. Kontroller den.");
            warning.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            warning.setPadding(0, dp(5), 0, 0);
            content.addView(warning, matchWrap());
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);

        Button review = actionButton("Se meldinger");
        review.setOnClickListener(v -> selectGroup(account, group));
        actions.addView(review, weightedButton());

        Button rename = actionButton("Gi navn");
        rename.setOnClickListener(v -> showRenameDialog(group));
        actions.addView(rename, weightedButton());

        Button rescore = actionButton("Finn flere");
        rescore.setOnClickListener(v -> {
            SpamFamilyLabRepository.requestRescore(
                    getApplicationContext(), account.uuid, group.family_id);
            tvStatus.setText("Leter etter flere meldinger som ligner " + groupName(group) + " …");
        });
        actions.addView(rescore, weightedButton());
        content.addView(actions, matchWrap());

        card.addView(content, matchWrap());
        card.setOnClickListener(v -> selectGroup(account, group));
        return card;
    }

    private void selectGroup(EntityAccount account, TupleSpamFamilyOverview group) {
        selectedGroupId = group.family_id;
        selectedGroup = group;
        tvReviewTitle.setText("Gjennomgå · " + groupName(group));
        tvReviewHelp.setText(
                "Se først på emne, avsendernavn, avsenderadresse og alias. " +
                        "Det er hovedsignalene. Velg deretter hva meldingen er.");
        loadCandidates(account, group, null, true);
        svContent.post(() -> svContent.smoothScrollTo(0, Math.max(0, tvReviewTitle.getTop() - dp(8))));
    }

    private void showRenameDialog(TupleSpamFamilyOverview group) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("For eksempel: Akusoli-spam");
        if (group.name != null)
            input.setText(group.name);
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
                .setTitle("Gi spamgruppen et navn")
                .setMessage("Navnet er kun for at du lettere skal kjenne den igjen.")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Lagre", (dialog, which) -> {
                    String name = input.getText().toString();
                    EntityAccount account = selectedAccount;
                    if (account == null || account.uuid == null)
                        return;
                    executor.execute(() -> {
                        boolean changed = SpamUndoManager.renameFamily(
                                getApplicationContext(), account.uuid, group.family_id, name);
                        runOnUiThread(() -> {
                            if (changed) {
                                tvStatus.setText("Navnet er lagret.");
                                Snackbar.make(svContent, "Navnet er lagret.", Snackbar.LENGTH_LONG)
                                        .setAction("ANGRE", v -> undoLatest(account))
                                        .show();
                            } else
                                tvStatus.setText("Kunne ikke endre navnet.");
                        });
                    });
                })
                .show();
    }

    private void loadCandidates(EntityAccount account,
                                TupleSpamFamilyOverview group,
                                Integer restoreScrollY,
                                boolean showLoading) {
        final long generation = candidateGeneration.incrementAndGet();
        if (showLoading) {
            llCandidates.removeAllViews();
            TextView loading = bodyText("Laster meldinger …");
            loading.setPadding(0, dp(6), 0, dp(8));
            llCandidates.addView(loading, matchWrap());
        }

        executor.execute(() -> {
            List<SpamFamilyLabRepository.Candidate> candidates;
            try {
                candidates = SpamFamilyLabRepository.getCandidates(
                        getApplicationContext(), account.uuid,
                        group.family_id, CANDIDATE_LIMIT);
            } catch (Throwable ex) {
                Log.e(ex);
                candidates = Collections.emptyList();
            }
            final List<SpamFamilyLabRepository.Candidate> result = candidates;
            runOnUiThread(() -> {
                if (generation != candidateGeneration.get() ||
                        isFinishing() || isDestroyed() || !isSelected(account) ||
                        selectedGroupId == null || selectedGroupId != group.family_id)
                    return;
                renderCandidates(account, group, result);
                if (restoreScrollY != null)
                    svContent.post(() -> svContent.scrollTo(0, restoreScrollY));
            });
        });
    }

    private void renderCandidates(EntityAccount account,
                                  TupleSpamFamilyOverview group,
                                  List<SpamFamilyLabRepository.Candidate> candidates) {
        llCandidates.removeAllViews();

        TextView count = bodyText(candidates.size() +
                (candidates.size() == 1 ? " melding" : " meldinger") + " vist");
        count.setPadding(0, 0, 0, dp(6));
        llCandidates.addView(count, matchWrap());

        if (candidates.isEmpty()) {
            TextView empty = bodyText("Ingen lokale meldinger å kontrollere i denne spamgruppen.");
            llCandidates.addView(empty, matchWrap());
            return;
        }

        for (SpamFamilyLabRepository.Candidate candidate : candidates)
            llCandidates.addView(candidateCard(account, group, candidate),
                    matchWrapWithMargin(0, 0, 0, 10));
    }

    private View candidateCard(EntityAccount account,
                               TupleSpamFamilyOverview group,
                               SpamFamilyLabRepository.Candidate candidate) {
        CardView card = card(10, 3);
        LinearLayout content = cardBody(14, 13);

        TextView state = new TextView(this);
        state.setText(messageState(candidate));
        state.setTextSize(13f);
        state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(state, matchWrap());

        TextView subjectLabel = fieldLabel("EMNE");
        subjectLabel.setPadding(0, dp(10), 0, 0);
        content.addView(subjectLabel, matchWrap());

        TextView subject = new TextView(this);
        subject.setText(empty(candidate.subject, "(uten emne)"));
        subject.setTextSize(20f);
        subject.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        subject.setTextIsSelectable(true);
        content.addView(subject, matchWrap());

        SenderParts sender = senderParts(candidate.sender);
        content.addView(fieldLabel("AVSENDERNAVN"), matchWrapWithMargin(0, 10, 0, 0));
        TextView senderName = valueText(empty(sender.name, "(uten navn)"), 17f, true);
        content.addView(senderName, matchWrap());

        content.addView(fieldLabel("AVSENDERADRESSE"), matchWrapWithMargin(0, 8, 0, 0));
        TextView senderAddress = valueText(
                empty(sender.address, candidate.senderDomain == null ? "(ukjent)" : candidate.senderDomain),
                16f, false);
        senderAddress.setTypeface(Typeface.MONOSPACE);
        senderAddress.setTextIsSelectable(true);
        content.addView(senderAddress, matchWrap());

        CardView aliasCard = card(7, 1);
        LinearLayout aliasBody = cardBody(11, 10);
        aliasBody.addView(fieldLabel("MOTTATT PÅ ALIAS"), matchWrap());
        TextView alias = valueText(empty(candidate.alias, "(ukjent alias)"), 17f, true);
        alias.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        alias.setTextIsSelectable(true);
        aliasBody.addView(alias, matchWrap());

        TextView aliasSignal = bodyText(aliasSignal(candidate));
        aliasSignal.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        aliasSignal.setPadding(0, dp(6), 0, 0);
        aliasBody.addView(aliasSignal, matchWrap());

        String domainLine = aliasDomainLine(candidate);
        if (domainLine != null) {
            TextView domains = bodyText(domainLine);
            domains.setPadding(0, dp(3), 0, 0);
            domains.setTextIsSelectable(true);
            aliasBody.addView(domains, matchWrap());
        }
        aliasCard.addView(aliasBody, matchWrap());
        content.addView(aliasCard, matchWrapWithMargin(0, 12, 0, 0));

        TextView when = bodyText(DateUtils.getRelativeDateTimeString(
                this,
                candidate.received,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.WEEK_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE).toString());
        when.setTextSize(12f);
        when.setPadding(0, dp(7), 0, 0);
        content.addView(when, matchWrap());

        Button details = secondaryButton("Detaljer");
        details.setOnClickListener(v -> showTechnicalDetails(group, candidate));
        content.addView(details, wrapWrapWithMargin(0, 8, 0, 0));

        if (!candidate.messagePresent) {
            TextView unavailable = bodyText(
                    "Meldingen finnes ikke lenger lokalt. Derfor kan den ikke brukes til læring nå.");
            unavailable.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            unavailable.setPadding(0, dp(10), 0, 0);
            content.addView(unavailable, matchWrap());
        } else {
            LinearLayout actions = new LinearLayout(this);
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
            content.addView(actions, matchWrap());
        }

        card.addView(content, matchWrap());
        return card;
    }

    private void runCandidateAction(EntityAccount account,
                                    TupleSpamFamilyOverview group,
                                    SpamFamilyLabRepository.Candidate candidate,
                                    String pendingText,
                                    String successText,
                                    Callable<SpamFamilyLabRepository.ActionResult> action) {
        if (!isSelected(account))
            return;

        final int scrollY = svContent.getScrollY();
        tvStatus.setText(pendingText);
        executor.execute(() -> {
            SpamFamilyLabRepository.ActionResult result;
            try {
                result = action.call();
            } catch (Throwable ex) {
                Log.e(ex);
                result = SpamFamilyLabRepository.ActionResult.REJECTED;
            }
            SpamFamilyLabRepository.ActionResult finalResult = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !isSelected(account))
                    return;

                if (finalResult == SpamFamilyLabRepository.ActionResult.APPLIED) {
                    tvStatus.setText(successText);
                    Snackbar.make(svContent, successText, Snackbar.LENGTH_LONG)
                            .setAction("ANGRE", v -> undoLatest(account))
                            .show();
                } else
                    tvStatus.setText(actionFailure(finalResult));

                if (selectedGroupId != null && selectedGroupId == group.family_id)
                    loadCandidates(account, group, scrollY, false);
            });
        });
    }

    private void showTechnicalDetails(TupleSpamFamilyOverview group,
                                      SpamFamilyLabRepository.Candidate candidate) {
        StringBuilder text = new StringBuilder();
        text.append("Spamgruppe: ").append(groupName(group)).append('\n')
                .append("Meldings-ID: ").append(candidate.messageId).append('\n')
                .append("Status: ").append(messageState(candidate)).append('\n')
                .append("Alias spam/ikke-spam historikk: ")
                .append(candidate.aliasSpamHits).append(" / ").append(candidate.aliasHamHits).append('\n')
                .append("Alias spamstøtte: ").append(percent(candidate.aliasSpamSupport)).append('\n')
                .append("Alias legitimitetsstøtte: ").append(percent(candidate.aliasHamSupport)).append('\n')
                .append("Aliasvurdering: ").append(empty(candidate.aliasVerdict, "ukjent")).append('\n')
                .append("Aliasårsaker: ").append(empty(candidate.aliasReasons, "ingen")).append("\n\n")
                .append("SAMLET VURDERING\n")
                .append("Spamstøtte: ").append(percent(candidate.overallSpamSupport)).append('\n')
                .append("Legitimitetsstøtte: ").append(percent(candidate.overallHamSupport)).append('\n')
                .append("Resultat: ").append(candidate.overallVerdict).append('\n')
                .append("Årsaker: ").append(candidate.overallReasons.isEmpty()
                        ? "ingen" : android.text.TextUtils.join(", ", candidate.overallReasons))
                .append("\n\n")
                .append("Spamgruppelikhet: ").append(percent(candidate.score)).append('\n')
                .append("Tekst: ").append(percent(candidate.text)).append('\n')
                .append("Struktur: ").append(percent(candidate.structure)).append('\n')
                .append("Lenker: ").append(percent(candidate.links)).append('\n')
                .append("Avsender: ").append(percent(candidate.senderScore)).append('\n')
                .append("Unsubscribe: ").append(candidate.hasUnsubscribe ? "ja" : "nei")
                .append("\n\nForhåndsvisning:\n")
                .append(empty(candidate.preview, "(ingen)"));

        new AlertDialog.Builder(this)
                .setTitle("Tekniske detaljer")
                .setMessage(text.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String messageState(SpamFamilyLabRepository.Candidate candidate) {
        if (candidate.explicitHam)
            return "✓ Du har merket denne som IKKE SPAM";
        if (candidate.confirmedThisFamily)
            return "✓ Bekreftet spam i denne gruppen";
        if (candidate.confirmedFamilyId != null)
            return "✓ Bekreftet spam i en annen spamgruppe";
        if (candidate.overallVerdict == SpamDecisionScorer.Verdict.SUSPICIOUS)
            return "⚠ Høy spamrisiko · trenger din kontroll";
        if (candidate.overallVerdict == SpamDecisionScorer.Verdict.LIKELY_LEGIT)
            return "✓ Sterke legitimitetssignaler · kontroller før du endrer";
        return "Trenger din vurdering";
    }

    private String aliasSignal(SpamFamilyLabRepository.Candidate candidate) {
        if (EntityAlias.STATE_COMPROMISED == candidate.aliasState)
            return "⚠ Dette aliaset er allerede markert som kompromittert";
        if ("SUSPICIOUS".equals(candidate.aliasVerdict))
            return "⚠ Avsenderen passer dårlig med dette aliaset";
        if ("LIKELY_LEGIT".equals(candidate.aliasVerdict))
            return "✓ Avsenderen passer godt med dette aliaset";
        if (candidate.expectedDomains != null && candidate.senderDomain != null)
            return "Aliasforholdet er ikke avgjort ennå";
        if (candidate.aliasSpamHits > 0)
            return "⚠ Dette aliaset har tidligere mottatt bekreftet spam";
        return "Aliasforholdet lærer fortsatt";
    }

    private String aliasDomainLine(SpamFamilyLabRepository.Candidate candidate) {
        String expected = candidate.expectedDomains;
        String actual = candidate.senderDomain;
        if (expected == null && actual == null)
            return null;
        if (expected != null && actual != null)
            return "Forventet: " + expected + "\nFaktisk avsenderdomene: " + actual;
        if (expected != null)
            return "Forventet avsenderdomene: " + expected;
        return "Faktisk avsenderdomene: " + actual;
    }

    private SenderParts senderParts(String raw) {
        if (raw == null || raw.trim().isEmpty())
            return new SenderParts(null, null);
        try {
            InternetAddress[] parsed = InternetAddress.parseHeader(raw, false);
            if (parsed != null && parsed.length > 0) {
                InternetAddress first = parsed[0];
                return new SenderParts(first.getPersonal(), first.getAddress());
            }
        } catch (Throwable ex) {
            Log.w(ex);
        }

        int lt = raw.lastIndexOf('<');
        int gt = raw.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            String name = raw.substring(0, lt).trim();
            String address = raw.substring(lt + 1, gt).trim();
            if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 1)
                name = name.substring(1, name.length() - 1);
            return new SenderParts(name, address);
        }
        return raw.contains("@") ? new SenderParts(null, raw.trim()) : new SenderParts(raw.trim(), null);
    }

    private void showChooseGroupMessage() {
        if (llCandidates == null)
            return;
        llCandidates.removeAllViews();
        TextView text = bodyText("Velg en spamgruppe ovenfor for å se meldingene.");
        text.setPadding(0, dp(6), 0, dp(16));
        llCandidates.addView(text, matchWrap());
        if (tvReviewTitle != null)
            tvReviewTitle.setText("Gjennomgå meldinger");
    }

    private String actionFailure(SpamFamilyLabRepository.ActionResult result) {
        if (result == SpamFamilyLabRepository.ActionResult.MESSAGE_MISSING)
            return "Kunne ikke lagre: meldingen finnes ikke lenger lokalt.";
        if (result == SpamFamilyLabRepository.ActionResult.ACCOUNT_MISMATCH)
            return "Kunne ikke lagre: meldingen tilhører en annen konto.";
        if (result == SpamFamilyLabRepository.ActionResult.FAMILY_MISSING)
            return "Kunne ikke lagre: spamgruppen finnes ikke lenger.";
        return "Kunne ikke lagre endringen. Ingen vurdering ble antatt som lagret.";
    }

    private boolean isSelected(EntityAccount account) {
        return selectedAccount != null && account != null &&
                selectedAccount.uuid != null &&
                selectedAccount.uuid.equals(account.uuid);
    }

    private String groupName(TupleSpamFamilyOverview group) {
        if (group.name != null && !group.name.trim().isEmpty())
            return group.name.trim();
        return "Spamgruppe " + group.family_id;
    }

    private String accountLabel(EntityAccount account) {
        if (account == null)
            return "konto";
        if (account.name != null && !account.name.trim().isEmpty())
            return account.name.trim();
        if (account.user != null && !account.user.trim().isEmpty())
            return account.user.trim();
        return account.host == null ? account.uuid : account.host;
    }

    private CardView card(int radiusDp, int elevationDp) {
        CardView card = new CardView(this);
        card.setUseCompatPadding(true);
        card.setRadius(dp(radiusDp));
        card.setCardElevation(dp(elevationDp));
        return card;
    }

    private LinearLayout cardBody(int horizontalDp, int verticalDp) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(horizontalDp), dp(verticalDp), dp(horizontalDp), dp(verticalDp));
        return content;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(21f);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView fieldLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(11f);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView valueText(String text, float size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        if (bold)
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView bodyText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14f);
        return view;
    }

    private Button actionButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        return button;
    }

    private Button primaryActionButton(String text) {
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

    private String empty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String percent(Double value) {
        if (value == null || value < 0)
            return "ukjent";
        return String.format(Locale.ROOT, "%.0f %%", value * 100.0);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightedButton() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams matchWrapWithMargin(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams wrapWrapWithMargin(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = wrapWrap();
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private static final class SenderParts {
        final String name;
        final String address;

        SenderParts(String name, String address) {
            this.name = name;
            this.address = address;
        }
    }
}

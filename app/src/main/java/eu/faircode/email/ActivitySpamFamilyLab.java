package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.cardview.widget.CardView;
import androidx.lifecycle.LiveData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Debug-build observer console for learned spam families.
 *
 * This activity intentionally exposes no automatic move/delete/burn action.
 * It is an instrumentation surface for measuring family quality on real mail.
 */
public class ActivitySpamFamilyLab extends ActivityBase {
    private static final int CANDIDATE_LIMIT = 200;
    private static final ExecutorService executor =
            Helper.getBackgroundExecutor(1, "spam-family-lab");

    private final AtomicLong candidateGeneration = new AtomicLong();

    private Spinner spAccount;
    private TextView tvStatus;
    private LinearLayout llFamilies;
    private LinearLayout llCandidates;

    private List<EntityAccount> accounts = Collections.emptyList();
    private EntityAccount selectedAccount;
    private LiveData<List<TupleSpamFamilyOverview>> liveOverview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setSubtitle("Spam Family Lab");

        setContentView(buildUi());
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
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView intro = new TextView(this);
        intro.setText("Observer-only. Strong matches are evidence, not automatic spam actions.");
        intro.setPadding(0, 0, 0, dp(8));
        root.addView(intro, matchWrap());

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        spAccount = new Spinner(this);
        controls.addView(spAccount, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnRefresh = new Button(this);
        btnRefresh.setText("Refresh");
        btnRefresh.setOnClickListener(v -> {
            EntityAccount account = selectedAccount;
            if (account != null && account.uuid != null) {
                tvStatus.setText("Refreshing family observations…");
                SpamFamilyRescorer.start(getApplicationContext());
                observeAccount(account);
            }
        });
        controls.addView(btnRefresh, wrapWrap());
        root.addView(controls, matchWrap());

        tvStatus = new TextView(this);
        tvStatus.setPadding(0, dp(6), 0, dp(8));
        tvStatus.setText("Loading accounts…");
        root.addView(tvStatus, matchWrap());

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        TextView familyCaption = caption("Families");
        body.addView(familyCaption, matchWrap());
        llFamilies = new LinearLayout(this);
        llFamilies.setOrientation(LinearLayout.VERTICAL);
        body.addView(llFamilies, matchWrap());

        TextView candidateCaption = caption("Selected family candidates");
        candidateCaption.setPadding(0, dp(18), 0, dp(6));
        body.addView(candidateCaption, matchWrap());
        llCandidates = new LinearLayout(this);
        llCandidates.setOrientation(LinearLayout.VERTICAL);
        body.addView(llCandidates, matchWrap());

        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
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
            tvStatus.setText("No FairEmail accounts found.");
            llFamilies.removeAllViews();
            llCandidates.removeAllViews();
        }
    }

    private void observeAccount(EntityAccount account) {
        selectedAccount = account;
        candidateGeneration.incrementAndGet();
        llCandidates.removeAllViews();

        if (liveOverview != null)
            liveOverview.removeObservers(this);

        liveOverview = SpamFamilyLabRepository.liveOverview(this, account.uuid);
        if (liveOverview == null) {
            tvStatus.setText("Could not open family data for " + accountLabel(account));
            return;
        }

        tvStatus.setText("Watching " + accountLabel(account) +
                " · strong threshold " + score(SpamFamilyLabRepository.STRONG_THRESHOLD));
        liveOverview.observe(this, families -> renderFamilies(account, families));
    }

    private void renderFamilies(EntityAccount account, List<TupleSpamFamilyOverview> families) {
        if (selectedAccount == null || account.uuid == null ||
                !account.uuid.equals(selectedAccount.uuid))
            return;

        llFamilies.removeAllViews();
        if (families == null || families.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No learned spam families yet. Marking a message as spam will create or extend one.");
            llFamilies.addView(empty, matchWrap());
            return;
        }

        int totalStrongHam = 0;
        for (TupleSpamFamilyOverview family : families) {
            totalStrongHam += family.strong_ham_count;
            llFamilies.addView(familyCard(account, family), matchWrapWithMargin(0, 0, 0, 8));
        }

        String warning = totalStrongHam == 0
                ? "No strong HAM canaries in " + families.size() + " families."
                : "⚠ " + totalStrongHam + " strong HAM canary match(es) across " +
                families.size() + " families.";
        tvStatus.setText(accountLabel(account) + " · " + warning);
    }

    private View familyCard(EntityAccount account, TupleSpamFamilyOverview family) {
        CardView card = new CardView(this);
        card.setUseCompatPadding(true);
        card.setRadius(dp(6));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView title = caption(familyName(family));
        content.addView(title, matchWrap());

        TextView stats = new TextView(this);
        StringBuilder text = new StringBuilder();
        text.append("Confirmed ").append(family.confirmed_count)
                .append(" · exemplars ").append(family.exemplar_count)
                .append(" · predicted ").append(family.predicted_count)
                .append(" · strong ").append(family.strong_count)
                .append("\nStrong unknown ").append(family.strong_unknown_count)
                .append(" · HAM canary ").append(family.strong_ham_count)
                .append(" · max ").append(score(family.max_score));
        if (family.rescore_processed != null)
            text.append("\nRescore running: ")
                    .append(family.rescore_processed)
                    .append(" processed · ")
                    .append(family.rescore_matches == null ? 0 : family.rescore_matches)
                    .append(" threshold matches");
        stats.setText(text.toString());
        stats.setPadding(0, dp(4), 0, dp(6));
        content.addView(stats, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button inspect = new Button(this);
        inspect.setText("Inspect");
        inspect.setOnClickListener(v -> loadCandidates(account, family));
        actions.addView(inspect, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button rescore = new Button(this);
        rescore.setText("Rescore");
        rescore.setOnClickListener(v -> {
            SpamFamilyLabRepository.requestRescore(
                    getApplicationContext(), account.uuid, family.family_id);
            tvStatus.setText("Queued rescore for " + familyName(family));
        });
        actions.addView(rescore, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(actions, matchWrap());

        card.addView(content, matchWrap());
        card.setOnClickListener(v -> loadCandidates(account, family));
        return card;
    }

    private void loadCandidates(EntityAccount account, TupleSpamFamilyOverview family) {
        final long generation = candidateGeneration.incrementAndGet();
        llCandidates.removeAllViews();
        TextView loading = new TextView(this);
        loading.setText("Loading " + familyName(family) + " candidates…");
        llCandidates.addView(loading, matchWrap());

        executor.execute(() -> {
            List<SpamFamilyLabRepository.Candidate> candidates;
            try {
                candidates = SpamFamilyLabRepository.getCandidates(
                        getApplicationContext(), account.uuid,
                        family.family_id, CANDIDATE_LIMIT);
            } catch (Throwable ex) {
                Log.e(ex);
                candidates = Collections.emptyList();
            }
            final List<SpamFamilyLabRepository.Candidate> result = candidates;
            runOnUiThread(() -> {
                if (generation != candidateGeneration.get() || isFinishing() || isDestroyed())
                    return;
                renderCandidates(family, result);
            });
        });
    }

    private void renderCandidates(TupleSpamFamilyOverview family,
                                  List<SpamFamilyLabRepository.Candidate> candidates) {
        llCandidates.removeAllViews();

        TextView heading = caption(familyName(family) + " · " + candidates.size() + " shown");
        llCandidates.addView(heading, matchWrapWithMargin(0, 0, 0, 6));

        if (candidates.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No locally indexed candidates for this family.");
            llCandidates.addView(empty, matchWrap());
            return;
        }

        for (SpamFamilyLabRepository.Candidate candidate : candidates) {
            TextView row = new TextView(this);
            row.setText(candidateText(candidate));
            row.setTextIsSelectable(true);
            row.setPadding(dp(10), dp(9), dp(10), dp(9));
            llCandidates.addView(row, matchWrapWithMargin(0, 0, 0, 4));
        }
    }

    private String candidateText(SpamFamilyLabRepository.Candidate c) {
        String label;
        if (c.explicitHam)
            label = "⚠ HAM CANARY";
        else if (c.confirmedThisFamily)
            label = "✓ CONFIRMED SPAM";
        else if (c.strong)
            label = "STRONG OBSERVER MATCH";
        else
            label = "observer match";

        String subject = c.subject == null ? "(message unavailable or no subject)" : c.subject;
        String sender = c.sender == null ? "(sender unavailable)" : c.sender;
        String preview = c.preview == null ? "" : "\n" + c.preview;

        return label + " · score " + score(c.score) + " · msg " + c.messageId +
                "\n" + subject +
                "\n" + sender + preview +
                "\ntext " + score(c.text) +
                " · structure " + score(c.structure) +
                " · links " + score(c.links) +
                " · sender " + score(c.senderScore) +
                "\nalias " + c.alias +
                (c.senderDomain == null ? "" : " · sender domain " + c.senderDomain);
    }

    private String familyName(TupleSpamFamilyOverview family) {
        if (family.name != null && !family.name.trim().isEmpty())
            return family.name.trim() + " · Family #" + family.family_id;
        return "Family #" + family.family_id;
    }

    private String accountLabel(EntityAccount account) {
        if (account == null)
            return "account";
        if (account.name != null && !account.name.trim().isEmpty())
            return account.name.trim();
        if (account.user != null && !account.user.trim().isEmpty())
            return account.user.trim();
        return account.host == null ? account.uuid : account.host;
    }

    private TextView caption(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(18f);
        return view;
    }

    private String score(Double value) {
        if (value == null || value < 0)
            return "—";
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String score(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
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

    private LinearLayout.LayoutParams matchWrapWithMargin(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }
}

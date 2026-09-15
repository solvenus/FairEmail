package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.cardview.widget.CardView;
import androidx.lifecycle.LiveData;

import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.mail.Address;
import javax.mail.internet.InternetAddress;

/**
 * Real human-facing Spam Control dashboard.
 *
 * Design invariant: everything should be controllable, but nothing unnecessary
 * should be required in the normal workflow.
 */
public class ActivitySpamControl extends ActivityBase {
    private static final int REVIEW_LIMIT = 250;
    private static final ExecutorService executor =
            Helper.getBackgroundExecutor(1, "spam-control-dashboard");

    private enum Section {
        OVERVIEW("Oversikt"),
        REVIEW("Gjennomgang"),
        ALIASES("Aliaser"),
        FAMILIES("Spamgrupper"),
        RULES("Regler"),
        SETTINGS("Innstillinger");

        final String title;
        Section(String title) { this.title = title; }
    }

    private final AtomicLong reviewGeneration = new AtomicLong();
    private final AtomicBoolean reviewActionRunning = new AtomicBoolean(false);
    private final Map<Section, Button> navButtons = new EnumMap<>(Section.class);
    private final Map<Long, String> familyDescriptors = new HashMap<>();
    private List<SpamNetworkAnalyzer.Network> networks = Collections.emptyList();
    private List<EntitySpamActionHistory> recentActions = Collections.emptyList();
    private SpamControlAttentionStats.Stats attentionStats = SpamControlAttentionStats.EMPTY;

    private Spinner spAccount;
    private TextView tvStatus;
    private ScrollView svContent;
    private LinearLayout llPage;

    private List<EntityAccount> accounts = Collections.emptyList();
    private EntityAccount selectedAccount;
    private Section section = Section.OVERVIEW;

    private LiveData<List<TupleSpamFamilyOverview>> liveFamilies;
    private LiveData<List<EntityAlias>> liveAliases;
    private List<TupleSpamFamilyOverview> families = Collections.emptyList();
    private List<EntityAlias> aliases = Collections.emptyList();

    private List<SpamFamilyLabRepository.Candidate> reviewQueue = new ArrayList<>();
    private int aliasBucketSelection = 0; // 0=spam/problem, 1=legitimate, 2=unresolved
    private int reviewIndex = 0;
    private boolean reviewLoading = false;
    private int reviewCount = 0;
    private boolean descriptorsLoading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle("Spamkontroll");
            actionBar.setSubtitle("Kontrollsenter");
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
        refresh.setOnClickListener(v -> refreshCurrentAccount());
        accountRow.addView(refresh, wrapWrap());
        root.addView(accountRow, matchWrap());

        tvStatus = bodyText("Laster e-postkonto …");
        tvStatus.setPadding(0, dp(5), 0, dp(5));
        root.addView(tvStatus, matchWrap());

        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setPadding(0, 0, 0, dp(7));
        Button undo = secondaryButton("↶ Angre siste");
        undo.setOnClickListener(v -> undoLatest());
        quickRow.addView(undo, weightedButton());
        Button review = primaryButton("Gjennomgå");
        review.setOnClickListener(v -> setSection(Section.REVIEW));
        quickRow.addView(review, weightedButton());
        root.addView(quickRow, matchWrap());

        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        for (Section value : Section.values()) {
            Button button = secondaryButton(value.title);
            button.setOnClickListener(v -> setSection(value));
            navButtons.put(value, button);
            nav.addView(button, wrapWrapWithMargin(0, 0, 6, 0));
        }
        navScroll.addView(nav, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(navScroll, matchWrap());

        svContent = new ScrollView(this);
        svContent.setFillViewport(true);
        llPage = new LinearLayout(this);
        llPage.setOrientation(LinearLayout.VERTICAL);
        llPage.setPadding(0, dp(10), 0, dp(24));
        svContent.addView(llPage, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(svContent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        updateNav();
        return root;
    }

    private void setSection(Section next) {
        section = next;
        updateNav();
        if (next == Section.REVIEW && selectedAccount != null)
            loadReviewQueue(selectedAccount, reviewQueue.isEmpty());
        else
            renderCurrent();
        svContent.post(() -> svContent.scrollTo(0, 0));
    }

    private void openAliasBucket(int bucket) {
        aliasBucketSelection = Math.max(0, Math.min(2, bucket));
        setSection(Section.ALIASES);
    }

    private void updateNav() {
        for (Map.Entry<Section, Button> entry : navButtons.entrySet()) {
            boolean active = entry.getKey() == section;
            entry.getValue().setTypeface(Typeface.DEFAULT,
                    active ? Typeface.BOLD : Typeface.NORMAL);
            entry.getValue().setAlpha(active ? 1f : 0.70f);
        }
    }

    private void renderCurrent() {
        if (llPage == null)
            return;
        llPage.removeAllViews();
        switch (section) {
            case REVIEW:
                renderReview();
                break;
            case ALIASES:
                renderAliases();
                break;
            case FAMILIES:
                renderFamilies();
                break;
            case RULES:
                renderRules();
                break;
            case SETTINGS:
                renderSettings();
                break;
            case OVERVIEW:
            default:
                renderOverview();
                break;
        }
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
        spAccount.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < accounts.size())
                    observeAccount(accounts.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        if (accounts.isEmpty()) {
            tvStatus.setText("Ingen FairEmail-konto funnet i denne testappen.");
            renderCurrent();
        }
    }

    private void observeAccount(EntityAccount account) {
        if (account == null || account.uuid == null)
            return;
        boolean changed = selectedAccount == null ||
                !account.uuid.equals(selectedAccount.uuid);
        selectedAccount = account;
        if (changed) {
            reviewQueue = new ArrayList<>();
            reviewIndex = 0;
            reviewCount = 0;
            familyDescriptors.clear();
            // Re-evaluate every retained delivery through the exact identity path.
            // This flushes legacy fuzzy predictions without deleting human labels.
            SpamFamilyRescorer.enqueueAllActive(getApplicationContext(), account.uuid);
            SpamFamilyRescorer.start(getApplicationContext());
        }

        if (liveFamilies != null)
            liveFamilies.removeObservers(this);
        if (liveAliases != null)
            liveAliases.removeObservers(this);

        liveFamilies = SpamFamilyLabRepository.liveOverview(this, account.uuid);
        if (liveFamilies != null)
            liveFamilies.observe(this, value -> {
                if (!isSelected(account))
                    return;
                families = value == null ? Collections.emptyList() : value;
                if (section == Section.OVERVIEW || section == Section.FAMILIES)
                    renderCurrent();
                loadFamilyDescriptors(account);
            });

        liveAliases = SpamIntelligenceDB.getInstance(this).alias().liveAliases(account.uuid);
        liveAliases.observe(this, value -> {
            if (!isSelected(account))
                return;
            aliases = value == null ? Collections.emptyList() : value;
            if (section == Section.OVERVIEW || section == Section.ALIASES)
                renderCurrent();
        });

        tvStatus.setText("Konto: " + accountLabel(account));
        loadReviewQueue(account, false);
        loadDashboardExtras(account);
    }

    private void refreshCurrentAccount() {
        EntityAccount account = selectedAccount;
        if (account == null)
            return;
        tvStatus.setText("Oppdaterer Spamkontroll …");
        SpamFamilyRescorer.enqueueAllActive(getApplicationContext(), account.uuid);
        SpamFamilyRescorer.start(getApplicationContext());
        loadReviewQueue(account, true);
    }

    private void renderOverview() {
        llPage.addView(sectionTitle("Oversikt"), matchWrap());
        TextView intro = bodyText("Alt skal kunne gjøres. Ingenting unødvendig skal måtte gjøres.");
        intro.setPadding(0, dp(2), 0, dp(10));
        llPage.addView(intro, matchWrap());

        int compromised = 0;
        int smtpDead = 0;
        int spamHits = 0;
        int replacementsMissing = 0;
        for (EntityAlias alias : aliases) {
            if (alias == null)
                continue;
            if (alias.state == EntityAlias.STATE_COMPROMISED) {
                compromised++;
                if (TextUtils.isEmpty(alias.replaced_by))
                    replacementsMissing++;
            }
            if (alias.smtp_reject_state == EntityAlias.SMTP_REJECT_VERIFIED)
                smtpDead++;
            spamHits += alias.spam_hits == null ? 0 : alias.spam_hits;
        }

        int actualNeeds = reviewCount + attentionStats.actualNeedsBeyondMail();
        String needsDetail = reviewCount + " meldinger · " +
                attentionStats.compromiseDecisions + " aliasavgjørelser · " +
                attentionStats.serverFailures + " serverfeil";
        llPage.addView(statCard("Trenger deg", String.valueOf(actualNeeds),
                needsDetail, actualNeeds == 0 ? null :
                        (reviewCount > 0 ? "Gjennomgå nå" : "Se aliaser"),
                actualNeeds == 0 ? null :
                        (reviewCount > 0 ? v -> setSection(Section.REVIEW) : v -> openAliasBucket(0))),
                matchWrapWithMargin(0, 0, 0, 8));
        llPage.addView(statCard("Kompromitterte aliaser", String.valueOf(compromised),
                replacementsMissing + " uten replacement · anbefalt", "Se aliaser",
                v -> openAliasBucket(0)), matchWrapWithMargin(0, 0, 0, 8));
        llPage.addView(statCard("Kjent spam", String.valueOf(spamHits),
                families.size() + " spamidentiteter · " + smtpDead + " SMTP-døde aliaser", null, null),
                matchWrapWithMargin(0, 0, 0, 12));

        llPage.addView(sectionTitle("Trenger deg"), matchWrap());
        if (reviewCount > 0)
            llPage.addView(attentionCard(reviewCount + " meldinger trenger vurdering",
                    "Dette er faktiske Spam/Ikke spam-beslutninger.",
                    "Start", v -> setSection(Section.REVIEW)), matchWrapWithMargin(0, 6, 0, 6));
        if (attentionStats.compromiseDecisions > 0)
            llPage.addView(attentionCard(attentionStats.compromiseDecisions +
                            " aliaser trenger kompromissavgjørelse",
                    "Spam finnes, men appen kan ikke avgjøre alias-lekkasjen uten deg.",
                    "Se aliaser", v -> openAliasBucket(0)), matchWrapWithMargin(0, 0, 0, 6));
        if (attentionStats.serverFailures > 0)
            llPage.addView(attentionCard(attentionStats.serverFailures + " SMTP-operasjoner feilet",
                    "Serverhandlingen trenger inspeksjon eller nytt forsøk.",
                    "Se aliaser", v -> openAliasBucket(0)), matchWrapWithMargin(0, 0, 0, 6));
        if (actualNeeds == 0) {
            TextView done = bodyText("✓ Ingen uløste beslutninger eller feil akkurat nå.");
            done.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            done.setPadding(0, dp(8), 0, dp(12));
            llPage.addView(done, matchWrap());
        }

        int recommendations = attentionStats.recommendations() +
                (SpamControlPolicy.hasCpanelConfig(this) ? 0 : 1);
        if (recommendations > 0) {
            llPage.addView(sectionTitle("Anbefalt"), matchWrapWithMargin(0, 10, 0, 0));
            if (attentionStats.replacementMissingRecommendations > 0)
                llPage.addView(attentionCard(attentionStats.replacementMissingRecommendations +
                                " kompromitterte aliaser mangler replacement",
                        "Anbefalt før SMTP-burn. Dette sperrer deg ikke.",
                        "Se aliaser", v -> openAliasBucket(0)), matchWrapWithMargin(0, 6, 0, 6));
            if (attentionStats.replacementUnverifiedRecommendations > 0)
                llPage.addView(attentionCard(attentionStats.replacementUnverifiedRecommendations +
                                " replacements kan verifiseres",
                        "Verifisering gir mer sikkerhet, men er ikke nødvendig for burn.",
                        "Se aliaser", v -> openAliasBucket(0)), matchWrapWithMargin(0, 0, 0, 6));
            if (!SpamControlPolicy.hasCpanelConfig(this))
                llPage.addView(attentionCard("cPanel er ikke konfigurert",
                        "Konfigurer dette når du vil bruke server-side SMTP hard reject.",
                        "Konfigurer", v -> showCpanelDialog()), matchWrapWithMargin(0, 0, 0, 10));
        }

        llPage.addView(sectionTitle("Automatikk"), matchWrapWithMargin(0, 8, 0, 0));
        llPage.addView(infoCard("Kjent spamidentitet",
                SpamControlPolicy.exactFamilyDetection(this) ? "PÅ · avsendernavn + emne" : "AV"), matchWrapWithMargin(0, 6, 0, 6));
        llPage.addView(infoCard("Auto-bekreft kjent spam",
                SpamControlPolicy.autoLabelExact(this) ? "PÅ · ingen automatisk flytting" : "AV · observer"), matchWrapWithMargin(0, 0, 0, 6));
        llPage.addView(infoCard("SMTP-burn",
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
            SpamControlAttentionStats.Stats loadedAttention;
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
            try {
                loadedAttention = SpamControlAttentionStats.compute(
                        getApplicationContext(), account.uuid);
            } catch (Throwable ex) {
                Log.e(ex);
                loadedAttention = SpamControlAttentionStats.EMPTY;
            }
            final List<SpamNetworkAnalyzer.Network> safeNetworks = loadedNetworks;
            final List<EntitySpamActionHistory> safeActions = loadedActions;
            final SpamControlAttentionStats.Stats safeAttention = loadedAttention;
            runOnUiThread(() -> {
                if (!isSelected(account) || isFinishing() || isDestroyed())
                    return;
                networks = safeNetworks;
                recentActions = safeActions;
                attentionStats = safeAttention;
                if (section == Section.OVERVIEW || section == Section.FAMILIES ||
                        section == Section.SETTINGS)
                    renderCurrent();
            });
        });
    }

    private void loadReviewQueue(EntityAccount account, boolean showLoading) {
        if (account == null || account.uuid == null)
            return;
        final long generation = reviewGeneration.incrementAndGet();
        if (showLoading) {
            reviewLoading = true;
            if (section == Section.REVIEW)
                renderCurrent();
        }
        executor.execute(() -> {
            List<SpamFamilyLabRepository.Candidate> result;
            int total;
            try {
                result = SpamFamilyLabRepository.getReviewQueue(
                        getApplicationContext(), account.uuid, REVIEW_LIMIT);
            } catch (Throwable ex) {
                Log.e(ex);
                result = Collections.emptyList();
            }
            try {
                total = SpamControlQueueStats.countReview(
                        getApplicationContext(), account.uuid);
            } catch (Throwable ex) {
                Log.e(ex);
                total = result.size();
            }
            final List<SpamFamilyLabRepository.Candidate> queue = result;
            final int exactTotal = Math.max(queue.size(), total);
            runOnUiThread(() -> {
                if (generation != reviewGeneration.get() || !isSelected(account) ||
                        isFinishing() || isDestroyed())
                    return;
                reviewLoading = false;
                reviewQueue = new ArrayList<>(queue);
                reviewCount = exactTotal;
                if (reviewIndex >= reviewQueue.size())
                    reviewIndex = 0;
                if (section == Section.REVIEW || section == Section.OVERVIEW)
                    renderCurrent();
                tvStatus.setText("Konto: " + accountLabel(account) +
                        " · " + reviewCount + " trenger vurdering");
            });
        });
    }

    private void renderReview() {
        llPage.addView(sectionTitle("Gjennomgang"), matchWrap());
        TextView help = bodyText("Én arbeidskø. Spam eller Ikke spam. Familien organiseres automatisk av avsendernavn + emne.");
        help.setPadding(0, dp(2), 0, dp(10));
        llPage.addView(help, matchWrap());

        if (reviewLoading) {
            llPage.addView(bodyText("Laster arbeidskø …"), matchWrap());
            return;
        }
        if (reviewQueue.isEmpty()) {
            CardView done = card(12, 2);
            LinearLayout body = cardBody(16, 18);
            TextView title = valueText("✨ Ferdig", 24f, true);
            body.addView(title, matchWrap());
            body.addView(bodyText("Ingen meldinger trenger vurdering."), matchWrapWithMargin(0, 5, 0, 0));
            done.addView(body, matchWrap());
            llPage.addView(done, matchWrap());
            return;
        }

        TextView progress = bodyText((reviewIndex + 1) + " av " + reviewQueue.size() + " i denne køen");
        progress.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        progress.setPadding(0, 0, 0, dp(6));
        llPage.addView(progress, matchWrap());
        SpamFamilyLabRepository.Candidate candidate = reviewQueue.get(reviewIndex);
        llPage.addView(reviewCard(candidate), matchWrap());
    }

    private View reviewCard(SpamFamilyLabRepository.Candidate candidate) {
        CardView card = card(12, 3);
        LinearLayout content = cardBody(15, 14);
        TextView state = bodyText(reviewState(candidate));
        state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(state, matchWrap());

        content.addView(fieldLabel("EMNE"), matchWrapWithMargin(0, 12, 0, 0));
        content.addView(valueText(empty(candidate.subject, "(uten emne)"), 22f, true), matchWrap());

        SenderParts sender = senderParts(candidate.sender);
        content.addView(fieldLabel("AVSENDERNAVN"), matchWrapWithMargin(0, 11, 0, 0));
        content.addView(valueText(empty(sender.name, "(uten navn)"), 17f, true), matchWrap());

        content.addView(fieldLabel("AVSENDERADRESSE"), matchWrapWithMargin(0, 9, 0, 0));
        TextView senderAddress = valueText(empty(sender.address,
                candidate.senderDomain == null ? "(ukjent)" : candidate.senderDomain), 15f, false);
        senderAddress.setTypeface(Typeface.MONOSPACE);
        senderAddress.setTextIsSelectable(true);
        content.addView(senderAddress, matchWrap());

        CardView aliasCard = card(8, 1);
        LinearLayout aliasBody = cardBody(11, 10);
        aliasBody.addView(fieldLabel("MOTTATT PÅ ALIAS"), matchWrap());
        TextView alias = valueText(empty(candidate.alias, "(ukjent alias)"), 16f, true);
        alias.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        alias.setTextIsSelectable(true);
        aliasBody.addView(alias, matchWrap());
        TextView signal = bodyText(aliasSignal(candidate));
        signal.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        signal.setPadding(0, dp(5), 0, 0);
        aliasBody.addView(signal, matchWrap());
        if (candidate.expectedDomains != null || candidate.senderDomain != null) {
            TextView domains = bodyText("Forventet: " + empty(candidate.expectedDomains, "ukjent") +
                    "\nFaktisk: " + empty(candidate.senderDomain, "ukjent"));
            domains.setPadding(0, dp(3), 0, 0);
            aliasBody.addView(domains, matchWrap());
        }
        aliasCard.addView(aliasBody, matchWrap());
        content.addView(aliasCard, matchWrapWithMargin(0, 12, 0, 0));

        SpamFamilyIdentity.Identity identity = SpamFamilyIdentity.fromRaw(sender.name, candidate.subject);
        if (identity != null) {
            TextView identityText = bodyText("Familie: " + sender.name + " + " + candidate.subject);
            identityText.setPadding(0, dp(8), 0, 0);
            identityText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            content.addView(identityText, matchWrap());
        }

        TextView when = bodyText(DateUtils.getRelativeDateTimeString(
                this, candidate.received, DateUtils.MINUTE_IN_MILLIS,
                DateUtils.WEEK_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString());
        when.setTextSize(12f);
        when.setPadding(0, dp(7), 0, 0);
        content.addView(when, matchWrap());

        if (SpamControlPolicy.showTechnical(this))
            content.addView(technicalInline(candidate), matchWrapWithMargin(0, 10, 0, 0));
        else {
            Button details = secondaryButton("Detaljer");
            details.setOnClickListener(v -> showTechnicalDetails(candidate));
            content.addView(details, wrapWrapWithMargin(0, 8, 0, 0));
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(14), 0, 0);
        boolean actionIdle = !reviewActionRunning.get();
        Button spam = primaryButton("Spam");
        spam.setEnabled(actionIdle && candidate.label != EntityAliasDelivery.LABEL_SPAM);
        spam.setOnClickListener(v -> runReviewAction(candidate, true));
        actions.addView(spam, weightedButton());
        Button ham = primaryButton("Ikke spam");
        ham.setEnabled(actionIdle && candidate.label != EntityAliasDelivery.LABEL_HAM);
        ham.setOnClickListener(v -> runReviewAction(candidate, false));
        actions.addView(ham, weightedButton());
        content.addView(actions, matchWrap());

        Button skip = secondaryButton("Hopp over");
        skip.setEnabled(actionIdle);
        skip.setOnClickListener(v -> {
            reviewIndex++;
            if (reviewIndex >= reviewQueue.size())
                reviewIndex = 0;
            renderCurrent();
        });
        content.addView(skip, matchWrapWithMargin(0, 5, 0, 0));

        card.addView(content, matchWrap());
        return card;
    }

    private void runReviewAction(SpamFamilyLabRepository.Candidate candidate, boolean spam) {
        EntityAccount account = selectedAccount;
        if (account == null || account.uuid == null)
            return;
        if ((spam && candidate.label == EntityAliasDelivery.LABEL_SPAM) ||
                (!spam && candidate.label == EntityAliasDelivery.LABEL_HAM))
            return;
        if (!reviewActionRunning.compareAndSet(false, true))
            return;

        tvStatus.setText(spam ? "Lagrer spam …" : "Lagrer ikke spam …");
        if (section == Section.REVIEW)
            renderCurrent();
        long familyContext = candidate.predictedFamilyId == null ? 0L : candidate.predictedFamilyId;
        executor.execute(() -> {
            SpamFamilyLabRepository.ActionResult result;
            int affectedMessages = 1;
            int affectedAliases = candidate.alias == null ? 0 : 1;
            if (spam) {
                SpamFamilyLabRepository.BulkActionResult bulk =
                        SpamUndoManager.runBulkMessageAction(
                                getApplicationContext(), account.uuid, familyContext, candidate.messageId,
                                SpamUndoManager.ACTION_SPAM, "Spam · eksakt identitet",
                                () -> SpamFamilyLabRepository.markSpamBulk(
                                        getApplicationContext(), account.uuid, candidate.messageId));
                result = bulk.result;
                affectedMessages = Math.max(1, bulk.messages);
                affectedAliases = Math.max(affectedAliases, bulk.aliases);
            } else {
                result = SpamUndoManager.runMessageAction(
                        getApplicationContext(), account.uuid, familyContext, candidate.messageId,
                        SpamUndoManager.ACTION_NOT_SPAM, "Ikke spam",
                        () -> SpamFamilyLabRepository.markLegitimate(
                                getApplicationContext(), account.uuid, candidate.messageId));
            }
            final int impactMessages = affectedMessages;
            final int impactAliases = affectedAliases;
            runOnUiThread(() -> {
                reviewActionRunning.set(false);
                if (isFinishing() || isDestroyed())
                    return;
                if (!isSelected(account)) {
                    if (section == Section.REVIEW)
                        renderCurrent();
                    return;
                }
                if (result == SpamFamilyLabRepository.ActionResult.APPLIED) {
                    String text;
                    if (spam && impactMessages > 1)
                        text = "Spam lært · " + impactMessages + " meldinger · " +
                                impactAliases + " aliaser oppdatert.";
                    else
                        text = spam ? "Lagret som spam." : "Lagret som ikke spam.";
                    tvStatus.setText(text);
                    Snackbar.make(svContent, text, Snackbar.LENGTH_LONG)
                            .setAction("ANGRE", v -> undoLatest())
                            .show();
                    if (SpamControlPolicy.autoAdvance(this) &&
                            reviewIndex < reviewQueue.size())
                        reviewQueue.remove(reviewIndex);
                    reviewCount = Math.max(0, reviewCount - 1);
                    if (reviewIndex >= reviewQueue.size())
                        reviewIndex = 0;
                    renderCurrent();
                    loadReviewQueue(account, false);
                } else {
                    tvStatus.setText("Kunne ikke lagre endringen.");
                    renderCurrent();
                    loadReviewQueue(account, false);
                }
            });
        });
    }

    private void renderAliases() {
        llPage.addView(sectionTitle("Aliaser"), matchWrap());
        TextView intro = bodyText("Spam-/kompromitterte, legitime og uavklarte aliaser er separate arbeidsflater. Kun én liste vises om gangen.");
        intro.setPadding(0, dp(2), 0, dp(8));
        llPage.addView(intro, matchWrap());

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Søk i valgt aliasgruppe");
        llPage.addView(search, matchWrapWithMargin(0, 0, 0, 8));

        LinearLayout bucketRowTop = new LinearLayout(this);
        bucketRowTop.setOrientation(LinearLayout.HORIZONTAL);
        Button spam = secondaryButton("Spam / kompromitterte (" + countAliasBucket(0) + ")");
        Button legit = secondaryButton("Legitime (" + countAliasBucket(1) + ")");
        bucketRowTop.addView(spam, weightedButton());
        bucketRowTop.addView(legit, weightedButton());
        llPage.addView(bucketRowTop, matchWrapWithMargin(0, 0, 0, 4));

        LinearLayout bucketRowBottom = new LinearLayout(this);
        bucketRowBottom.setOrientation(LinearLayout.HORIZONTAL);
        Button unresolved = secondaryButton("Uavklarte (" + countAliasBucket(2) + ")");
        Button inactive = secondaryButton("Inaktive (" + countAliasBucket(3) + ")");
        bucketRowBottom.addView(unresolved, weightedButton());
        bucketRowBottom.addView(inactive, weightedButton());
        llPage.addView(bucketRowBottom, matchWrapWithMargin(0, 0, 0, 8));

        TextView description = bodyText("");
        description.setPadding(0, 0, 0, dp(7));
        llPage.addView(description, matchWrap());

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        llPage.addView(list, matchWrap());

        final Button[] buttons = {spam, legit, unresolved, inactive};
        final Runnable[] repopulate = new Runnable[1];
        repopulate[0] = () -> {
            for (int i = 0; i < buttons.length; i++) {
                boolean active = i == aliasBucketSelection;
                buttons[i].setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
                buttons[i].setAlpha(active ? 1f : 0.62f);
            }
            description.setText(aliasBucketDescription(aliasBucketSelection));
            populateAliasBucket(list, search.getText().toString(), aliasBucketSelection,
                    aliasBucketEmptyText(aliasBucketSelection));
        };

        spam.setOnClickListener(v -> {
            aliasBucketSelection = 0;
            repopulate[0].run();
        });
        legit.setOnClickListener(v -> {
            aliasBucketSelection = 1;
            repopulate[0].run();
        });
        unresolved.setOnClickListener(v -> {
            aliasBucketSelection = 2;
            repopulate[0].run();
        });
        inactive.setOnClickListener(v -> {
            aliasBucketSelection = 3;
            repopulate[0].run();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { repopulate[0].run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        repopulate[0].run();
    }

    private int countAliasBucket(int bucket) {
        int count = 0;
        for (EntityAlias alias : aliases)
            if (alias != null && aliasBucket(alias) == bucket)
                count++;
        return count;
    }

    private String aliasBucketDescription(int bucket) {
        if (bucket == 0)
            return "Kompromitterte/erstattede aliaser, SMTP-styrte aliaser og uløste kompromissavgjørelser.";
        if (bucket == 1)
            return "Aktive legitime aliaser. En spam-mail flytter ikke aliaset hitfra når lekkasje allerede er avkreftet.";
        if (bucket == 2)
            return "Aktive aliaser som foreløpig mangler nok evidens til en sikker plassering.";
        return "Aliaser du eksplisitt har satt til DISABLED eller IGNORED.";
    }

    private String aliasBucketEmptyText(int bucket) {
        if (bucket == 0)
            return "Ingen spam-/kompromitterte aliaser matcher søket.";
        if (bucket == 1)
            return "Ingen legitime aliaser matcher søket.";
        if (bucket == 2)
            return "Ingen uavklarte aliaser matcher søket.";
        return "Ingen inaktive aliaser matcher søket.";
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

    /** 0=spam/problem, 1=legitimate, 2=unresolved, 3=inactive. */
    private int aliasBucket(EntityAlias alias) {
        int spam = alias.spam_hits == null ? 0 : alias.spam_hits;
        int ham = alias.ham_hits == null ? 0 : alias.ham_hits;
        int smtp = alias.smtp_reject_state == null
                ? EntityAlias.SMTP_REJECT_NONE : alias.smtp_reject_state;

        // Physical SMTP state and explicit compromised/replaced lifecycle are
        // problem work regardless of the message-level spam counters.
        if (alias.state == EntityAlias.STATE_COMPROMISED ||
                alias.state == EntityAlias.STATE_REPLACED ||
                smtp != EntityAlias.SMTP_REJECT_NONE)
            return 0;

        if (alias.state == EntityAlias.STATE_DISABLED ||
                alias.state == EntityAlias.STATE_IGNORED)
            return 3;

        boolean compromiseReview = alias.state == EntityAlias.STATE_ACTIVE &&
                spam > 0 && SpamControlPolicy.markAliasCompromised(this) &&
                AliasCompromiseReviewStore.needsReview(this, alias);
        if (compromiseReview)
            return 0;

        if (alias.state == EntityAlias.STATE_ACTIVE) {
            if (ham > 0 || aliasHasInboxTraffic(alias))
                return 1;

            // With compromise intelligence enabled, an active alias with spam
            // whose current spam count no longer needs review has explicitly
            // been resolved healthy by the user/policy. Spam truth does not
            // become alias-leak truth.
            if (spam > 0 && SpamControlPolicy.markAliasCompromised(this))
                return 1;
        }

        return 2;
    }

    private boolean aliasHasInboxTraffic(EntityAlias alias) {
        if (alias == null || TextUtils.isEmpty(alias.folder_counts))
            return false;
        try {
            org.json.JSONObject counts = new org.json.JSONObject(alias.folder_counts);
            return counts.optInt(EntityFolder.INBOX, 0) > 0;
        } catch (Throwable ex) {
            Log.w(ex);
            return false;
        }
    }

    private View aliasCard(EntityAlias alias) {
        CardView card = card(10, 2);
        LinearLayout body = cardBody(14, 12);
        TextView address = valueText(alias.address, 17f, true);
        address.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        address.setTextIsSelectable(true);
        body.addView(address, matchWrap());
        body.addView(bodyText(aliasState(alias) + " · " +
                (alias.spam_hits == null ? 0 : alias.spam_hits) + " spam · " +
                (alias.ham_hits == null ? 0 : alias.ham_hits) + " legit"), matchWrapWithMargin(0, 4, 0, 0));
        if (!TextUtils.isEmpty(alias.service) || !TextUtils.isEmpty(alias.service_domain))
            body.addView(bodyText("Tjeneste: " + empty(alias.service, "ukjent") +
                    " · domene: " + empty(alias.service_domain, "ukjent")), matchWrapWithMargin(0, 3, 0, 0));
        if (!TextUtils.isEmpty(alias.replaced_by))
            body.addView(bodyText("Replacement: " + alias.replaced_by), matchWrapWithMargin(0, 3, 0, 0));
        AliasBurnPolicy.Result readiness = AliasBurnReadiness.evaluate(this, alias);
        TextView lifecycle = bodyText("Neste: " + aliasLifecycleText(alias, readiness));
        lifecycle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        body.addView(lifecycle, matchWrapWithMargin(0, 4, 0, 0));
        body.addView(bodyText("SMTP: " + smtpState(alias)),
                matchWrapWithMargin(0, 2, 0, 0));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        Button open = primaryButton("Åpne");
        open.setOnClickListener(v -> showAliasEditor(alias));
        actions.addView(open, weightedButton());
        if (alias.smtp_reject_state == EntityAlias.SMTP_REJECT_VERIFIED) {
            Button restore = secondaryButton("Gjenopprett SMTP");
            restore.setOnClickListener(v -> confirmRestore(alias));
            actions.addView(restore, weightedButton());
        } else {
            if (readiness.verdict == AliasBurnPolicy.Verdict.REVIEW_COMPROMISE) {
                Button review = secondaryButton("Vurder alias");
                review.setOnClickListener(v -> showCompromiseReview(alias));
                actions.addView(review, weightedButton());
            } else if (readiness.verdict == AliasBurnPolicy.Verdict.COMPROMISED ||
                    readiness.verdict == AliasBurnPolicy.Verdict.ROTATE_FIRST) {
                Button replacement = secondaryButton("Sett replacement");
                replacement.setOnClickListener(v -> showAliasEditor(alias));
                actions.addView(replacement, weightedButton());
            } else if (readiness.verdict == AliasBurnPolicy.Verdict.VERIFY_REPLACEMENT) {
                Button verify = secondaryButton("Skann Innboks");
                verify.setOnClickListener(v -> runHistoricalScan(true, false));
                actions.addView(verify, weightedButton());
            }
        }
        body.addView(actions, matchWrap());

        if (alias.smtp_reject_state != EntityAlias.SMTP_REJECT_VERIFIED) {
            Button burn = secondaryButton(!SpamControlPolicy.smtpBurnEnabled(this)
                    ? "Aktiver manuell SMTP-burn"
                    : !SpamControlPolicy.hasCpanelConfig(this)
                    ? "Konfigurer cPanel for SMTP-burn"
                    : readiness.burnAllowed ? "SMTP-død" : "SMTP-død likevel");
            burn.setOnClickListener(v -> {
                if (!SpamControlPolicy.smtpBurnEnabled(this)) {
                    SpamControlPolicy.setBoolean(this, SpamControlPolicy.PREF_SMTP_BURN_ENABLED, true);
                    tvStatus.setText("Manuell SMTP-burn er slått på.");
                    renderCurrent();
                } else if (!SpamControlPolicy.hasCpanelConfig(this))
                    showCpanelDialog();
                else
                    confirmBurn(alias);
            });
            body.addView(burn, matchWrapWithMargin(0, 6, 0, 0));
        }
        card.addView(body, matchWrap());
        card.setOnClickListener(v -> showAliasEditor(alias));
        return card;
    }

    private void showAliasEditor(EntityAlias alias) {
        LinearLayout form = dialogForm();
        EditText service = input("Tjeneste", alias.service);
        EditText domain = input("Service-domene", alias.service_domain);
        EditText trusted = input("Trusted domains, kommaseparert", jsonArrayToCsv(alias.trusted_domains));
        EditText replacement = input("Replacement alias", alias.replaced_by);
        EditText note = input("Notat", alias.note);
        form.addView(service, matchWrap());
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

        AliasBurnPolicy.Result readiness = AliasBurnReadiness.evaluate(this, alias);
        TextView lifecycle = bodyText("SMTP-livssyklus: " + aliasLifecycleText(alias, readiness));
        lifecycle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        lifecycle.setPadding(0, dp(8), 0, dp(6));
        form.addView(lifecycle, matchWrap());

        Spinner state = new Spinner(this);
        String[] states = {"ACTIVE", "COMPROMISED", "REPLACED", "DISABLED", "IGNORED"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, states);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        state.setAdapter(adapter);
        state.setSelection(statePosition(alias.state));
        form.addView(state, matchWrap());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(alias.address)
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Lagre", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    saveAlias(alias.address,
                            service.getText().toString(), domain.getText().toString(),
                            trusted.getText().toString(), replacement.getText().toString(),
                            note.getText().toString(), state.getSelectedItemPosition());
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void saveAlias(String address, String service, String domain,
                           String trusted, String replacement, String note, int statePosition) {
        EntityAccount account = selectedAccount;
        if (account == null || account.uuid == null)
            return;
        tvStatus.setText("Lagrer alias …");
        executor.execute(() -> {
            boolean ok = false;
            boolean stateChanged = false;
            try {
                DaoAlias dao = SpamIntelligenceDB.getInstance(getApplicationContext()).alias();
                EntityAlias fresh = dao.getAlias(account.uuid, address);
                if (fresh != null) {
                    int newState = stateFromPosition(statePosition);
                    int oldState = fresh.state == null ? EntityAlias.STATE_ACTIVE : fresh.state;
                    stateChanged = oldState != newState;

                    fresh.service = cleanNullable(service);
                    fresh.service_domain = cleanNullable(domain);
                    fresh.trusted_domains = csvToJsonArray(trusted);
                    fresh.replaced_by = cleanNullable(replacement);
                    fresh.note = cleanNullable(note);
                    fresh.state = newState;

                    if (stateChanged) {
                        final EntityAlias target = fresh;
                        final int targetState = newState;
                        ok = SpamUndoManager.runAccountAction(
                                getApplicationContext(), account.uuid,
                                SpamUndoManager.ACTION_ALIAS_STATE,
                                "Endre aliasstatus",
                                () -> {
                                    boolean updated = dao.updateAlias(target) == 1;
                                    if (updated && targetState == EntityAlias.STATE_ACTIVE)
                                        AliasCompromiseReviewStore.markReviewedHealthy(
                                                getApplicationContext(), target);
                                    return updated;
                                });
                    } else
                        ok = dao.updateAlias(fresh) == 1;
                    // Sender regex will also be synchronized naturally on next delivery/reply.
                }
            } catch (Throwable ex) {
                Log.e(ex);
            }
            final boolean saved = ok;
            final boolean lifecycleChanged = stateChanged;
            runOnUiThread(() -> {
                tvStatus.setText(saved ? "Alias lagret." : "Kunne ikke lagre alias.");
                if (saved && lifecycleChanged)
                    Snackbar.make(svContent, "Aliasstatus endret.", Snackbar.LENGTH_LONG)
                            .setAction("ANGRE", v -> undoLatest()).show();
                if (saved)
                    loadDashboardExtras(account);
            });
        });
    }

    private void confirmBurn(EntityAlias alias) {
        if (!SpamControlPolicy.smtpBurnEnabled(this)) {
            tvStatus.setText("SMTP-burn er deaktivert i Regler.");
            return;
        }
        if (!SpamControlPolicy.hasCpanelConfig(this)) {
            showCpanelDialog();
            return;
        }
        AliasBurnPolicy.Result readiness = AliasBurnReadiness.evaluate(this, alias);
        StringBuilder message = new StringBuilder();
        message.append(alias.address)
                .append("\n\nServeren skal svare med hard feil ved RCPT TO. ")
                .append("Spamkontroll verifiserer regelen med read-back før den kalles aktiv.");
        if (!readiness.burnAllowed) {
            message.append("\n\n⚠ ANBEFALING, IKKE LÅS: ")
                    .append(aliasLifecycleText(alias, readiness));
            if (TextUtils.isEmpty(alias.replaced_by))
                message.append("\nReplacement er ikke registrert.");
            else if (readiness.verdict == AliasBurnPolicy.Verdict.VERIFY_REPLACEMENT)
                message.append("\nReplacement er registrert, men ikke verifisert fra mottatt legitim trafikk.");
            message.append("\n\nDu kan overstyre dette og burne aliaset nå.");
        }
        new AlertDialog.Builder(this)
                .setTitle(readiness.burnAllowed ? "Gjør alias SMTP-dødt?" : "Overstyr og gjør alias SMTP-dødt?")
                .setMessage(message.toString())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(readiness.burnAllowed ? "Gjør SMTP-dødt" : "BURN LIKEVEL",
                        (d, w) -> burnAlias(alias))
                .show();
    }

    private void burnAlias(EntityAlias alias) {
        EntityAccount account = selectedAccount;
        CpanelAliasActuator.Config config = SpamControlPolicy.cpanelConfig(this);
        if (account == null || config == null)
            return;
        tvStatus.setText("Oppretter og verifiserer SMTP hard-fail …");
        executor.execute(() -> {
            AliasBurnManager.Outcome outcome = AliasBurnManager.burn(
                    getApplicationContext(), account.uuid, alias.address,
                    new CpanelAliasActuator(config), AliasBurnManager.DEFAULT_FAILURE_MESSAGE);
            runOnUiThread(() -> tvStatus.setText(outcome.success
                    ? "SMTP hard-fail er verifisert for " + alias.address
                    : "SMTP-burn feilet: " + outcome.error));
        });
    }

    private void confirmRestore(EntityAlias alias) {
        if (!SpamControlPolicy.hasCpanelConfig(this)) {
            showCpanelDialog();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Gjenopprett SMTP-mottak?")
                .setMessage(alias.address)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Gjenopprett", (d, w) -> restoreAlias(alias))
                .show();
    }

    private void restoreAlias(EntityAlias alias) {
        EntityAccount account = selectedAccount;
        CpanelAliasActuator.Config config = SpamControlPolicy.cpanelConfig(this);
        if (account == null || config == null)
            return;
        tvStatus.setText("Gjenoppretter SMTP-routing …");
        executor.execute(() -> {
            AliasBurnManager.Outcome outcome = AliasBurnManager.restore(
                    getApplicationContext(), account.uuid, alias.address,
                    new CpanelAliasActuator(config));
            runOnUiThread(() -> tvStatus.setText(outcome.success
                    ? "SMTP-mottak er verifisert gjenopprettet."
                    : "Gjenoppretting feilet: " + outcome.error));
        });
    }

    private void renderFamilies() {
        llPage.addView(sectionTitle("Spamgrupper"), matchWrap());
        CardView rule = card(10, 2);
        LinearLayout rb = cardBody(14, 12);
        rb.addView(valueText("Familieidentitet er eksakt", 17f, true), matchWrap());
        rb.addView(bodyText("Samme normaliserte avsendernavn + samme normaliserte emne = samme familie. " +
                "Avsenderadresse og Envelope-To påvirker ikke familieidentiteten."), matchWrapWithMargin(0, 5, 0, 0));
        rule.addView(rb, matchWrap());
        llPage.addView(rule, matchWrapWithMargin(0, 6, 0, 12));

        if (families.isEmpty()) {
            llPage.addView(bodyText("Ingen spamgrupper ennå."), matchWrap());
            return;
        }
        for (TupleSpamFamilyOverview family : families)
            llPage.addView(familyCard(family), matchWrapWithMargin(0, 0, 0, 8));

        llPage.addView(sectionTitle("Spamnettverk"), matchWrapWithMargin(0, 12, 0, 3));
        llPage.addView(bodyText("Bredere HTML-, lenke- og senderinfrastruktur analyseres separat. " +
                "Nettverk kan koble flere eksakte familier uten å slå dem sammen."),
                matchWrapWithMargin(0, 0, 0, 7));
        if (networks.isEmpty())
            llPage.addView(bodyText("Ingen sterke nettverkskoblinger funnet i de lagrede familie-fingerprintene."), matchWrap());
        else
            for (int i = 0; i < networks.size(); i++)
                llPage.addView(networkCard(networks.get(i), i + 1), matchWrapWithMargin(0, 0, 0, 7));
    }

    private View familyCard(TupleSpamFamilyOverview family) {
        CardView card = card(10, 2);
        LinearLayout body = cardBody(14, 12);
        String descriptor = familyDescriptors.get(family.family_id);
        String name = family.name == null || family.name.trim().isEmpty()
                ? (descriptor == null ? "Spamgruppe " + family.family_id : descriptor)
                : family.name.trim() + (descriptor == null ? "" : "\n" + descriptor);
        TextView title = valueText(name, 17f, true);
        body.addView(title, matchWrap());
        body.addView(bodyText(family.confirmed_count + " bekreftet · " +
                family.strong_unknown_count + " eksakte nye treff"), matchWrapWithMargin(0, 5, 0, 0));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        Button rename = secondaryButton("Gi navn");
        rename.setOnClickListener(v -> showRenameDialog(family));
        actions.addView(rename, weightedButton());
        Button rescore = secondaryButton("Skann på nytt");
        rescore.setOnClickListener(v -> {
            EntityAccount account = selectedAccount;
            if (account != null)
                SpamFamilyLabRepository.requestRescore(getApplicationContext(), account.uuid, family.family_id);
            tvStatus.setText("Eksakt rescan er lagt i kø.");
        });
        actions.addView(rescore, weightedButton());
        body.addView(actions, matchWrap());
        card.addView(body, matchWrap());
        return card;
    }

    private void loadFamilyDescriptors(EntityAccount account) {
        if (descriptorsLoading || account == null || families.isEmpty())
            return;
        descriptorsLoading = true;
        List<TupleSpamFamilyOverview> snapshot = new ArrayList<>(families);
        executor.execute(() -> {
            Map<Long, String> loaded = new HashMap<>();
            for (TupleSpamFamilyOverview family : snapshot) {
                String value = SpamFamilyLabRepository.describeFamily(
                        getApplicationContext(), family.family_id);
                if (value != null)
                    loaded.put(family.family_id, value);
            }
            runOnUiThread(() -> {
                descriptorsLoading = false;
                if (!isSelected(account))
                    return;
                familyDescriptors.putAll(loaded);
                if (section == Section.FAMILIES)
                    renderCurrent();
            });
        });
    }

    private void showRenameDialog(TupleSpamFamilyOverview family) {
        EditText input = input("Navn", family.name);
        new AlertDialog.Builder(this)
                .setTitle("Gi spamgruppen navn")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Lagre", (d, w) -> {
                    EntityAccount account = selectedAccount;
                    if (account == null)
                        return;
                    executor.execute(() -> {
                        boolean changed = SpamUndoManager.renameFamily(
                                getApplicationContext(), account.uuid, family.family_id,
                                input.getText().toString());
                        runOnUiThread(() -> {
                            tvStatus.setText(changed ? "Navnet er lagret." : "Kunne ikke lagre navnet.");
                            if (changed)
                                Snackbar.make(svContent, "Navnet er lagret.", Snackbar.LENGTH_LONG)
                                        .setAction("ANGRE", v -> undoLatest()).show();
                        });
                    });
                })
                .show();
    }

    private void renderRules() {
        llPage.addView(sectionTitle("Regler"), matchWrap());
        llPage.addView(bodyText("Dette er faktisk policy motoren leser, ikke visningsinnstillinger."),
                matchWrapWithMargin(0, 2, 0, 10));

        llPage.addView(policyCheck("Kjent spamidentitet",
                "Eksakt avsendernavn + emne kan produsere et kjent-family treff.",
                SpamControlPolicy.PREF_EXACT_FAMILY,
                SpamControlPolicy.exactFamilyDetection(this),
                checked -> {
                    SpamControlPolicy.setBoolean(this, SpamControlPolicy.PREF_EXACT_FAMILY, checked);
                    EntityAccount account = selectedAccount;
                    if (account != null)
                        SpamFamilyRescorer.enqueueAllActive(this, account.uuid);
                }), matchWrapWithMargin(0, 0, 0, 7));

        llPage.addView(policyCheck("Auto-bekreft eksakt kjent spam",
                "Når PÅ: exact-match blir bekreftet som spam i intelligenslaget. Mail flyttes ikke automatisk.",
                SpamControlPolicy.PREF_AUTO_LABEL_EXACT,
                SpamControlPolicy.autoLabelExact(this),
                checked -> SpamControlPolicy.setBoolean(this, SpamControlPolicy.PREF_AUTO_LABEL_EXACT, checked)),
                matchWrapWithMargin(0, 0, 0, 7));

        llPage.addView(policyCheck("Spam kan kompromittere alias",
                "Spam og alias-lekkasje er separate sannheter. Fremmed/mistenkelig spam kan markere aliaset COMPROMISED; spam fra forventet tjenesteavsender gjør det ikke.",
                SpamControlPolicy.PREF_MARK_ALIAS_COMPROMISED,
                SpamControlPolicy.markAliasCompromised(this),
                checked -> SpamControlPolicy.setBoolean(this, SpamControlPolicy.PREF_MARK_ALIAS_COMPROMISED, checked)),
                matchWrapWithMargin(0, 0, 0, 7));

        llPage.addView(policyCheck("Fremmed avsender er evidens",
                "Uventet avsenderdomene på et etablert alias påvirker aliasrisikoen.",
                SpamControlPolicy.PREF_FOREIGN_SENDER_EVIDENCE,
                SpamControlPolicy.foreignSenderEvidence(this),
                checked -> SpamControlPolicy.setBoolean(this, SpamControlPolicy.PREF_FOREIGN_SENDER_EVIDENCE, checked)),
                matchWrapWithMargin(0, 0, 0, 7));

        llPage.addView(policyCheck("Tillat manuell SMTP-burn",
                "Manuell serverhandling er tilgjengelig når cPanel er konfigurert. Readiness gir anbefalinger, ikke sperrer. Hver burn krever eksplisitt bekreftelse og read-back.",
                SpamControlPolicy.PREF_SMTP_BURN_ENABLED,
                SpamControlPolicy.smtpBurnEnabled(this),
                checked -> SpamControlPolicy.setBoolean(this, SpamControlPolicy.PREF_SMTP_BURN_ENABLED, checked)),
                matchWrapWithMargin(0, 0, 0, 12));

        CardView safety = card(10, 1);
        LinearLayout sb = cardBody(14, 12);
        sb.addView(valueText("Automatisk flytting/sletting", 17f, true), matchWrap());
        sb.addView(bodyText("AV. Spamkontroll observerer og lærer. Denne release flytter eller sletter ikke mail automatisk."),
                matchWrapWithMargin(0, 5, 0, 0));
        safety.addView(sb, matchWrap());
        llPage.addView(safety, matchWrap());
    }

    private View policyCheck(String title, String description, String key,
                             boolean checked, CheckedAction action) {
        CardView card = card(10, 1);
        LinearLayout body = cardBody(14, 10);
        CheckBox box = new CheckBox(this);
        box.setText(title);
        box.setTextSize(16f);
        box.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.setChecked(checked);
        body.addView(box, matchWrap());
        body.addView(bodyText(description), matchWrapWithMargin(0, 3, 0, 0));
        box.setOnCheckedChangeListener((buttonView, isChecked) -> {
            action.apply(isChecked);
            tvStatus.setText(title + (isChecked ? " er slått på." : " er slått av."));
        });
        card.addView(body, matchWrap());
        return card;
    }

    private void renderSettings() {
        llPage.addView(sectionTitle("Innstillinger"), matchWrap());

        llPage.addView(sectionTitleSmall("Arbeidsflyt"), matchWrapWithMargin(0, 8, 0, 4));
        llPage.addView(uiCheck("Auto-advance",
                "Gå direkte videre etter et valg.", SpamControlPolicy.PREF_AUTO_ADVANCE,
                SpamControlPolicy.autoAdvance(this)), matchWrapWithMargin(0, 0, 0, 6));
        llPage.addView(uiCheck("Skjul ferdigvurderte",
                "Arbeidskøen viser det som faktisk gjenstår.", SpamControlPolicy.PREF_HIDE_REVIEWED,
                SpamControlPolicy.hideReviewed(this)), matchWrapWithMargin(0, 0, 0, 6));
        llPage.addView(uiCheck("Vis tekniske detaljer direkte",
                "Ellers ligger score og årsaker bak Detaljer.", SpamControlPolicy.PREF_SHOW_TECHNICAL,
                SpamControlPolicy.showTechnical(this)), matchWrapWithMargin(0, 0, 0, 12));

        CardView identity = card(10, 1);
        LinearLayout ib = cardBody(14, 12);
        ib.addView(valueText("Spamidentitet", 17f, true), matchWrap());
        ib.addView(bodyText("Låst hovedregel: normalisert avsendernavn + normalisert emne. " +
                "Adresse og Envelope-To kan rotere uten å skape ny familie."), matchWrapWithMargin(0, 5, 0, 0));
        identity.addView(ib, matchWrap());
        llPage.addView(identity, matchWrapWithMargin(0, 0, 0, 12));

        CardView historical = card(10, 1);
        LinearLayout hb = cardBody(14, 12);
        hb.addView(valueText("Historisk skanning", 17f, true), matchWrap());
        hb.addView(bodyText("Les eksisterende e-post inn i Spamkontroll. Spam-mappen blir en gjennomgangskilde; Innboks bygger alias- og avsenderhistorikk. Mappeplassering blir aldri automatisk spam/ikke-spam-sannhet."),
                matchWrapWithMargin(0, 5, 0, 0));
        Button scanBoth = primaryButton("Skann Spam + Innboks");
        scanBoth.setOnClickListener(v -> runHistoricalScan(true, true));
        hb.addView(scanBoth, matchWrapWithMargin(0, 8, 0, 0));
        LinearLayout scanRow = new LinearLayout(this);
        scanRow.setOrientation(LinearLayout.HORIZONTAL);
        Button scanSpam = secondaryButton("Kun Spam");
        scanSpam.setOnClickListener(v -> runHistoricalScan(false, true));
        scanRow.addView(scanSpam, weightedButton());
        Button scanInbox = secondaryButton("Kun Innboks");
        scanInbox.setOnClickListener(v -> runHistoricalScan(true, false));
        scanRow.addView(scanInbox, weightedButton());
        hb.addView(scanRow, matchWrapWithMargin(0, 5, 0, 0));
        historical.addView(hb, matchWrap());
        llPage.addView(historical, matchWrapWithMargin(0, 0, 0, 12));

        CardView cpanel = card(10, 1);
        LinearLayout cb = cardBody(14, 12);
        cb.addView(valueText("cPanel / SMTP", 17f, true), matchWrap());
        cb.addView(bodyText(SpamControlPolicy.hasCpanelConfig(this)
                ? "Konfigurert lokalt i denne appinstallasjonen."
                : "Ikke konfigurert. Alias-burn er derfor utilgjengelig."), matchWrapWithMargin(0, 5, 0, 0));
        Button configure = secondaryButton("Konfigurer cPanel");
        configure.setOnClickListener(v -> showCpanelDialog());
        cb.addView(configure, matchWrapWithMargin(0, 7, 0, 0));
        cpanel.addView(cb, matchWrap());
        llPage.addView(cpanel, matchWrapWithMargin(0, 0, 0, 12));

        CardView data = card(10, 1);
        LinearLayout db = cardBody(14, 12);
        db.addView(valueText("Data og læring", 17f, true), matchWrap());
        Button export = secondaryButton("Eksporter status");
        export.setOnClickListener(v -> exportStatus());
        db.addView(export, matchWrapWithMargin(0, 7, 0, 0));
        Button history = secondaryButton("Vis læringshistorikk");
        history.setOnClickListener(v -> showLearningHistory());
        db.addView(history, matchWrapWithMargin(0, 5, 0, 0));
        Button undo = secondaryButton("Angre siste valg");
        undo.setOnClickListener(v -> undoLatest());
        db.addView(undo, matchWrapWithMargin(0, 7, 0, 0));
        Button reset = secondaryButton("Nullstill all læring …");
        reset.setOnClickListener(v -> showResetDialog());
        db.addView(reset, matchWrapWithMargin(0, 5, 0, 0));
        data.addView(db, matchWrap());
        llPage.addView(data, matchWrap());
    }

    private String aliasLifecycleText(EntityAlias alias, AliasBurnPolicy.Result readiness) {
        if (alias == null || readiness == null)
            return "ukjent";
        switch (readiness.verdict) {
            case HEALTHY:
                return "Aliaset er aktivt. Ingen SMTP-handling nødvendig.";
            case REVIEW_COMPROMISE:
                return "Spam finnes, men alias-lekkasje er ikke avgjort.";
            case COMPROMISED:
                return "Kompromittert. Replacement anbefales før burn; du kan overstyre.";
            case ROTATE_FIRST:
                return "Bytt alias anbefales før burn; du kan overstyre.";
            case VERIFY_REPLACEMENT:
                return "Replacement er registrert, ikke verifisert. Du kan burne nå eller verifisere først.";
            case READY_TO_BURN:
                return SpamControlPolicy.hasCpanelConfig(this)
                        ? "Replacement er verifisert. SMTP-død er klar."
                        : "Replacement er verifisert. Konfigurer cPanel for SMTP-død.";
            case SERVER_PENDING:
                return "Serveroperasjon pågår.";
            case SMTP_DEAD:
                return "SMTP hard reject er verifisert (550).";
            case SERVER_FAILED:
                return "Siste serveroperasjon feilet. Åpne aliaset for detaljer.";
            default:
                return readiness.verdict.toString();
        }
    }

    private void showCompromiseReview(EntityAlias alias) {
        if (alias == null)
            return;
        new AlertDialog.Builder(this)
                .setTitle("Er aliaset kompromittert?")
                .setMessage(alias.address + "\n\nSpam er bekreftet, men det er ikke nok alene til å konkludere med at aliasadressen har lekket.")
                .setNegativeButton("Behold aktivt", (d, w) -> executor.execute(() -> {
                    boolean changed = SpamUndoManager.runAccountAction(
                            getApplicationContext(), alias.account_uuid,
                            SpamUndoManager.ACTION_ALIAS_KEEP_ACTIVE,
                            "Behold alias aktivt",
                            () -> AliasCompromiseReviewStore.markReviewedHealthy(
                                    getApplicationContext(), alias));
                    runOnUiThread(() -> {
                        tvStatus.setText(changed
                                ? "Aliaset beholdes aktivt. Ny spam-evidens kan åpne spørsmålet igjen."
                                : "Aliaset var allerede vurdert som aktivt.");
                        if (changed)
                            Snackbar.make(svContent, "Alias beholdt aktivt.", Snackbar.LENGTH_LONG)
                                    .setAction("ANGRE", v -> undoLatest()).show();
                        loadDashboardExtras(selectedAccount);
                        renderCurrent();
                    });
                }))
                .setPositiveButton("Marker kompromittert", (d, w) -> executor.execute(() -> {
                    boolean changed = SpamUndoManager.runAccountAction(
                            getApplicationContext(), alias.account_uuid,
                            SpamUndoManager.ACTION_ALIAS_COMPROMISED,
                            "Marker alias kompromittert",
                            () -> SpamIntelligenceDB.getInstance(getApplicationContext()).alias()
                                    .markCompromised(alias.account_uuid, alias.address) > 0);
                    runOnUiThread(() -> {
                        tvStatus.setText(changed
                                ? "Alias markert kompromittert."
                                : "Aliasstatus var allerede oppdatert.");
                        if (changed)
                            Snackbar.make(svContent, "Alias markert kompromittert.", Snackbar.LENGTH_LONG)
                                    .setAction("ANGRE", v -> undoLatest()).show();
                        loadDashboardExtras(selectedAccount);
                        renderCurrent();
                    });
                }))
                .show();
    }

    private void runHistoricalScan(boolean inbox, boolean junk) {
        EntityAccount account = selectedAccount;
        if (account == null)
            return;
        tvStatus.setText("Skanner eksisterende " +
                (inbox && junk ? "Spam + Innboks" : junk ? "Spam" : "Innboks") + " …");
        executor.execute(() -> {
            SpamHistoricalScanner.Result result = SpamHistoricalScanner.scan(
                    getApplicationContext(), account, inbox, junk);
            runOnUiThread(() -> {
                if (!isSelected(account) || isFinishing() || isDestroyed())
                    return;
                if (result.success) {
                    tvStatus.setText("Historisk skann ferdig · " + result.examined +
                            " lest · " + result.newlyImported + " nye observasjoner · " +
                            result.junk + " Spam · " + result.inbox + " Innboks" +
                            (result.skippedNoEnvelope > 0
                                    ? " · " + result.skippedNoEnvelope + " uten Envelope-To" : ""));
                    loadReviewQueue(account, true);
                    loadDashboardExtras(account);
                } else
                    tvStatus.setText("Historisk skann feilet: " + result.error);
            });
        });
    }

    private View actionHistoryCard(EntitySpamActionHistory action) {
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

    private View uiCheck(String title, String description, String key, boolean checked) {
        return policyCheck(title, description, key, checked, value -> {
            SpamControlPolicy.setBoolean(this, key, value);
            if (SpamControlPolicy.PREF_HIDE_REVIEWED.equals(key)) {
                EntityAccount account = selectedAccount;
                if (account != null)
                    loadReviewQueue(account, true);
            } else if (SpamControlPolicy.PREF_SHOW_TECHNICAL.equals(key) && section == Section.SETTINGS) {
                // Takes effect the next time Review renders.
            }
        });
    }

    private void showCpanelDialog() {
        LinearLayout form = dialogForm();
        EditText base = input("Base URL, f.eks. https://server:2083", SpamControlPolicy.cpanelBaseUrl(this));
        EditText user = input("cPanel-brukernavn", SpamControlPolicy.cpanelUsername(this));
        EditText token = input("API token", "");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (!SpamControlPolicy.cpanelToken(this).isEmpty())
            token.setHint("Token er allerede lagret. La stå tomt for å beholde.");
        form.addView(base, matchWrap());
        form.addView(user, matchWrap());
        form.addView(token, matchWrap());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("cPanel-konfigurasjon")
                .setMessage("Lagres lokalt i appens private preferences. Ingen credentials legges i repoet.")
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("Fjern", null)
                .setPositiveButton("Lagre", null)
                .create();
        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String newToken = token.getText().toString();
                if (newToken.isEmpty())
                    newToken = SpamControlPolicy.cpanelToken(this);
                SpamControlPolicy.setCpanelConfig(this,
                        base.getText().toString(), user.getText().toString(), newToken);
                tvStatus.setText(SpamControlPolicy.hasCpanelConfig(this)
                        ? "cPanel-konfigurasjon lagret." : "cPanel-konfigurasjonen er ufullstendig.");
                dialog.dismiss();
                renderCurrent();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                SpamControlPolicy.setCpanelConfig(this, "", "", "");
                tvStatus.setText("cPanel-konfigurasjon fjernet.");
                dialog.dismiss();
                renderCurrent();
            });
        });
        dialog.show();
    }

    private void undoLatest() {
        EntityAccount account = selectedAccount;
        if (account == null)
            return;
        tvStatus.setText("Angrer siste valg …");
        executor.execute(() -> {
            SpamUndoManager.UndoResult result = SpamUndoManager.undoLatest(
                    getApplicationContext(), account.uuid);
            runOnUiThread(() -> {
                if (!isSelected(account))
                    return;
                if (result == SpamUndoManager.UndoResult.APPLIED) {
                    tvStatus.setText("Siste valg er angret.");
                    loadReviewQueue(account, false);
                    loadDashboardExtras(account);
                } else if (result == SpamUndoManager.UndoResult.NOTHING_TO_UNDO)
                    tvStatus.setText("Det er ingen flere valg å angre.");
                else
                    tvStatus.setText("Kunne ikke angre siste valg.");
            });
        });
    }

    private void showResetDialog() {
        EntityAccount account = selectedAccount;
        if (account == null)
            return;
        new AlertDialog.Builder(this)
                .setTitle("Nullstill all læring?")
                .setMessage("Spam/ikke-spam-læring og spamgrupper blir blanke ark. Mail, aliasinventar, " +
                        "service/trusted domains, replacement og SMTP/cPanel-state beholdes. Nullstillingen kan angres.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Nullstill", (d, w) -> {
                    tvStatus.setText("Nullstiller læring …");
                    executor.execute(() -> {
                        boolean ok = SpamResetManager.resetLearning(getApplicationContext(), account.uuid);
                        runOnUiThread(() -> {
                            tvStatus.setText(ok ? "All spamlæring er nullstilt." : "Kunne ikke nullstille læringen.");
                            if (ok) {
                                Snackbar.make(svContent, "All spamlæring er nullstilt.", Snackbar.LENGTH_LONG)
                                        .setAction("ANGRE", v -> undoLatest()).show();
                                loadReviewQueue(account, false);
                                loadDashboardExtras(account);
                            }
                        });
                    });
                })
                .show();
    }

    private View technicalInline(SpamFamilyLabRepository.Candidate candidate) {
        CardView card = card(8, 1);
        LinearLayout body = cardBody(10, 9);
        body.addView(fieldLabel("TEKNISKE DETALJER"), matchWrap());
        body.addView(bodyText(technicalText(candidate)), matchWrapWithMargin(0, 4, 0, 0));
        card.addView(body, matchWrap());
        return card;
    }

    private void showTechnicalDetails(SpamFamilyLabRepository.Candidate candidate) {
        new AlertDialog.Builder(this)
                .setTitle("Tekniske detaljer")
                .setMessage(technicalText(candidate))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String technicalText(SpamFamilyLabRepository.Candidate candidate) {
        return "Meldings-ID: " + candidate.messageId +
                "\nAlias spam/legit: " + candidate.aliasSpamHits + " / " + candidate.aliasHamHits +
                "\nAlias spamstøtte: " + percent(candidate.aliasSpamSupport) +
                "\nAlias legitimitetsstøtte: " + percent(candidate.aliasHamSupport) +
                "\nAliasvurdering: " + empty(candidate.aliasVerdict, "ukjent") +
                "\nAliasårsaker: " + empty(candidate.aliasReasons, "ingen") +
                "\n\nSamlet spamstøtte: " + percent(candidate.overallSpamSupport) +
                "\nSamlet legitimitetsstøtte: " + percent(candidate.overallHamSupport) +
                "\nResultat: " + candidate.overallVerdict +
                "\nÅrsaker: " + (candidate.overallReasons.isEmpty()
                        ? "ingen" : TextUtils.join(", ", candidate.overallReasons)) +
                "\n\nEksakt familie-match: " + percent(candidate.score) +
                "\nUnsubscribe: " + (candidate.hasUnsubscribe ? "ja" : "nei");
    }

    private String reviewState(SpamFamilyLabRepository.Candidate candidate) {
        if (candidate.explicitHam)
            return "✓ Merket IKKE SPAM";
        if (candidate.confirmedFamilyId != null)
            return "✓ Bekreftet SPAM";
        if (candidate.predictedFamilyId != null)
            return "⚡ Eksakt kjent spamidentitet";
        if (candidate.overallVerdict == SpamDecisionScorer.Verdict.SUSPICIOUS)
            return "⚠ Mistenkelig aliastrafikk";
        if (candidate.overallVerdict == SpamDecisionScorer.Verdict.LIKELY_LEGIT)
            return "✓ Sterke legitimitetssignaler";
        return "Trenger din vurdering";
    }

    private String aliasSignal(SpamFamilyLabRepository.Candidate candidate) {
        if (candidate.aliasState == EntityAlias.STATE_COMPROMISED)
            return "⚠ Aliaset er markert kompromittert";
        if ("SUSPICIOUS".equals(candidate.aliasVerdict))
            return "⚠ Avsenderen passer dårlig med dette aliaset";
        if ("LIKELY_LEGIT".equals(candidate.aliasVerdict))
            return "✓ Avsenderen passer godt med dette aliaset";
        if (candidate.aliasSpamHits > 0)
            return "⚠ Aliaset har tidligere mottatt bekreftet spam";
        return "Aliasforholdet er ikke avgjort";
    }

    private String aliasState(EntityAlias alias) {
        if (alias.smtp_reject_state == EntityAlias.SMTP_REJECT_VERIFIED)
            return "SMTP-DEAD";
        switch (alias.state) {
            case EntityAlias.STATE_COMPROMISED: return "COMPROMISED";
            case EntityAlias.STATE_REPLACED: return "REPLACED";
            case EntityAlias.STATE_DISABLED: return "DISABLED";
            case EntityAlias.STATE_IGNORED: return "IGNORED";
            case EntityAlias.STATE_ACTIVE:
            default: return "ACTIVE";
        }
    }

    private String smtpState(EntityAlias alias) {
        switch (alias.smtp_reject_state) {
            case EntityAlias.SMTP_REJECT_PENDING: return "PENDING";
            case EntityAlias.SMTP_REJECT_VERIFIED: return "VERIFIED DEAD";
            case EntityAlias.SMTP_REJECT_FAILED: return "FAILED";
            case EntityAlias.SMTP_RESTORE_PENDING: return "RESTORE PENDING";
            case EntityAlias.SMTP_REJECT_NONE:
            default: return "ingen hard-fail";
        }
    }

    private int statePosition(Integer state) {
        int value = state == null ? EntityAlias.STATE_ACTIVE : state;
        if (value == EntityAlias.STATE_COMPROMISED) return 1;
        if (value == EntityAlias.STATE_REPLACED) return 2;
        if (value == EntityAlias.STATE_DISABLED) return 3;
        if (value == EntityAlias.STATE_IGNORED) return 4;
        return 0;
    }

    private int stateFromPosition(int position) {
        if (position == 1) return EntityAlias.STATE_COMPROMISED;
        if (position == 2) return EntityAlias.STATE_REPLACED;
        if (position == 3) return EntityAlias.STATE_DISABLED;
        if (position == 4) return EntityAlias.STATE_IGNORED;
        return EntityAlias.STATE_ACTIVE;
    }

    private String jsonArrayToCsv(String json) {
        if (TextUtils.isEmpty(json))
            return "";
        try {
            JSONArray values = new JSONArray(json);
            List<String> result = new ArrayList<>();
            for (int i = 0; i < values.length(); i++) {
                String value = values.optString(i, null);
                if (!TextUtils.isEmpty(value))
                    result.add(value.trim());
            }
            return TextUtils.join(", ", result);
        } catch (Throwable ex) {
            return "";
        }
    }

    private String csvToJsonArray(String text) {
        JSONArray result = new JSONArray();
        if (text != null)
            for (String raw : text.split("[,;\\n]")) {
                String value = raw.trim();
                if (!value.isEmpty())
                    result.put(value);
            }
        return result.toString();
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
        return raw.contains("@") ? new SenderParts(null, raw.trim()) : new SenderParts(raw.trim(), null);
    }

    private boolean isSelected(EntityAccount account) {
        return account != null && selectedAccount != null &&
                account.uuid != null && account.uuid.equals(selectedAccount.uuid);
    }

    private String accountLabel(EntityAccount account) {
        if (account == null)
            return "konto";
        if (!TextUtils.isEmpty(account.name))
            return account.name.trim();
        if (!TextUtils.isEmpty(account.user))
            return account.user.trim();
        return TextUtils.isEmpty(account.host) ? account.uuid : account.host;
    }

    private View statCard(String label, String value, String description,
                          String action, View.OnClickListener listener) {
        CardView card = card(12, 2);
        LinearLayout body = cardBody(15, 13);
        body.addView(fieldLabel(label.toUpperCase(Locale.ROOT)), matchWrap());
        body.addView(valueText(value, 30f, true), matchWrapWithMargin(0, 3, 0, 0));
        body.addView(bodyText(description), matchWrapWithMargin(0, 3, 0, 0));
        if (action != null) {
            Button button = primaryButton(action);
            button.setOnClickListener(listener);
            body.addView(button, matchWrapWithMargin(0, 8, 0, 0));
        }
        card.addView(body, matchWrap());
        return card;
    }

    private View attentionCard(String title, String description,
                               String action, View.OnClickListener listener) {
        CardView card = card(10, 1);
        LinearLayout body = cardBody(13, 10);
        body.addView(valueText(title, 16f, true), matchWrap());
        body.addView(bodyText(description), matchWrapWithMargin(0, 3, 0, 0));
        Button button = secondaryButton(action);
        button.setOnClickListener(listener);
        body.addView(button, wrapWrapWithMargin(0, 7, 0, 0));
        card.addView(body, matchWrap());
        return card;
    }

    private View infoCard(String title, String value) {
        CardView card = card(9, 1);
        LinearLayout body = cardBody(13, 9);
        body.addView(valueText(title, 15f, true), matchWrap());
        body.addView(bodyText(value), matchWrapWithMargin(0, 2, 0, 0));
        card.addView(body, matchWrap());
        return card;
    }

    private LinearLayout dialogForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        form.setPadding(pad, dp(4), pad, 0);
        return form;
    }

    private EditText input(String hint, String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        if (value != null)
            input.setText(value);
        return input;
    }

    private String cleanNullable(String value) {
        if (value == null)
            return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
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
        view.setTextSize(22f);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView sectionTitleSmall(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(17f);
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

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(dp(44));
        return button;
    }

    private Button primaryButton(String text) {
        Button button = secondaryButton(text);
        button.setMinHeight(dp(52));
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
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

    private String percent(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0)
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
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

    private interface CheckedAction {
        void apply(boolean checked);
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

from pathlib import Path

ROOT = Path('app/src/main/java/eu/faircode/email')


def once(text, old, new, label):
    c = text.count(old)
    if c != 1:
        raise SystemExit(f'{label}: expected 1 match, got {c}')
    return text.replace(old, new, 1)

# ------------------------------------------------------------------
# Persistent log: add DEBUG + TRACE levels.
# ------------------------------------------------------------------
p = ROOT / 'SpamControlLog.java'
text = p.read_text()
text = once(text,
'''    public static void i(Context context, String category, String message) {\n        append(context, "INFO", category, message, null);\n    }\n\n    public static void w(Context context, String category, String message) {\n''',
'''    public static void i(Context context, String category, String message) {\n        append(context, "INFO", category, message, null);\n    }\n\n    public static void d(Context context, String category, String message) {\n        append(context, "DEBUG", category, message, null);\n    }\n\n    public static void t(Context context, String category, String message) {\n        append(context, "TRACE", category, message, null);\n    }\n\n    public static void w(Context context, String category, String message) {\n''', 'log-levels')
p.write_text(text)

# ------------------------------------------------------------------
# cPanel: INFO stays outcome-level; DEBUG shows request/response shape;
# TRACE carries the sanitized response body.
# ------------------------------------------------------------------
p = ROOT / 'CpanelAliasActuator.java'
text = p.read_text()
text = once(text,
'''        SpamControlLog.i(context, "CPANEL",\n                "REQUEST " + module + "/" + function + " endpoint=" + endpoint);\n''',
'''        SpamControlLog.d(context, "CPANEL",\n                "> GET " + module + "/" + function + " endpoint=" + endpoint);\n''', 'cpanel-request-debug')
text = once(text,
'''            SpamControlLog.i(context, "CPANEL",\n                    "RESPONSE HTTP " + code + " final=" + connection.getURL() +\n                            " contentType=" + connection.getContentType() +\n                            " topLevel=" + topLevelKeys(root) +\n                            " body=" + SpamControlLog.sanitize(body));\n''',
'''            SpamControlLog.d(context, "CPANEL",\n                    "< HTTP " + code + " final=" + connection.getURL() +\n                            " contentType=" + connection.getContentType() +\n                            " topLevel=" + topLevelKeys(root));\n            SpamControlLog.t(context, "CPANEL",\n                    "< BODY " + SpamControlLog.sanitize(body));\n''', 'cpanel-response-levels')
p.write_text(text)

# ------------------------------------------------------------------
# Historical scanner: page-level DEBUG + per-message TRACE.
# ------------------------------------------------------------------
p = ROOT / 'SpamHistoricalScanner.java'
text = p.read_text()
text = once(text,
'''                if (page == null || page.isEmpty())\n                    break;\n\n                for (EntityMessage message : page) {\n''',
'''                if (page == null || page.isEmpty())\n                    break;\n\n                SpamControlLog.d(app, "SCAN",\n                        "< PAGE afterMessageId=" + afterMessageId + " rows=" + page.size());\n\n                for (EntityMessage message : page) {\n''', 'scan-page-debug')
text = once(text,
'''                    if (afterMessage != null) {\n                        indexed++;\n                        if (beforeMessage == null)\n                            newlyIndexed++;\n                    }\n\n                    if (message.deliveredto == null) {\n''',
'''                    if (afterMessage != null) {\n                        indexed++;\n                        if (beforeMessage == null)\n                            newlyIndexed++;\n                    }\n                    SpamControlLog.t(app, "SCAN",\n                            "INDEX message=" + message.id +\n                                    " folder=" + folder.type +\n                                    " new=" + (beforeMessage == null) +\n                                    " envelope=" + (message.deliveredto == null ? "missing" : "present"));\n\n                    if (message.deliveredto == null) {\n''', 'scan-message-trace')
p.write_text(text)

# ------------------------------------------------------------------
# Main UI: first-class Terminal tab + canonical scan counts.
# ------------------------------------------------------------------
p = ROOT / 'ActivitySpamControl.java'
text = p.read_text()
text = once(text,
'''import android.os.Bundle;\n''',
'''import android.os.Bundle;\nimport android.os.Handler;\nimport android.os.Looper;\n''', 'activity-handler-imports')
text = once(text,
'''        FAMILIES("Spamgrupper"),\n        RULES("Regler"),\n        SETTINGS("Innstillinger");\n''',
'''        FAMILIES("Spamgrupper"),\n        RULES("Regler"),\n        SETTINGS("Innstillinger"),\n        TERMINAL("Terminal");\n''', 'terminal-section-enum')
text = once(text,
'''    private boolean descriptorsLoading = false;\n\n    @Override\n    protected void onCreate(Bundle savedInstanceState) {\n''',
'''    private boolean descriptorsLoading = false;\n\n    private final Handler terminalHandler = new Handler(Looper.getMainLooper());\n    private TextView terminalOutput;\n    private boolean terminalLive = true;\n    private int terminalVerbosity = 2; // 0 ERROR, 1 WARN, 2 INFO, 3 DEBUG, 4 TRACE\n    private String terminalSearch = "";\n    private final Runnable terminalRefresh = new Runnable() {\n        @Override\n        public void run() {\n            if (section != Section.TERMINAL || !terminalLive || isFinishing() || isDestroyed())\n                return;\n            refreshTerminalOutput();\n            terminalHandler.postDelayed(this, 1000L);\n        }\n    };\n\n    @Override\n    protected void onCreate(Bundle savedInstanceState) {\n''', 'terminal-fields')
text = once(text,
'''    @Override\n    public boolean onOptionsItemSelected(MenuItem item) {\n        if (item.getItemId() == android.R.id.home) {\n            finish();\n            return true;\n        }\n        return super.onOptionsItemSelected(item);\n    }\n\n    private View buildUi() {\n''',
'''    @Override\n    public boolean onOptionsItemSelected(MenuItem item) {\n        if (item.getItemId() == android.R.id.home) {\n            finish();\n            return true;\n        }\n        return super.onOptionsItemSelected(item);\n    }\n\n    @Override\n    protected void onDestroy() {\n        terminalHandler.removeCallbacks(terminalRefresh);\n        super.onDestroy();\n    }\n\n    private View buildUi() {\n''', 'terminal-destroy')
text = once(text,
'''    private void setSection(Section next) {\n        section = next;\n        updateNav();\n        if (next == Section.REVIEW && selectedAccount != null)\n            loadReviewQueue(selectedAccount, reviewQueue.isEmpty());\n        else\n            renderCurrent();\n        svContent.post(() -> svContent.scrollTo(0, 0));\n    }\n''',
'''    private void setSection(Section next) {\n        terminalHandler.removeCallbacks(terminalRefresh);\n        section = next;\n        updateNav();\n        if (next == Section.REVIEW && selectedAccount != null)\n            loadReviewQueue(selectedAccount, reviewQueue.isEmpty());\n        else\n            renderCurrent();\n        if (next == Section.TERMINAL && terminalLive)\n            terminalHandler.post(terminalRefresh);\n        svContent.post(() -> svContent.scrollTo(0, 0));\n    }\n''', 'terminal-set-section')
text = once(text,
'''            case SETTINGS:\n                renderSettings();\n                break;\n            case OVERVIEW:\n''',
'''            case SETTINGS:\n                renderSettings();\n                break;\n            case TERMINAL:\n                renderTerminal();\n                break;\n            case OVERVIEW:\n''', 'terminal-render-switch')

# Canonical scan counts + richer report.
old = '''        executor.execute(() -> {\n            DaoAlias dao = SpamIntelligenceDB.getInstance(getApplicationContext()).alias();\n            boolean includeReviewed = !SpamControlPolicy.hideReviewed(getApplicationContext());\n            int beforeReview = dao.countReviewQueue(account.uuid, includeReviewed);\n            List<EntityAlias> beforeAliasesList = dao.getAliases(account.uuid);\n            int beforeAliases = beforeAliasesList == null ? 0 : beforeAliasesList.size();\n\n            SpamHistoricalScanner.Result result = SpamHistoricalScanner.scan(\n                    getApplicationContext(), account, inbox, junk);\n\n            int afterReview = dao.countReviewQueue(account.uuid, includeReviewed);\n            List<EntityAlias> afterAliasesList = dao.getAliases(account.uuid);\n            int afterAliases = afterAliasesList == null ? 0 : afterAliasesList.size();\n'''
new = '''        executor.execute(() -> {\n            SpamIntelligenceDB intelligence = SpamIntelligenceDB.getInstance(getApplicationContext());\n            DaoAlias dao = intelligence.alias();\n            DaoSpamMessage messageDao = intelligence.message();\n            boolean includeReviewed = !SpamControlPolicy.hideReviewed(getApplicationContext());\n            int beforeReview = messageDao.countReviewQueue(account.uuid, includeReviewed);\n            int beforeIndexed = messageDao.count(account.uuid);\n            List<EntityAlias> beforeAliasesList = dao.getAliases(account.uuid);\n            int beforeAliases = beforeAliasesList == null ? 0 : beforeAliasesList.size();\n\n            SpamHistoricalScanner.Result result = SpamHistoricalScanner.scan(\n                    getApplicationContext(), account, inbox, junk);\n\n            int afterReview = messageDao.countReviewQueue(account.uuid, includeReviewed);\n            int afterIndexed = messageDao.count(account.uuid);\n            List<EntityAlias> afterAliasesList = dao.getAliases(account.uuid);\n            int afterAliases = afterAliasesList == null ? 0 : afterAliasesList.size();\n'''
text = once(text, old, new, 'scan-canonical-counts')
text = once(text,
'''                            " review=" + beforeReview + "->" + afterReview +\n                            " aliases=" + beforeAliases + "->" + afterAliases +\n                            " examined=" + result.examined + " observed=" + result.observed +\n                            " new=" + result.newlyImported +\n                            " noEnvelope=" + result.skippedNoEnvelope +\n''',
'''                            " review=" + beforeReview + "->" + afterReview +\n                            " indexed=" + beforeIndexed + "->" + afterIndexed +\n                            " aliases=" + beforeAliases + "->" + afterAliases +\n                            " examined=" + result.examined +\n                            " indexedThisScan=" + result.indexed +\n                            " newlyIndexed=" + result.newlyIndexed +\n                            " aliasObserved=" + result.observed +\n                            " aliasNew=" + result.newlyImported +\n                            " noEnvelope=" + result.skippedNoEnvelope +\n''', 'scan-log-rich')
text = once(text,
'''                    report.append("Kilde: ").append(sources)\n                            .append("\\nLest: ").append(result.examined)\n                            .append("\\nRe-evaluert med Envelope-To: ").append(result.observed)\n                            .append("\\nNye observasjoner: ").append(result.newlyImported)\n                            .append("\\nAllerede kjent / re-evaluert: ")\n                            .append(Math.max(0, result.observed - result.newlyImported))\n                            .append("\\nSpam-mappe: ").append(result.junk)\n                            .append("\\nInnboks: ").append(result.inbox)\n                            .append("\\nUten Envelope-To: ").append(result.skippedNoEnvelope)\n                            .append("\\nMangler mappe: ").append(result.missingFolder)\n                            .append("\\n\\nGjennomgangskø: ").append(beforeReview)\n                            .append(" → ").append(afterReview)\n                            .append("\\nAliaser: ").append(beforeAliases)\n                            .append(" → ").append(afterAliases);\n''',
'''                    report.append("Kilde: ").append(sources)\n                            .append("\\nRå meldinger lest: ").append(result.examined)\n                            .append("\\nCanonical message-state indeksert: ").append(result.indexed)\n                            .append("\\nNye canonical meldinger: ").append(result.newlyIndexed)\n                            .append("\\nAlias-beriket med Envelope-To: ").append(result.observed)\n                            .append("\\nNye alias-observasjoner: ").append(result.newlyImported)\n                            .append("\\nSpam-mappe: ").append(result.junk)\n                            .append("\\nInnboks: ").append(result.inbox)\n                            .append("\\nUten Envelope-To (fortsatt indeksert): ").append(result.skippedNoEnvelope)\n                            .append("\\nMangler mappe: ").append(result.missingFolder)\n                            .append("\\n\\nMessage-index: ").append(beforeIndexed)\n                            .append(" → ").append(afterIndexed)\n                            .append("\\nGjennomgangskø: ").append(beforeReview)\n                            .append(" → ").append(afterReview)\n                            .append("\\nAliaser: ").append(beforeAliases)\n                            .append(" → ").append(afterAliases);\n''', 'scan-report-rich')
text = once(text,
'''                    if (result.newlyImported == 0 && beforeReview == afterReview && beforeAliases == afterAliases)\n                        report.append("\\n\\nIngen ny synlig state ble opprettet. Dette betyr vanligvis at AliasBackfill allerede hadde importert de samme meldingene. Se Diagnostikklogg for detaljene.");\n''',
'''                    if (result.newlyIndexed == 0 && beforeReview == afterReview && beforeIndexed == afterIndexed)\n                        report.append("\\n\\nIngen ny canonical message-state ble opprettet. Terminal viser nøyaktig hva skanneren fant og hvorfor køen eventuelt ikke endret seg.");\n''', 'scan-zero-explanation')

# Terminal UI methods inserted before existing diagnostics dialog.
anchor = '''    private void showDiagnosticsLog() {\n'''
methods = '''    private void renderTerminal() {
        llPage.addView(sectionTitle("Terminal"), matchWrap());
        llPage.addView(bodyText("Live operasjonslogg for Spamkontroll. Credentials og Authorization-header blir aldri logget. TRACE kan inneholde alias/endepunkter og er ment for lokal feilsøking."),
                matchWrapWithMargin(0, 2, 0, 8));

        LinearLayout levelRow = new LinearLayout(this);
        levelRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] levels = {"ERROR", "WARN", "INFO", "DEBUG", "TRACE"};
        Spinner level = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, levels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        level.setAdapter(adapter);
        level.setSelection(Math.max(0, Math.min(4, terminalVerbosity)));
        level.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                terminalVerbosity = position;
                refreshTerminalOutput();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        levelRow.addView(valueText("Verbosity  ", 14f, true), wrapWrap());
        levelRow.addView(level, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        llPage.addView(levelRow, matchWrap());

        EditText search = input("Søk i terminal", terminalSearch);
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                terminalSearch = s == null ? "" : s.toString();
                refreshTerminalOutput();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        llPage.addView(search, matchWrapWithMargin(0, 5, 0, 5));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button live = secondaryButton(terminalLive ? "⏸ Pause" : "▶ Live");
        live.setOnClickListener(v -> {
            terminalLive = !terminalLive;
            terminalHandler.removeCallbacks(terminalRefresh);
            if (terminalLive)
                terminalHandler.post(terminalRefresh);
            renderCurrent();
        });
        controls.addView(live, weightedButton());
        Button refresh = secondaryButton("↻ Refresh");
        refresh.setOnClickListener(v -> refreshTerminalOutput());
        controls.addView(refresh, weightedButton());
        Button share = secondaryButton("Eksporter");
        share.setOnClickListener(v -> shareTerminalFiltered());
        controls.addView(share, weightedButton());
        llPage.addView(controls, matchWrap());

        Button clear = secondaryButton("Tøm terminal");
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Tøm Terminal?")
                .setMessage("Dette sletter den lokale diagnostikkloggen. Mail og Spamkontroll-data påvirkes ikke.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Tøm", (d, w) -> {
                    SpamControlLog.clear(getApplicationContext());
                    refreshTerminalOutput();
                }).show());
        llPage.addView(clear, matchWrapWithMargin(0, 5, 0, 7));

        terminalOutput = bodyText("");
        terminalOutput.setTypeface(Typeface.MONOSPACE);
        terminalOutput.setTextIsSelectable(true);
        terminalOutput.setTextSize(12f);
        terminalOutput.setPadding(dp(8), dp(8), dp(8), dp(16));
        llPage.addView(terminalOutput, matchWrap());
        refreshTerminalOutput();
    }

    private void refreshTerminalOutput() {
        if (terminalOutput == null || section != Section.TERMINAL)
            return;
        String filtered = filteredTerminalLog();
        terminalOutput.setText(TextUtils.isEmpty(filtered)
                ? "$ _\\n(ingen logglinjer matcher filteret)" : "$ spam-control --live\\n" + filtered);
    }

    private String filteredTerminalLog() {
        String raw = SpamControlLog.read(getApplicationContext());
        if (TextUtils.isEmpty(raw))
            return "";
        String query = terminalSearch == null ? "" : terminalSearch.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (String line : raw.split("\\n")) {
            if (line == null || line.isEmpty())
                continue;
            int level = terminalLineLevel(line);
            if (level > terminalVerbosity)
                continue;
            if (!query.isEmpty() && !line.toLowerCase(Locale.ROOT).contains(query))
                continue;
            if (out.length() > 0)
                out.append('\\n');
            out.append(line);
        }
        return out.toString();
    }

    private int terminalLineLevel(String line) {
        if (line.contains("[ERROR]")) return 0;
        if (line.contains("[WARN]")) return 1;
        if (line.contains("[INFO]")) return 2;
        if (line.contains("[DEBUG]")) return 3;
        if (line.contains("[TRACE]")) return 4;
        return 2;
    }

    private void shareTerminalFiltered() {
        String text = filteredTerminalLog();
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "Spamkontroll Terminal");
        share.putExtra(Intent.EXTRA_TEXT, "Verbosity=" + terminalVerbosity +
                " search=" + empty(terminalSearch, "(ingen)") + "\\n\\n" +
                (TextUtils.isEmpty(text) ? "Ingen logglinjer matcher filteret." : text));
        startActivity(Intent.createChooser(share, "Eksporter Spamkontroll Terminal"));
    }

''' + anchor
text = once(text, anchor, methods, 'terminal-methods')
p.write_text(text)

print('patched terminal observability')

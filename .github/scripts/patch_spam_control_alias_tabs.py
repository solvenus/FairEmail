from pathlib import Path
import re

path = Path('app/src/main/java/eu/faircode/email/ActivitySpamControl.java')
text = path.read_text(encoding='utf-8')

old_field = '''    private List<SpamFamilyLabRepository.Candidate> reviewQueue = new ArrayList<>();\n    private int reviewIndex = 0;'''
new_field = '''    private List<SpamFamilyLabRepository.Candidate> reviewQueue = new ArrayList<>();\n    private int aliasBucketSelection = 0; // 0=spam/problem, 1=legitimate, 2=unresolved\n    private int reviewIndex = 0;'''
if text.count(old_field) != 1:
    raise SystemExit('alias bucket state field signature mismatch')
text = text.replace(old_field, new_field, 1)

pattern = re.compile(r'''    private void renderAliases\(\) \{.*?\n    private View aliasCard\(EntityAlias alias\) \{''', re.S)
match = pattern.search(text)
if not match:
    raise SystemExit('renderAliases block not found')

replacement = r'''    private void renderAliases() {
        llPage.addView(sectionTitle("Aliaser"), matchWrap());
        TextView intro = bodyText("Spam-/kompromitterte, legitime og uavklarte aliaser er separate arbeidsflater. Kun én liste vises om gangen.");
        intro.setPadding(0, dp(2), 0, dp(8));
        llPage.addView(intro, matchWrap());

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Søk i valgt aliasgruppe");
        llPage.addView(search, matchWrapWithMargin(0, 0, 0, 8));

        LinearLayout bucketRow = new LinearLayout(this);
        bucketRow.setOrientation(LinearLayout.HORIZONTAL);
        Button spam = secondaryButton("Spam / kompromitterte (" + countAliasBucket(0) + ")");
        Button legit = secondaryButton("Legitime (" + countAliasBucket(1) + ")");
        Button unresolved = secondaryButton("Uavklarte (" + countAliasBucket(2) + ")");
        bucketRow.addView(spam, weightedButton());
        bucketRow.addView(legit, weightedButton());
        bucketRow.addView(unresolved, weightedButton());
        llPage.addView(bucketRow, matchWrapWithMargin(0, 0, 0, 8));

        TextView description = bodyText("");
        description.setPadding(0, 0, 0, dp(7));
        llPage.addView(description, matchWrap());

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        llPage.addView(list, matchWrap());

        final Button[] buttons = {spam, legit, unresolved};
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
            return "Spamtrafikk, kompromitterte/erstattede aliaser og SMTP-styrte aliaser.";
        if (bucket == 1)
            return "Aktive aliaser med legitim historikk og uten spamtrafikk.";
        return "Aliaser som ennå ikke har nok menneskelig læring til å være legitime eller spamrammede.";
    }

    private String aliasBucketEmptyText(int bucket) {
        if (bucket == 0)
            return "Ingen spam-/kompromitterte aliaser matcher søket.";
        if (bucket == 1)
            return "Ingen legitime aliaser matcher søket.";
        return "Ingen uavklarte aliaser matcher søket.";
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

text = text[:match.start()] + replacement + text[match.end():]
path.write_text(text, encoding='utf-8')
print('alias tabs patch prepared')

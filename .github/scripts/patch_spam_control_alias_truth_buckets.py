from pathlib import Path

path = Path('app/src/main/java/eu/faircode/email/ActivitySpamControl.java')
text = path.read_text(encoding='utf-8')

def one(old, new, label):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly 1 match, got {count}')
    text = text.replace(old, new, 1)
    print('patched', label)

one(
'''        LinearLayout bucketRow = new LinearLayout(this);\n        bucketRow.setOrientation(LinearLayout.HORIZONTAL);\n        Button spam = secondaryButton("Spam / kompromitterte (" + countAliasBucket(0) + ")");\n        Button legit = secondaryButton("Legitime (" + countAliasBucket(1) + ")");\n        Button unresolved = secondaryButton("Uavklarte (" + countAliasBucket(2) + ")");\n        bucketRow.addView(spam, weightedButton());\n        bucketRow.addView(legit, weightedButton());\n        bucketRow.addView(unresolved, weightedButton());\n        llPage.addView(bucketRow, matchWrapWithMargin(0, 0, 0, 8));''',
'''        LinearLayout bucketRowTop = new LinearLayout(this);\n        bucketRowTop.setOrientation(LinearLayout.HORIZONTAL);\n        Button spam = secondaryButton("Spam / kompromitterte (" + countAliasBucket(0) + ")");\n        Button legit = secondaryButton("Legitime (" + countAliasBucket(1) + ")");\n        bucketRowTop.addView(spam, weightedButton());\n        bucketRowTop.addView(legit, weightedButton());\n        llPage.addView(bucketRowTop, matchWrapWithMargin(0, 0, 0, 4));\n\n        LinearLayout bucketRowBottom = new LinearLayout(this);\n        bucketRowBottom.setOrientation(LinearLayout.HORIZONTAL);\n        Button unresolved = secondaryButton("Uavklarte (" + countAliasBucket(2) + ")");\n        Button inactive = secondaryButton("Inaktive (" + countAliasBucket(3) + ")");\n        bucketRowBottom.addView(unresolved, weightedButton());\n        bucketRowBottom.addView(inactive, weightedButton());\n        llPage.addView(bucketRowBottom, matchWrapWithMargin(0, 0, 0, 8));''',
'four semantic alias buckets')

one(
'''        final Button[] buttons = {spam, legit, unresolved};''',
'''        final Button[] buttons = {spam, legit, unresolved, inactive};''',
'include inactive tab state')

one(
'''        unresolved.setOnClickListener(v -> {\n            aliasBucketSelection = 2;\n            repopulate[0].run();\n        });\n        search.addTextChangedListener(new TextWatcher() {''',
'''        unresolved.setOnClickListener(v -> {\n            aliasBucketSelection = 2;\n            repopulate[0].run();\n        });\n        inactive.setOnClickListener(v -> {\n            aliasBucketSelection = 3;\n            repopulate[0].run();\n        });\n        search.addTextChangedListener(new TextWatcher() {''',
'inactive tab action')

one(
'''    private String aliasBucketDescription(int bucket) {\n        if (bucket == 0)\n            return "Spamtrafikk, kompromitterte/erstattede aliaser og SMTP-styrte aliaser.";\n        if (bucket == 1)\n            return "Aktive aliaser med Innboks-trafikk eller eksplisitt Ikke spam, uten spam/problemstate.";\n        return "Aliaser som foreløpig mangler nok sunn trafikk eller spam-evidens til å plasseres sikkert.";\n    }\n\n    private String aliasBucketEmptyText(int bucket) {\n        if (bucket == 0)\n            return "Ingen spam-/kompromitterte aliaser matcher søket.";\n        if (bucket == 1)\n            return "Ingen legitime aliaser matcher søket.";\n        return "Ingen uavklarte aliaser matcher søket.";\n    }''',
'''    private String aliasBucketDescription(int bucket) {\n        if (bucket == 0)\n            return "Kompromitterte/erstattede aliaser, SMTP-styrte aliaser og uløste kompromissavgjørelser.";\n        if (bucket == 1)\n            return "Aktive legitime aliaser. En spam-mail flytter ikke aliaset hitfra når lekkasje allerede er avkreftet.";\n        if (bucket == 2)\n            return "Aktive aliaser som foreløpig mangler nok evidens til en sikker plassering.";\n        return "Aliaser du eksplisitt har satt til DISABLED eller IGNORED.";\n    }\n\n    private String aliasBucketEmptyText(int bucket) {\n        if (bucket == 0)\n            return "Ingen spam-/kompromitterte aliaser matcher søket.";\n        if (bucket == 1)\n            return "Ingen legitime aliaser matcher søket.";\n        if (bucket == 2)\n            return "Ingen uavklarte aliaser matcher søket.";\n        return "Ingen inaktive aliaser matcher søket.";\n    }''',
'truthful bucket descriptions')

old_bucket = '''    /** 0=spam/problem, 1=legitimate, 2=unresolved. */\n    private int aliasBucket(EntityAlias alias) {\n        int spam = alias.spam_hits == null ? 0 : alias.spam_hits;\n        int ham = alias.ham_hits == null ? 0 : alias.ham_hits;\n        int smtp = alias.smtp_reject_state == null\n                ? EntityAlias.SMTP_REJECT_NONE : alias.smtp_reject_state;\n\n        if (spam > 0 ||\n                alias.state == EntityAlias.STATE_COMPROMISED ||\n                alias.state == EntityAlias.STATE_REPLACED ||\n                smtp != EntityAlias.SMTP_REJECT_NONE)\n            return 0;\n\n        if (alias.state == EntityAlias.STATE_ACTIVE &&\n                (ham > 0 || aliasHasInboxTraffic(alias)))\n            return 1;\n\n        return 2;\n    }'''
new_bucket = '''    /** 0=spam/problem, 1=legitimate, 2=unresolved, 3=inactive. */\n    private int aliasBucket(EntityAlias alias) {\n        int spam = alias.spam_hits == null ? 0 : alias.spam_hits;\n        int ham = alias.ham_hits == null ? 0 : alias.ham_hits;\n        int smtp = alias.smtp_reject_state == null\n                ? EntityAlias.SMTP_REJECT_NONE : alias.smtp_reject_state;\n\n        // Physical SMTP state and explicit compromised/replaced lifecycle are\n        // problem work regardless of the message-level spam counters.\n        if (alias.state == EntityAlias.STATE_COMPROMISED ||\n                alias.state == EntityAlias.STATE_REPLACED ||\n                smtp != EntityAlias.SMTP_REJECT_NONE)\n            return 0;\n\n        if (alias.state == EntityAlias.STATE_DISABLED ||\n                alias.state == EntityAlias.STATE_IGNORED)\n            return 3;\n\n        boolean compromiseReview = alias.state == EntityAlias.STATE_ACTIVE &&\n                spam > 0 && SpamControlPolicy.markAliasCompromised(this) &&\n                AliasCompromiseReviewStore.needsReview(this, alias);\n        if (compromiseReview)\n            return 0;\n\n        if (alias.state == EntityAlias.STATE_ACTIVE) {\n            if (ham > 0 || aliasHasInboxTraffic(alias))\n                return 1;\n\n            // With compromise intelligence enabled, an active alias with spam\n            // whose current spam count no longer needs review has explicitly\n            // been resolved healthy by the user/policy. Spam truth does not\n            // become alias-leak truth.\n            if (spam > 0 && SpamControlPolicy.markAliasCompromised(this))\n                return 1;\n        }\n\n        return 2;\n    }'''
one(old_bucket, new_bucket, 'separate spam truth from alias truth')

path.write_text(text, encoding='utf-8')
print('alias truth bucket patch complete')

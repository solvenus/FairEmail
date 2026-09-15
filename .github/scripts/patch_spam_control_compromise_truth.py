from pathlib import Path


def patch(path_s, replacements):
    path = Path(path_s)
    text = path.read_text(encoding='utf-8')
    for label, old, new in replacements:
        count = text.count(old)
        if count != 1:
            raise SystemExit(f'{path_s} {label}: expected exactly 1 match, got {count}')
        text = text.replace(old, new, 1)
        print('patched', path_s, label)
    path.write_text(text, encoding='utf-8')

patch('app/src/main/java/eu/faircode/email/AliasBurnPolicy.java', [
    ('advisory doc',
''' * A spam hit alone never authorizes SMTP burn. The alias lifecycle must itself\n * be COMPROMISED/REPLACED and the configured replacement must be verified from\n * observed legitimate traffic before the destructive server action is ready.''',
''' * This policy computes advisory lifecycle/readiness, not authorization. The\n * human can explicitly veto replacement/readiness advice and request SMTP burn.\n * A spam hit alone is never proof that the alias itself is compromised.'''),
    ('review input',
'''        public boolean aliasCompromised;\n        public boolean serviceDomainKnown;''',
'''        public boolean aliasCompromised;\n        public boolean compromiseNeedsReview;\n        public boolean serviceDomainKnown;'''),
    ('noncompromised semantics',
'''        if (!compromised) {\n            if (spam > 0) {\n                reasons.add("confirmed-spam=" + spam);\n                reasons.add("alias-compromise-not-confirmed");\n                return new Result(Verdict.REVIEW_COMPROMISE, false, false, reasons);\n            }\n            reasons.add("no-confirmed-spam");\n            return new Result(Verdict.HEALTHY, false, false, reasons);\n        }''',
'''        if (!compromised) {\n            if (input.compromiseNeedsReview) {\n                if (spam > 0)\n                    reasons.add("confirmed-spam=" + spam);\n                reasons.add("alias-compromise-needs-review");\n                return new Result(Verdict.REVIEW_COMPROMISE, false, false, reasons);\n            }\n            if (spam > 0)\n                reasons.add("confirmed-spam-without-alias-compromise=" + spam);\n            else\n                reasons.add("no-confirmed-spam");\n            return new Result(Verdict.HEALTHY, false, false, reasons);\n        }''')
])

patch('app/src/main/java/eu/faircode/email/AliasBurnReadiness.java', [
    ('context review truth',
'''        AliasBurnPolicy.Input input = baseInput(alias);\n        if (context != null && input.replacementConfigured &&''',
'''        AliasBurnPolicy.Input input = baseInput(alias);\n        if (context != null)\n            input.compromiseNeedsReview = AliasCompromiseReviewStore.needsReview(context, alias);\n        if (context != null && input.replacementConfigured &&'''),
    ('conservative pure review',
'''        input.aliasCompromised = alias.state != null &&\n                (alias.state == EntityAlias.STATE_COMPROMISED ||\n                        alias.state == EntityAlias.STATE_REPLACED);\n        input.serviceDomainKnown = !TextUtils.isEmpty(alias.service_domain);''',
'''        input.aliasCompromised = alias.state != null &&\n                (alias.state == EntityAlias.STATE_COMPROMISED ||\n                        alias.state == EntityAlias.STATE_REPLACED);\n        input.compromiseNeedsReview = !input.aliasCompromised && input.spamHits > 0;\n        input.serviceDomainKnown = !TextUtils.isEmpty(alias.service_domain);''')
])

patch('app/src/main/java/eu/faircode/email/SpamIntelligence.java', [
    ('persist keep active resolution',
'''                        if (compromise == AliasCompromisePolicy.Decision.COMPROMISE)\n                            dao.markCompromised(account.uuid, after.address);\n                        else if (compromise == AliasCompromisePolicy.Decision.REVIEW)\n                            Log.i("SpamControl alias compromise needs review alias=" + after.address +\n                                    " message=" + message.id);''',
'''                        if (compromise == AliasCompromisePolicy.Decision.COMPROMISE)\n                            dao.markCompromised(account.uuid, after.address);\n                        else if (compromise == AliasCompromisePolicy.Decision.REVIEW)\n                            Log.i("SpamControl alias compromise needs review alias=" + after.address +\n                                    " message=" + message.id);\n                        else if (compromise == AliasCompromisePolicy.Decision.KEEP_ACTIVE) {\n                            EntityAlias currentAlias = dao.getAlias(account.uuid, after.address);\n                            if (currentAlias != null)\n                                AliasCompromiseReviewStore.markReviewedHealthy(context, currentAlias);\n                        }''')
])

patch('app/src/main/java/eu/faircode/email/SpamControlAttentionStats.java', [
    ('respect compromise policy toggle',
'''        int replacementUnverified = 0;\n\n        for (EntityAlias alias : aliases) {''',
'''        int replacementUnverified = 0;\n        boolean compromisePolicyEnabled = SpamControlPolicy.markAliasCompromised(context);\n\n        for (EntityAlias alias : aliases) {'''),
    ('conditional review count',
'''            if (AliasCompromiseReviewStore.needsReview(context, alias))\n                compromiseDecisions++;''',
'''            if (compromisePolicyEnabled &&\n                    AliasCompromiseReviewStore.needsReview(context, alias))\n                compromiseDecisions++;''')
])

patch('tools/spam-family-lab/AliasBurnPolicyLab.java', [
    ('explicit unresolved review test',
'''        AliasBurnPolicy.Input spamButNotCompromised = new AliasBurnPolicy.Input();\n        spamButNotCompromised.spamHits = 8;\n        AliasBurnPolicy.Result review = AliasBurnPolicy.evaluate(spamButNotCompromised);''',
'''        AliasBurnPolicy.Input spamButNotCompromised = new AliasBurnPolicy.Input();\n        spamButNotCompromised.spamHits = 8;\n        spamButNotCompromised.compromiseNeedsReview = true;\n        AliasBurnPolicy.Result review = AliasBurnPolicy.evaluate(spamButNotCompromised);'''),
    ('resolved healthy spam test',
'''        AliasBurnPolicy.Input leakedService = new AliasBurnPolicy.Input();''',
'''        AliasBurnPolicy.Input resolvedHealthySpam = new AliasBurnPolicy.Input();\n        resolvedHealthySpam.spamHits = 8;\n        resolvedHealthySpam.compromiseNeedsReview = false;\n        AliasBurnPolicy.Result resolved = AliasBurnPolicy.evaluate(resolvedHealthySpam);\n        require(resolved.verdict == AliasBurnPolicy.Verdict.HEALTHY,\n                "spam truth with resolved healthy alias must not reopen compromise review");\n        require(!resolved.compromised,\n                "resolved healthy spam must keep alias truth separate from spam truth");\n\n        AliasBurnPolicy.Input leakedService = new AliasBurnPolicy.Input();'''),
    ('print resolved verdict',
'''                a.verdict + "," + review.verdict + "," + b.verdict + "," +''',
'''                a.verdict + "," + review.verdict + "," + resolved.verdict + "," + b.verdict + "," +''')
])

print('compromise truth patch complete')

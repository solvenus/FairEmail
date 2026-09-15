from pathlib import Path

P = Path('app/src/main/java/eu/faircode/email/SpamIntelligence.java')
text = P.read_text(encoding='utf-8')

old = '''    private static AliasCompromisePolicy.Decision aliasCompromiseDecision(Context context,\n                                                                           EntityAccount account,\n                                                                           EntityMessage message) {\n        try {\n            AliasTrafficAnalyzer.Result traffic = AliasTrafficAnalyzer.assess(context, account, message);\n            EntityAlias alias = traffic.alias == null ? null :\n                    SpamIntelligenceDB.getInstance(context).alias()\n                            .getAlias(account.uuid, traffic.alias);\n            return AliasCompromisePolicy.decide(\n                    context,\n                    traffic.assessment == null ? null : traffic.assessment.verdict,\n                    alias != null && traffic.senderDomain != null &&\n                            AliasDomainAffinity.isTrustedSender(alias, traffic.senderDomain));\n        } catch (Throwable ex) {\n            Log.e(ex);\n            return AliasCompromisePolicy.Decision.REVIEW;\n        }\n    }\n\n'''

new = '''    private static AliasCompromisePolicy.Decision aliasCompromiseDecision(\n            Context context, EntityAccount account, EntityMessage message) {\n        AliasTrafficAnalyzer.Result traffic = AliasTrafficAnalyzer.assess(\n                context, account, message);\n        AliasCompromisePolicy.Input input = new AliasCompromisePolicy.Input();\n        input.explicitSpam = true;\n        input.trafficSuspicious = traffic.assessment.verdict ==\n                AliasTrafficScorer.Verdict.SUSPICIOUS;\n        input.senderKnown = traffic.senderDomain != null;\n        input.expectedContextKnown = traffic.serviceDomain != null ||\n                (traffic.trustedDomains != null && !traffic.trustedDomains.isEmpty());\n        input.expectedSenderMatch = traffic.senderDomain != null &&\n                ((traffic.serviceDomain != null &&\n                        traffic.senderDomain.equalsIgnoreCase(traffic.serviceDomain)) ||\n                        containsIgnoreCase(traffic.trustedDomains, traffic.senderDomain));\n        input.aliasHam = traffic.aliasHam;\n        return AliasCompromisePolicy.decide(input);\n    }\n\n    private static boolean containsIgnoreCase(java.util.List<String> values, String needle) {\n        if (values == null || needle == null)\n            return false;\n        for (String value : values)\n            if (needle.equalsIgnoreCase(value))\n                return true;\n        return false;\n    }\n\n'''

count = text.count(old)
if count != 1:
    raise SystemExit(f'compromise adapter: expected 1 broken block, got {count}')
text = text.replace(old, new, 1)

# Mechanical regression guard for the adapter contract inherited from the
# known-good phone checkpoint. Canonical message-state may not weaken these
# alias-side effects when Envelope-To/alias context exists.
required = [
    'input.trafficSuspicious',
    'traffic.senderDomain',
    'traffic.serviceDomain',
    'traffic.trustedDomains',
    'input.aliasHam = traffic.aliasHam',
    'AliasCompromisePolicy.decide(input)',
]
for token in required:
    if token not in text:
        raise SystemExit(f'missing compromise adapter invariant: {token}')
if 'AliasDomainAffinity.isTrustedSender' in text:
    raise SystemExit('forbidden drift: invented AliasDomainAffinity.isTrustedSender adapter remains')

P.write_text(text, encoding='utf-8')
print('restored SpamIntelligence compromise adapter contract')

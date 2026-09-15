from pathlib import Path

# Trigger revision 1: workflow exists before this script update.
ACT = Path('app/src/main/java/eu/faircode/email/ActivitySpamControl.java')
text = ACT.read_text()


def replace_once(old, new, label):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one match, found {count}')
    text = text.replace(old, new, 1)

old = '''        TextView state = bodyText(reviewState(candidate));\n        state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);\n        content.addView(state, matchWrap());\n'''
new = '''        TextView state = bodyText(reviewState(candidate));\n        state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);\n        content.addView(state, matchWrap());\n\n        if (candidate.overallReasons != null &&\n                candidate.overallReasons.contains("conflicting_evidence")) {\n            TextView conflict = bodyText("⚠ KONFLIKT I EVIDENS\\n" +\n                    "Spamstøtte " + percent(candidate.overallSpamSupport) +\n                    " · legitimitetsstøtte " + percent(candidate.overallHamSupport) +\n                    ". Ingen automatisk konklusjon — din vurdering avgjør.");\n            conflict.setTypeface(Typeface.DEFAULT, Typeface.BOLD);\n            content.addView(conflict, matchWrapWithMargin(0, 7, 0, 0));\n        }\n\n        int identityTwins = reviewIdentityCount(candidate);\n        if (identityTwins > 1) {\n            TextView impact = bodyText("↳ Minst " + identityTwins +\n                    " meldinger i denne køsiden har samme eksakte spamidentitet. " +\n                    "Én Spam-avgjørelse lærer identiteten og kan rydde flere historiske treff.");\n            impact.setTypeface(Typeface.DEFAULT, Typeface.BOLD);\n            content.addView(impact, matchWrapWithMargin(0, 7, 0, 0));\n        }\n'''
replace_once(old, new, 'review-conflict-impact')

marker = '''    private void runReviewAction(SpamFamilyLabRepository.Candidate candidate, boolean spam) {\n'''
helper = '''    private int reviewIdentityCount(SpamFamilyLabRepository.Candidate candidate) {\n        if (candidate == null)\n            return 0;\n        SenderParts sender = senderParts(candidate.sender);\n        SpamFamilyIdentity.Identity identity = SpamFamilyIdentity.fromRaw(\n                sender == null ? null : sender.name, candidate.subject);\n        if (identity == null)\n            return 1;\n\n        int count = 0;\n        for (SpamFamilyLabRepository.Candidate other : reviewQueue) {\n            if (other == null)\n                continue;\n            SenderParts otherSender = senderParts(other.sender);\n            SpamFamilyIdentity.Identity otherIdentity = SpamFamilyIdentity.fromRaw(\n                    otherSender == null ? null : otherSender.name, other.subject);\n            if (otherIdentity != null && identity.key.equals(otherIdentity.key))\n                count++;\n        }\n        return Math.max(1, count);\n    }\n\n'''
if text.count(marker) != 1:
    raise SystemExit(f'review-identity-helper-marker: expected exactly one match, found {text.count(marker)}')
text = text.replace(marker, helper + marker, 1)

ACT.write_text(text)
print('review conflict + exact identity impact patch applied')

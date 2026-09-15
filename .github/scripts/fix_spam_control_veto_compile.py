from pathlib import Path

path = Path('app/src/main/java/eu/faircode/email/ActivitySpamControl.java')
text = path.read_text(encoding='utf-8')
old = '            else if (!AliasReplacementVerifier.isVerified(this, alias))\n'
new = '            else if (readiness.verdict == AliasBurnPolicy.Verdict.VERIFY_REPLACEMENT)\n'
count = text.count(old)
if count != 1:
    raise SystemExit(f'expected exactly one veto verification call, got {count}')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
print('fixed veto compile check')

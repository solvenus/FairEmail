from pathlib import Path

path = Path('app/src/main/java/eu/faircode/email/SpamIntelligence.java')
text = path.read_text(encoding='utf-8')
old = 'compromise == AliasCompromisePolicy.Decision.KEEP_ACTIVE'
new = 'compromise == AliasCompromisePolicy.Decision.KEEP'
count = text.count(old)
if count != 1:
    raise SystemExit(f'expected exactly one KEEP_ACTIVE comparison, got {count}')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
print('fixed AliasCompromisePolicy KEEP enum comparison')

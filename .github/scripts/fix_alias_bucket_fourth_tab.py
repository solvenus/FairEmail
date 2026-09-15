from pathlib import Path

P = Path('app/src/main/java/eu/faircode/email/ActivitySpamControl.java')
text = P.read_text()

old = '    private int aliasBucketSelection = 0; // 0=spam/problem, 1=legitimate, 2=unresolved\n'
new = '    private int aliasBucketSelection = 0; // 0=spam/problem, 1=legitimate, 2=unresolved, 3=inactive\n'
if text.count(old) != 1:
    raise SystemExit(f'aliasBucketSelection comment: expected 1, got {text.count(old)}')
text = text.replace(old, new, 1)

old = '        aliasBucketSelection = Math.max(0, Math.min(2, bucket));\n'
new = '        aliasBucketSelection = Math.max(0, Math.min(3, bucket));\n'
if text.count(old) != 1:
    raise SystemExit(f'openAliasBucket clamp: expected 1, got {text.count(old)}')
text = text.replace(old, new, 1)

P.write_text(text)
print('fourth alias bucket navigation enabled')

from pathlib import Path

path = Path('app/src/main/java/eu/faircode/email/ActivitySpamControl.java')
text = path.read_text(encoding='utf-8')

old = '''    private void updateNav() {'''
new = '''    private void openAliasBucket(int bucket) {\n        aliasBucketSelection = Math.max(0, Math.min(2, bucket));\n        setSection(Section.ALIASES);\n    }\n\n    private void updateNav() {'''
if text.count(old) != 1:
    raise SystemExit('updateNav insertion signature mismatch')
text = text.replace(old, new, 1)

needle = 'v -> setSection(Section.ALIASES)'
count = text.count(needle)
if count != 6:
    raise SystemExit(f'expected 6 overview alias navigation callbacks, got {count}')
text = text.replace(needle, 'v -> openAliasBucket(0)')

old_sub = '                replacementsMissing + " mangler replacement", "Se aliaser",\n'
new_sub = '                replacementsMissing + " uten replacement · anbefalt", "Se aliaser",\n'
if text.count(old_sub) != 1:
    raise SystemExit('compromised stat subtitle signature mismatch')
text = text.replace(old_sub, new_sub, 1)

path.write_text(text, encoding='utf-8')
print('direct alias task navigation patch complete')

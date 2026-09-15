from pathlib import Path

path = Path('.github/scripts/patch_spam_control_alias_undo.py')
text = path.read_text(encoding='utf-8')
old = '''        count = text.count(old)\n        if count != 1:\n            raise SystemExit(f'{path_s} {label}: expected exactly 1 match, got {count}')\n        text = text.replace(old, new, 1)\n'''
new = '''        count = text.count(old)\n        expected = 2 if label == 'restore account compromise rows' else 1\n        if count != expected:\n            raise SystemExit(f'{path_s} {label}: expected exactly {expected} match(es), got {count}')\n        text = text.replace(old, new, 1)\n'''
count = text.count(old)
if count != 1:
    raise SystemExit(f'patch helper signature expected once, got {count}')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
print('hardened alias undo patcher')

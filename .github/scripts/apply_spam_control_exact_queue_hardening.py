#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


dao = Path("app/src/main/java/eu/faircode/email/DaoAlias.java")
old_clause = '''            " AND (traffic_verdict = 'SUSPICIOUS' OR predicted_family_id IS NOT NULL)" +'''
new_clause = '''            " AND (traffic_verdict = 'SUSPICIOUS'" +
            "   OR (predicted_family_id IS NOT NULL AND family_score >= 0.999999))" +'''
text = dao.read_text(encoding="utf-8")
count = text.count(old_clause)
if count != 2:
    raise SystemExit(f"exact review queue clauses: expected 2 matches, found {count}")
dao.write_text(text.replace(old_clause, new_clause), encoding="utf-8")

activity = Path("app/src/main/java/eu/faircode/email/ActivitySpamControl.java")
replace_once(
    activity,
    '''        selectedAccount = account;
        if (changed) {
            reviewQueue = new ArrayList<>();
            reviewIndex = 0;
            reviewCount = 0;
            familyDescriptors.clear();
        }
''',
    '''        selectedAccount = account;
        if (changed) {
            reviewQueue = new ArrayList<>();
            reviewIndex = 0;
            reviewCount = 0;
            familyDescriptors.clear();
            // Re-evaluate every retained delivery through the exact identity path.
            // This flushes legacy fuzzy predictions without deleting human labels.
            SpamFamilyRescorer.enqueueAllActive(getApplicationContext(), account.uuid);
            SpamFamilyRescorer.start(getApplicationContext());
        }
''',
    "initial exact rescan",
)

replace_once(
    activity,
    '''                    if (ok && (fresh.state == EntityAlias.STATE_ACTIVE ||
                            fresh.state == EntityAlias.STATE_COMPROMISED)) {
                        EntityMessage latest = null;
                        // Sender regex will also be synchronized naturally on next delivery/reply.
                    }
''',
    '''                    // Sender regex will also be synchronized naturally on next delivery/reply.
''',
    "remove dead alias editor local",
)

print("PASS: Spam Control queue accepts only exact family matches or independent suspicious alias traffic")

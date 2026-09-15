#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


compose = Path("app/src/main/java/eu/faircode/email/FragmentCompose.java")

replace_once(
    compose,
    '''                            // Envelope-To is the authoritative reply alias. Resolve it against
                            // the identity actually selected for this reply, not ref.identity: older
                            // messages and messages ingested before alias synchronization can have a
                            // null/stale reference identity even though the account identity is known.
                            String envelopeExtra = SpamIntelligence.resolveReplyExtra(
                                    context, selected, ref.deliveredto);''',
    '''                            // Envelope-To is the authoritative reply alias. Re-observe the exact
                            // reference message before resolving it so old messages and messages whose
                            // alias registry synchronization lagged ingestion get repaired in the reply
                            // path itself. observeMessage is idempotent for an already observed delivery.
                            if (!TextUtils.isEmpty(ref.deliveredto) && ref.folder != null) {
                                EntityAccount refAccount = db.account().getAccount(ref.account);
                                EntityFolder refFolder = db.folder().getFolder(ref.folder);
                                if (refAccount != null && refFolder != null)
                                    SpamIntelligence.observeMessage(context, refAccount, refFolder, ref);
                            }

                            // Resolve against the identity actually selected for this reply, not
                            // ref.identity: the latter may be null/stale on older messages.
                            String envelopeExtra = SpamIntelligence.resolveReplyExtra(
                                    context, selected, ref.deliveredto);''',
    "repair alias registry synchronously in reply path",
)

print("PASS: reply path now repairs Envelope-To alias registration before resolving sender extra")

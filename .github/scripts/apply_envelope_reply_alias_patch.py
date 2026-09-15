#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/eu/faircode/email/FragmentCompose.java")
text = path.read_text(encoding="utf-8")
marker = 'Reply alias from Envelope-To='
if marker in text:
    print("Envelope-To reply alias patch already present")
    raise SystemExit(0)

old = '''                                Address preferred = null;
                                if (recognized != null) {
'''
new = '''                                Address preferred = null;
                                // Envelope-To is FairEmail's stored authoritative delivery alias
                                // for this account. Give it priority over visible To/Cc/Bcc when
                                // choosing the sender alias for a reply.
                                if (recognized != null && !TextUtils.isEmpty(ref.deliveredto)) {
                                    String envelopeExtra = SpamIntelligence.resolveReplyExtra(
                                            context, recognized, ref.deliveredto);
                                    if (!TextUtils.isEmpty(envelopeExtra))
                                        try {
                                            preferred = new InternetAddress(ref.deliveredto);
                                            EntityLog.log(context, "Reply alias from Envelope-To=" +
                                                    ref.deliveredto + " extra=" + envelopeExtra);
                                        } catch (AddressException ex) {
                                            Log.w(ex);
                                        }
                                }

                                if (preferred == null && recognized != null) {
'''
count = text.count(old)
if count != 1:
    raise SystemExit(f"Expected exactly one reply identity anchor, found {count}")
text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
print("Patched FragmentCompose: Envelope-To now has reply-alias priority")

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
    '''                            if (ref.identity != null) {
                                EntityIdentity recognized = db.identity().getIdentity(ref.identity);
                                EntityLog.log(context, "Recognized=" + (recognized == null ? null : recognized.email));

                                Address preferred = null;
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

                                if (preferred == null && recognized != null) {''',
    '''                            // Envelope-To is the authoritative reply alias. Resolve it against
                            // the identity actually selected for this reply, not ref.identity: older
                            // messages and messages ingested before alias synchronization can have a
                            // null/stale reference identity even though the account identity is known.
                            String envelopeExtra = SpamIntelligence.resolveReplyExtra(
                                    context, selected, ref.deliveredto);
                            if (!TextUtils.isEmpty(envelopeExtra)) {
                                data.draft.extra = envelopeExtra;
                                EntityLog.log(context, "Reply alias from Envelope-To=" +
                                        ref.deliveredto + " extra=" + envelopeExtra +
                                        " identity=" + selected.email);
                            } else if (ref.identity != null) {
                                EntityIdentity recognized = db.identity().getIdentity(ref.identity);
                                EntityLog.log(context, "Recognized=" + (recognized == null ? null : recognized.email));

                                Address preferred = null;
                                if (preferred == null && recognized != null) {''',
    "make Envelope-To alias independent of reference identity",
)

replace_once(
    compose,
    '''            etExtra.setText(data.draft.extra);
            etTo.setText(MessageHelper.formatAddressesCompose(data.draft.to));''',
    '''            etExtra.setText(data.draft.extra);
            // A resolved reply alias must stay visible even if sender-extra UI state
            // was not synchronized before the compose screen was created.
            if (!TextUtils.isEmpty(data.draft.extra))
                grpExtra.setVisibility(View.VISIBLE);
            etTo.setText(MessageHelper.formatAddressesCompose(data.draft.to));''',
    "keep resolved Envelope-To alias visible in compose",
)

print("PASS: reply alias now comes directly from Envelope-To + selected identity")

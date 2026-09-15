#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


compose = Path("app/src/main/java/eu/faircode/email/FragmentCompose.java")
text = compose.read_text(encoding="utf-8")

marker = "Reply-time alias recovery v4"
if marker in text:
    print("PASS: reply-time alias recovery v4 already applied")
    raise SystemExit(0)

replace_once(
    compose,
    "import javax.mail.internet.InternetAddress;\n",
    "import javax.mail.internet.InternetAddress;\nimport javax.mail.internet.InternetHeaders;\n",
    "add InternetHeaders import",
)

old = '''                            // Envelope-To is the authoritative reply alias. Re-observe the exact
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
                                    context, selected, ref.deliveredto);
                            if (!TextUtils.isEmpty(envelopeExtra)) {
                                data.draft.extra = envelopeExtra;
                                EntityLog.log(context, "Reply alias from Envelope-To=" +
                                        ref.deliveredto + " extra=" + envelopeExtra +
                                        " identity=" + selected.email);
'''

new = '''                            // Reply-time alias recovery v4
                            //
                            // Older locally stored messages can predate alias/identity synchronization.
                            // Reconstruct the delivery alias on demand when Reply is pressed instead of
                            // requiring a mailbox-wide rewrite. Prefer FairEmail's stored Envelope-To,
                            // then the original envelope headers, then visible recipients on this
                            // identity's domain. Once resolved, repair this one historical message.
                            String identityDomain = UriHelper.getEmailDomain(selected.email);
                            String replyDeliveredTo = AliasRegistry.normalizeAddress(ref.deliveredto);
                            if (!TextUtils.isEmpty(replyDeliveredTo) &&
                                    !TextUtils.isEmpty(identityDomain) &&
                                    !identityDomain.equalsIgnoreCase(UriHelper.getEmailDomain(replyDeliveredTo)))
                                replyDeliveredTo = null;

                            if (TextUtils.isEmpty(replyDeliveredTo) && !TextUtils.isEmpty(ref.headers))
                                try {
                                    InternetHeaders storedHeaders = new InternetHeaders(
                                            new ByteArrayInputStream(ref.headers.getBytes(StandardCharsets.UTF_8)), true);
                                    String[] envelopeHeaders = new String[]{
                                            "Envelope-To", "X-Envelope-To", "X-Original-To", "Delivered-To"};
                                    for (String headerName : envelopeHeaders) {
                                        String value = storedHeaders.getHeader(headerName, null);
                                        String candidate = AliasRegistry.normalizeAddress(value);
                                        if (!TextUtils.isEmpty(candidate) &&
                                                !TextUtils.isEmpty(identityDomain) &&
                                                identityDomain.equalsIgnoreCase(UriHelper.getEmailDomain(candidate))) {
                                            replyDeliveredTo = candidate;
                                            EntityLog.log(context, "Recovered historical reply alias from " +
                                                    headerName + "=" + candidate);
                                            break;
                                        }
                                    }
                                } catch (Throwable ex) {
                                    Log.w(ex);
                                }

                            if (TextUtils.isEmpty(replyDeliveredTo) && !TextUtils.isEmpty(identityDomain)) {
                                Address[][] recipientGroups = new Address[][]{ref.to, ref.bcc, ref.cc};
                                String identityAddress = AliasRegistry.normalizeAddress(selected.email);
                                String sameAddressCandidate = null;

                                recipientSearch:
                                for (Address[] recipients : recipientGroups) {
                                    if (recipients == null)
                                        continue;
                                    for (Address recipient : recipients) {
                                        if (!(recipient instanceof InternetAddress))
                                            continue;
                                        String candidate = AliasRegistry.normalizeAddress(
                                                ((InternetAddress) recipient).getAddress());
                                        if (TextUtils.isEmpty(candidate) ||
                                                !identityDomain.equalsIgnoreCase(UriHelper.getEmailDomain(candidate)))
                                            continue;

                                        if (identityAddress == null || !candidate.equalsIgnoreCase(identityAddress)) {
                                            replyDeliveredTo = candidate;
                                            break recipientSearch;
                                        }
                                        if (sameAddressCandidate == null)
                                            sameAddressCandidate = candidate;
                                    }
                                }

                                if (TextUtils.isEmpty(replyDeliveredTo))
                                    replyDeliveredTo = sameAddressCandidate;

                                if (!TextUtils.isEmpty(replyDeliveredTo))
                                    EntityLog.log(context, "Recovered historical reply alias from recipients=" +
                                            replyDeliveredTo);
                            }

                            if (!TextUtils.isEmpty(replyDeliveredTo)) {
                                boolean recovered = TextUtils.isEmpty(ref.deliveredto);
                                ref.deliveredto = replyDeliveredTo;

                                if (recovered && ref.id != null)
                                    db.getOpenHelper().getWritableDatabase().execSQL(
                                            "UPDATE message SET deliveredto = ?" +
                                                    " WHERE id = ? AND (deliveredto IS NULL OR TRIM(deliveredto) = '')",
                                            new Object[]{replyDeliveredTo, ref.id});

                                if (ref.folder != null) {
                                    EntityAccount refAccount = db.account().getAccount(ref.account);
                                    EntityFolder refFolder = db.folder().getFolder(ref.folder);
                                    if (refAccount != null && refFolder != null)
                                        SpamIntelligence.observeMessage(context, refAccount, refFolder, ref);
                                }
                            }

                            // Resolve against the identity actually selected for this reply. Successful
                            // resolution also repairs the identity on exactly this historical message.
                            String envelopeExtra = SpamIntelligence.resolveReplyExtra(
                                    context, selected, replyDeliveredTo);
                            if (!TextUtils.isEmpty(envelopeExtra)) {
                                data.draft.extra = envelopeExtra;
                                if (ref.id != null && selected.id != null &&
                                        !Objects.equals(ref.identity, selected.id)) {
                                    db.message().setMessageIdentity(ref.id, selected.id);
                                    ref.identity = selected.id;
                                }
                                EntityLog.log(context, "Reply alias resolved on demand=" +
                                        replyDeliveredTo + " extra=" + envelopeExtra +
                                        " identity=" + selected.email);
'''

replace_once(compose, old, new, "replace v3 reply alias block")

print("PASS: historical reply aliases are recovered and repaired on demand")

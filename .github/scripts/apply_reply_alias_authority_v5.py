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

marker = "Reply-time alias authority v5"
if marker in text:
    print("PASS: reply-time alias authority v5 already applied")
    raise SystemExit(0)

old = '''                            // Reply-time alias recovery v4
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
'''

new = '''                            // Reply-time alias authority v5
                            //
                            // Old messages can contain both the service alias and a later routing alias.
                            // The SMTP envelope recipient closest to the original delivery is authoritative:
                            // Envelope-To > X-Envelope-To > X-Original-To > visible recipients > Delivered-To
                            // > the legacy stored deliveredto value. This matters for forwarded/routed mail,
                            // where Delivered-To can name an outer mailbox such as sd_1 while Envelope-To and
                            // To still name the real service alias such as sd_notion.
                            String identityDomain = UriHelper.getEmailDomain(selected.email);
                            String originalDeliveredTo = AliasRegistry.normalizeAddress(ref.deliveredto);
                            String replyDeliveredTo = null;
                            String replyAliasSource = null;
                            InternetHeaders storedHeaders = null;

                            if (!TextUtils.isEmpty(ref.headers))
                                try {
                                    storedHeaders = new InternetHeaders(
                                            new ByteArrayInputStream(ref.headers.getBytes(StandardCharsets.UTF_8)), true);
                                    String[] authoritativeHeaders = new String[]{
                                            "Envelope-To", "X-Envelope-To", "X-Original-To"};
                                    for (String headerName : authoritativeHeaders) {
                                        String value = storedHeaders.getHeader(headerName, null);
                                        String candidate = AliasRegistry.normalizeAddress(value);
                                        if (!TextUtils.isEmpty(candidate) &&
                                                !TextUtils.isEmpty(identityDomain) &&
                                                identityDomain.equalsIgnoreCase(UriHelper.getEmailDomain(candidate))) {
                                            replyDeliveredTo = candidate;
                                            replyAliasSource = headerName;
                                            break;
                                        }
                                    }
                                } catch (Throwable ex) {
                                    Log.w(ex);
                                }

                            // Original visible recipients are stronger evidence than Delivered-To because
                            // Delivered-To can be rewritten by a later local forward/transport hop.
                            if (TextUtils.isEmpty(replyDeliveredTo) && !TextUtils.isEmpty(identityDomain)) {
                                Address[][] recipientGroups = new Address[][]{ref.to, ref.bcc, ref.cc};
                                String[] recipientNames = new String[]{"To", "Bcc", "Cc"};
                                String identityAddress = AliasRegistry.normalizeAddress(selected.email);
                                String sameAddressCandidate = null;
                                String sameAddressSource = null;

                                recipientSearch:
                                for (int group = 0; group < recipientGroups.length; group++) {
                                    Address[] recipients = recipientGroups[group];
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
                                            replyAliasSource = recipientNames[group];
                                            break recipientSearch;
                                        }
                                        if (sameAddressCandidate == null) {
                                            sameAddressCandidate = candidate;
                                            sameAddressSource = recipientNames[group];
                                        }
                                    }
                                }

                                if (TextUtils.isEmpty(replyDeliveredTo)) {
                                    replyDeliveredTo = sameAddressCandidate;
                                    replyAliasSource = sameAddressSource;
                                }
                            }

                            // Delivered-To is intentionally late. It often identifies the final local
                            // transport target rather than the alias the sender addressed.
                            if (TextUtils.isEmpty(replyDeliveredTo) && storedHeaders != null)
                                try {
                                    String candidate = AliasRegistry.normalizeAddress(
                                            storedHeaders.getHeader("Delivered-To", null));
                                    if (!TextUtils.isEmpty(candidate) &&
                                            !TextUtils.isEmpty(identityDomain) &&
                                            identityDomain.equalsIgnoreCase(UriHelper.getEmailDomain(candidate))) {
                                        replyDeliveredTo = candidate;
                                        replyAliasSource = "Delivered-To";
                                    }
                                } catch (Throwable ex) {
                                    Log.w(ex);
                                }

                            if (TextUtils.isEmpty(replyDeliveredTo) &&
                                    !TextUtils.isEmpty(originalDeliveredTo) &&
                                    !TextUtils.isEmpty(identityDomain) &&
                                    identityDomain.equalsIgnoreCase(UriHelper.getEmailDomain(originalDeliveredTo))) {
                                replyDeliveredTo = originalDeliveredTo;
                                replyAliasSource = "stored-deliveredto";
                            }

                            if (!TextUtils.isEmpty(replyDeliveredTo)) {
                                boolean repaired = TextUtils.isEmpty(originalDeliveredTo) ||
                                        !replyDeliveredTo.equalsIgnoreCase(originalDeliveredTo);
                                ref.deliveredto = replyDeliveredTo;

                                if (repaired && ref.id != null)
                                    db.getOpenHelper().getWritableDatabase().execSQL(
                                            "UPDATE message SET deliveredto = ? WHERE id = ?",
                                            new Object[]{replyDeliveredTo, ref.id});

                                EntityLog.log(context, "Reply alias authority source=" + replyAliasSource +
                                        " alias=" + replyDeliveredTo +
                                        " previous=" + originalDeliveredTo +
                                        " repaired=" + repaired);

                                if (ref.folder != null) {
                                    EntityAccount refAccount = db.account().getAccount(ref.account);
                                    EntityFolder refFolder = db.folder().getFolder(ref.folder);
                                    if (refAccount != null && refFolder != null)
                                        SpamIntelligence.observeMessage(context, refAccount, refFolder, ref);
                                }
                            }
'''

replace_once(compose, old, new, "replace v4 reply alias authority block")

print("PASS: reply alias authority now prefers original envelope/recipient evidence over routing Delivered-To")

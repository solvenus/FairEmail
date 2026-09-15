package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import javax.mail.internet.InternetAddress;

/**
 * Bridges the Alias Registry to FairEmail's existing sender_extra mechanism.
 *
 * FairEmail already knows how to compose and send using an alternate local
 * part. This manager gives the existing mechanism an exact regex containing
 * only aliases observed in our registry. It never replaces a custom regex the
 * user has edited outside this manager.
 */
public final class AliasSenderManager {
    private static final String META_PREFIX = "alias_sender_regex:";

    private AliasSenderManager() {
    }

    static void synchronizeForMessage(Context context,
                                      EntityAccount account,
                                      EntityMessage message) {
        if (context == null || account == null || account.id == null || account.uuid == null ||
                message == null || message.deliveredto == null)
            return;

        try {
            String alias = AliasRegistry.normalizeAddress(message.deliveredto);
            if (alias == null)
                return;

            String aliasDomain = domain(alias);
            if (aliasDomain == null)
                return;

            DB db = DB.getInstance(context);
            List<EntityIdentity> identities = db.identity().getSynchronizingIdentities(account.id);
            EntityIdentity identity = selectIdentity(identities, aliasDomain);
            if (identity == null || identity.id == null)
                return;

            synchronizeIdentity(context, account, identity);

            // The first message to a newly discovered alias may have been ingested
            // before the managed regex existed. Repair its identity immediately so
            // replying to that same message already uses the alias.
            if (message.identity == null) {
                InternetAddress delivered = new InternetAddress(alias);
                if (identity.sameAddress(delivered) || identity.similarAddress(delivered)) {
                    message.identity = identity.id;
                    db.message().updateMessage(message);
                }
            }
        } catch (Throwable ex) {
            Log.e(ex);
        }
    }

    static boolean synchronizeIdentity(Context context,
                                       EntityAccount account,
                                       EntityIdentity identity) {
        if (context == null || account == null || account.uuid == null ||
                identity == null || identity.id == null || identity.uuid == null)
            return false;

        String identityDomain = domain(identity.email);
        if (identityDomain == null)
            return false;

        SpamIntelligenceDB intelligence = SpamIntelligenceDB.getInstance(context);
        DaoAlias aliases = intelligence.alias();
        List<EntityAlias> entries = aliases.getAliases(account.uuid);
        List<String> addresses = new ArrayList<>();
        for (EntityAlias entry : entries) {
            if (entry == null || entry.state != EntityAlias.STATE_ACTIVE)
                continue;
            String address = AliasRegistry.normalizeAddress(entry.address);
            if (address != null && identityDomain.equalsIgnoreCase(domain(address)))
                addresses.add(address);
        }

        if (addresses.isEmpty())
            return false;

        Collections.sort(addresses, String.CASE_INSENSITIVE_ORDER);
        String regex = exactAddressRegex(addresses);
        String metaKey = META_PREFIX + identity.uuid;
        String previousManaged = aliases.getMetaText(metaKey);
        String current = identity.sender_extra_regex;

        // Existing unowned configuration belongs to the user.
        if (previousManaged == null && !TextUtils.isEmpty(current))
            return false;

        // If a previously managed identity was edited or disabled manually,
        // relinquish control instead of fighting the user's choice.
        if (previousManaged != null &&
                (!identity.sender_extra || !Objects.equals(previousManaged, current)))
            return false;

        if (identity.sender_extra && Objects.equals(current, regex))
            return false;

        identity.sender_extra = true;
        identity.sender_extra_regex = regex;
        DB.getInstance(context).identity().updateIdentity(identity);
        Core.clearIdentities();

        EntitySpamMeta meta = new EntitySpamMeta();
        meta.key = metaKey;
        meta.text_value = regex;
        aliases.putMeta(meta);
        return true;
    }

    private static EntityIdentity selectIdentity(List<EntityIdentity> identities, String domain) {
        if (identities == null)
            return null;
        List<EntityIdentity> candidates = new ArrayList<>();
        for (EntityIdentity identity : identities)
            if (identity != null && domain.equalsIgnoreCase(domain(identity.email)))
                candidates.add(identity);
        if (candidates.isEmpty())
            return null;

        Collections.sort(candidates, new Comparator<EntityIdentity>() {
            @Override
            public int compare(EntityIdentity a, EntityIdentity b) {
                int primary = Boolean.compare(Boolean.TRUE.equals(b.primary), Boolean.TRUE.equals(a.primary));
                if (primary != 0)
                    return primary;
                return a.email.compareToIgnoreCase(b.email);
            }
        });
        return candidates.get(0);
    }

    private static String exactAddressRegex(List<String> addresses) {
        StringBuilder result = new StringBuilder("(?i)^(?:");
        for (int i = 0; i < addresses.size(); i++) {
            if (i > 0)
                result.append('|');
            result.append(Pattern.quote(addresses.get(i)));
        }
        return result.append(")$").toString();
    }

    private static String domain(String address) {
        if (address == null)
            return null;
        int at = address.lastIndexOf('@');
        if (at <= 0 || at + 1 >= address.length())
            return null;
        return address.substring(at + 1).toLowerCase(Locale.ROOT);
    }
}

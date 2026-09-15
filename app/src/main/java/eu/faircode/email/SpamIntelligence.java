package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;

import java.util.Locale;

/**
 * Narrow integration facade between FairEmail and the custom intelligence
 * subsystem. Mail synchronization should only need to call this facade.
 */
public final class SpamIntelligence {
    private SpamIntelligence() {
    }

    /**
     * Convenience ingress for existing FairEmail paths that only have a
     * message/folder pair. Account UUID is resolved from FairEmail's main DB.
     */
    public static void observeMessage(Context context,
                                      EntityFolder folder,
                                      EntityMessage message) {
        try {
            if (context == null)
                return;

            // Populate the registry from already-synced mail once per process.
            // This is background, restart-safe and idempotent.
            AliasBackfill.schedule(context);

            if (folder == null || message == null || message.account == null ||
                    message.deliveredto == null)
                return;
            EntityAccount account = DB.getInstance(context).account().getAccount(message.account);
            observeMessage(context, account, folder, message);
        } catch (Throwable ex) {
            Log.e(ex);
        }
    }

    /** Record envelope alias and sender-domain evidence once the message has a DB id. */
    public static void observeMessage(Context context,
                                      EntityAccount account,
                                      EntityFolder folder,
                                      EntityMessage message) {
        try {
            if (context == null || account == null || folder == null || message == null ||
                    message.id == null || account.uuid == null || message.deliveredto == null)
                return;

            AliasDomainAffinity.Evidence evidence = AliasDomainAffinity.fromMessage(context, message);
            SpamAliasStore.observeDelivery(
                    context,
                    account.uuid,
                    message.id,
                    message.deliveredto,
                    message.received,
                    folder.type,
                    evidence.senderDomain,
                    evidence.unsubscribe);

            // Evaluate and persist only. Automatic move/delete remains disabled
            // until replay has established precision on real user data.
            refreshAssessment(context, account, message, true);

            // Reuse FairEmail's existing sender_extra mechanism, but constrain it
            // to exact active aliases already observed in our registry.
            AliasSenderManager.synchronizeForMessage(context, account, message);
        } catch (Throwable ex) {
            // Intelligence must never be able to break mail synchronization.
            Log.e(ex);
        }
    }

    /**
     * True only for an active alias actually observed for this FairEmail account.
     * This lets automatic replies use an envelope alias without opening the
     * identity to arbitrary sender editing.
     */
    public static boolean isKnownAlias(Context context,
                                       EntityIdentity identity,
                                       String address) {
        try {
            if (context == null || identity == null || identity.account == null)
                return false;

            String alias = AliasRegistry.normalizeAddress(address);
            String identityAddress = AliasRegistry.normalizeAddress(identity.email);
            if (alias == null || identityAddress == null)
                return false;

            String aliasDomain = emailDomain(alias);
            String identityDomain = emailDomain(identityAddress);
            if (aliasDomain == null || !aliasDomain.equalsIgnoreCase(identityDomain))
                return false;

            if (alias.equalsIgnoreCase(identityAddress))
                return true;

            EntityAccount account = DB.getInstance(context).account().getAccount(identity.account);
            if (account == null || account.uuid == null)
                return false;

            EntityAlias known = SpamIntelligenceDB.getInstance(context)
                    .alias().getAlias(account.uuid, alias);
            return known != null && known.state == EntityAlias.STATE_ACTIVE;
        } catch (Throwable ex) {
            Log.e(ex);
            return false;
        }
    }

    /** Resolve the plain local-part FairEmail sender-extra value for an observed alias. */
    public static String resolveReplyExtra(Context context,
                                           EntityIdentity identity,
                                           String deliveredTo) {
        String alias = AliasRegistry.normalizeAddress(deliveredTo);
        String identityAddress = AliasRegistry.normalizeAddress(identity == null ? null : identity.email);
        if (alias == null || identityAddress == null || !isKnownAlias(context, identity, alias))
            return null;

        int aat = alias.lastIndexOf('@');
        int iat = identityAddress.lastIndexOf('@');
        if (aat <= 0 || iat <= 0)
            return null;
        String local = alias.substring(0, aat);
        String identityLocal = identityAddress.substring(0, iat);
        return local.equalsIgnoreCase(identityLocal) ? null : local;
    }

    /**
     * Permit sender-extra semantics either through FairEmail's own setting or
     * through an exact active alias present in the local Alias Registry.
     */
    public static boolean permitsExtra(Context context,
                                       EntityIdentity identity,
                                       String extra) {
        if (identity == null || extra == null)
            return false;
        if (identity.sender_extra)
            return true;

        String value = extra.trim();
        // Our registry-backed path intentionally supports only a plain local-part.
        // FairEmail's +extra/@extra/name forms remain governed by sender_extra.
        if (value.isEmpty() || value.startsWith("+") || value.startsWith("@") || value.contains(","))
            return false;

        String domain = emailDomain(identity.email);
        if (domain == null)
            return false;
        return isKnownAlias(context, identity, value + "@" + domain);
    }

    public static void learnSpam(Context context,
                                 EntityAccount account,
                                 EntityMessage message,
                                 Long familyId) {
        setLabel(context, account, message, EntityAliasDelivery.LABEL_SPAM, familyId);
    }

    public static void learnHam(Context context,
                                EntityAccount account,
                                EntityMessage message) {
        setLabel(context, account, message, EntityAliasDelivery.LABEL_HAM, null);
    }

    public static void clearLabel(Context context,
                                  EntityAccount account,
                                  EntityMessage message) {
        setLabel(context, account, message, EntityAliasDelivery.LABEL_UNKNOWN, null);
    }

    private static void setLabel(Context context,
                                 EntityAccount account,
                                 EntityMessage message,
                                 int label,
                                 Long familyId) {
        try {
            if (context == null || account == null || message == null ||
                    message.id == null || account.uuid == null)
                return;

            // Label actions can arrive before a message has passed the normal
            // intelligence ingress. Ensure a ledger row exists first.
            EntityAliasDelivery delivery = SpamIntelligenceDB.getInstance(context)
                    .alias().getDelivery(account.uuid, message.id);
            if (delivery == null && message.folder != null && message.deliveredto != null) {
                EntityFolder folder = DB.getInstance(context).folder().getFolder(message.folder);
                if (folder != null)
                    observeMessage(context, account, folder, message);
            }

            boolean changed = SpamAliasStore.setLabel(
                    context,
                    account.uuid,
                    message.id,
                    label,
                    familyId);
            if (changed)
                refreshAssessment(context, account, message, false);
        } catch (Throwable ex) {
            Log.e(ex);
        }
    }

    private static void refreshAssessment(Context context,
                                          EntityAccount account,
                                          EntityMessage message,
                                          boolean log) {
        AliasTrafficAnalyzer.Result traffic = AliasTrafficAnalyzer.assess(context, account, message);
        if (traffic.alias == null || message.id == null || account.uuid == null)
            return;

        SpamAliasStore.setAssessment(context, account.uuid, message.id, traffic.assessment);
        if (log)
            Log.i("AliasTraffic" +
                    " alias=" + traffic.alias +
                    " sender=" + traffic.senderDomain +
                    " service=" + traffic.serviceDomain +
                    " aliasLabels=" + traffic.aliasSpam + "/" + traffic.aliasHam +
                    " senderLabels=" + traffic.senderSpam + "/" + traffic.senderHam +
                    " " + traffic.assessment);
    }

    private static String emailDomain(String address) {
        if (address == null)
            return null;
        int at = address.lastIndexOf('@');
        if (at <= 0 || at + 1 >= address.length())
            return null;
        return address.substring(at + 1).toLowerCase(Locale.ROOT);
    }
}

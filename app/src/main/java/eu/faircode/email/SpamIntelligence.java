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

/** Narrow integration facade between FairEmail and the custom intelligence subsystem. */
public final class SpamIntelligence {
    private SpamIntelligence() {
    }

    public static void observeMessage(Context context,
                                      EntityFolder folder,
                                      EntityMessage message) {
        try {
            if (context == null)
                return;

            SpamIntentObserver.start(context);
            SpamFamilyRescorer.start(context);
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

    /** Record envelope alias and all observer-only intelligence once the message has a DB id. */
    public static void observeMessage(Context context,
                                      EntityAccount account,
                                      EntityFolder folder,
                                      EntityMessage message) {
        try {
            if (context == null || account == null || folder == null || message == null ||
                    message.id == null || account.uuid == null || message.deliveredto == null)
                return;

            SpamIntentObserver.start(context);
            SpamFamilyRescorer.start(context);

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

            refreshAssessment(context, account, message, true);
            refreshFamilyMatch(context, account, message, true);
            maybeAutoLabelExact(context, account, message);

            AliasSenderManager.synchronizeForMessage(context, account, message);
        } catch (Throwable ex) {
            Log.e(ex);
        }
    }

    /**
     * True only for a reply-capable alias actually observed for this FairEmail
     * account. COMPROMISED remains reply-capable until the service is rotated.
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
            return known != null && AliasSenderManager.isReplyCapable(known.state);
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
     * through an exact reply-capable alias present in the local Alias Registry.
     */
    public static boolean permitsExtra(Context context,
                                       EntityIdentity identity,
                                       String extra) {
        if (identity == null || extra == null)
            return false;
        if (identity.sender_extra)
            return true;

        String value = extra.trim();
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

    /**
     * Explicitly state that one message does not belong to one family without
     * claiming the message is legitimate.
     */
    public static boolean excludeFromFamily(Context context,
                                            EntityAccount account,
                                            EntityMessage message,
                                            long familyId,
                                            String reason) {
        try {
            if (context == null || account == null || account.uuid == null ||
                    message == null || message.id == null || familyId <= 0)
                return false;

            SpamIntelligenceDB intelligence = SpamIntelligenceDB.getInstance(context);
            DaoSpamFamily familyDao = intelligence.family();
            EntitySpamFamily family = familyDao.getFamily(familyId);
            if (family == null || family.id == null ||
                    !account.uuid.equals(family.account_uuid))
                return false;

            EntityAliasDelivery delivery = intelligence.alias()
                    .getDelivery(account.uuid, message.id);
            if (delivery == null)
                return false;
            if (delivery.label == EntityAliasDelivery.LABEL_SPAM &&
                    delivery.family_id != null && delivery.family_id == familyId)
                return false;

            EntitySpamFamilyExclusion exclusion = new EntitySpamFamilyExclusion();
            exclusion.account_uuid = account.uuid;
            exclusion.message_id = message.id;
            exclusion.family_id = familyId;
            exclusion.created_at = System.currentTimeMillis();
            exclusion.reason = reason == null ? null : reason.trim();
            familyDao.insertExclusion(exclusion);

            if (delivery.predicted_family_id != null &&
                    delivery.predicted_family_id == familyId)
                familyDao.clearFamilyMatch(account.uuid, message.id,
                        System.currentTimeMillis());

            refreshFamilyMatch(context, account, message, false);
            return true;
        } catch (Throwable ex) {
            Log.e(ex);
            return false;
        }
    }

    private static void setLabel(Context context,
                                 EntityAccount account,
                                 EntityMessage message,
                                 int label,
                                 Long requestedFamilyId) {
        try {
            if (context == null || account == null || message == null ||
                    message.id == null || account.uuid == null)
                return;

            SpamIntelligenceDB intelligence = SpamIntelligenceDB.getInstance(context);
            DaoAlias dao = intelligence.alias();

            EntityAliasDelivery before = dao.getDelivery(account.uuid, message.id);
            if (before == null && message.folder != null && message.deliveredto != null) {
                EntityFolder folder = DB.getInstance(context).folder().getFolder(message.folder);
                if (folder != null)
                    observeMessage(context, account, folder, message);
                before = dao.getDelivery(account.uuid, message.id);
            }
            if (before == null)
                return;

            Long familyId = requestedFamilyId;
            SpamFamilyStore.LearnResult familyLearn = null;
            if (label == EntityAliasDelivery.LABEL_SPAM) {
                SpamFamilyFingerprint fingerprint = SpamFamilyMessageAdapter.fromMessage(context, message);
                SpamFamilyIdentity.Identity identity = SpamFamilyMessageAdapter.identityFromMessage(message);
                if (requestedFamilyId != null) {
                    familyLearn = SpamFamilyStore.learnSpamIntoFamily(
                            context, account.uuid, message.id, fingerprint, requestedFamilyId);
                    familyId = familyLearn.familyId;
                    if (identity != null && familyId != null)
                        SpamFamilyStore.bindIdentity(context, account.uuid, identity.key, familyId);
                } else if (fingerprint != null && identity != null) {
                    familyLearn = SpamFamilyStore.learnSpamExact(
                            context, account.uuid, message.id, fingerprint, identity.key);
                    familyId = familyLearn.familyId;
                } else if (fingerprint != null) {
                    // Missing identity data must never re-enable fuzzy family joining.
                    // Keep the message in a deterministic one-message family until a
                    // complete sender-name + subject identity becomes available.
                    familyLearn = SpamFamilyStore.learnSpamExact(
                            context, account.uuid, message.id, fingerprint,
                            SpamFamilyIdentity.isolatedMessageKey(message.id));
                    familyId = familyLearn.familyId;
                }

                if (familyLearn != null && familyLearn.learned)
                    Log.i("SpamFamily learned family=" + familyId +
                            " message=" + message.id +
                            " created=" + familyLearn.created +
                            " previous=" + familyLearn.previousBest);
            }

            boolean changed = SpamAliasStore.setLabel(
                    context,
                    account.uuid,
                    message.id,
                    label,
                    familyId);
            if (!changed) {
                if (familyLearn != null && familyLearn.learned &&
                        before.label != EntityAliasDelivery.LABEL_SPAM)
                    SpamFamilyStore.unlearnMessage(
                            context, account.uuid, message.id, familyLearn.familyId);
                return;
            }

            Long changedFamily = null;
            if (label == EntityAliasDelivery.LABEL_SPAM && familyId != null) {
                SpamFamilyStore.reconcileFamily(context, familyId);
                if (familyLearn != null && familyLearn.learned)
                    changedFamily = familyId;
            } else if (before.label == EntityAliasDelivery.LABEL_SPAM) {
                changedFamily = SpamFamilyStore.unlearnMessage(
                        context, account.uuid, message.id, before.family_id);
            }

            if (changedFamily != null) {
                if (intelligence.family().getFamily(changedFamily) != null)
                    SpamFamilyRescorer.enqueue(context, account.uuid, changedFamily);
                else
                    SpamFamilyRescorer.enqueueAllActive(context, account.uuid);
            }

            EntityAliasDelivery after = dao.getDelivery(account.uuid, message.id);
            if (after != null) {
                if (label == EntityAliasDelivery.LABEL_SPAM) {
                    if (SpamControlPolicy.markAliasCompromised(context)) {
                        AliasCompromisePolicy.Decision compromise =
                                aliasCompromiseDecision(context, account, message);
                        if (compromise == AliasCompromisePolicy.Decision.COMPROMISE)
                            dao.markCompromised(account.uuid, after.address);
                        else if (compromise == AliasCompromisePolicy.Decision.REVIEW)
                            Log.i("SpamControl alias compromise needs review alias=" + after.address +
                                    " message=" + message.id);
                        else if (compromise == AliasCompromisePolicy.Decision.KEEP_ACTIVE) {
                            EntityAlias currentAlias = dao.getAlias(account.uuid, after.address);
                            if (currentAlias != null)
                                AliasCompromiseReviewStore.markReviewedHealthy(context, currentAlias);
                        }
                    }
                } else {
                    EntityAlias alias = dao.getAlias(account.uuid, after.address);
                    if (alias != null && alias.state == EntityAlias.STATE_COMPROMISED &&
                            alias.spam_hits == 0)
                        dao.setState(account.uuid, after.address, EntityAlias.STATE_ACTIVE);
                }
            }

            refreshAssessment(context, account, message, false);
            refreshFamilyMatch(context, account, message, false);
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

    private static void refreshFamilyMatch(Context context,
                                           EntityAccount account,
                                           EntityMessage message,
                                           boolean log) {
        if (context == null || account == null || account.uuid == null ||
                message == null || message.id == null)
            return;

        DaoSpamFamily dao = SpamIntelligenceDB.getInstance(context).family();
        long assessedAt = System.currentTimeMillis();
        if (!SpamControlPolicy.exactFamilyDetection(context)) {
            dao.clearFamilyMatch(account.uuid, message.id, assessedAt);
            return;
        }

        SpamFamilyIdentity.Identity identity = SpamFamilyMessageAdapter.identityFromMessage(message);
        if (identity == null) {
            dao.clearFamilyMatch(account.uuid, message.id, assessedAt);
            return;
        }

        SpamFamilyStore.Match match = SpamFamilyStore.matchIdentity(
                context, account.uuid, message.id, identity.key);
        if (match.familyId == null) {
            dao.clearFamilyMatch(account.uuid, message.id, assessedAt);
            return;
        }

        SpamFamilyEngine.Score score = match.score;
        dao.setFamilyMatch(account.uuid, message.id, match.familyId,
                score.value, score.raw, score.text, score.structure,
                score.links, score.sender, assessedAt);
        if (log)
            Log.i("SpamFamily exact match family=" + match.familyId +
                    " message=" + message.id + " identity=" + identity.key);
    }

    private static void maybeAutoLabelExact(Context context,
                                            EntityAccount account,
                                            EntityMessage message) {
        if (!SpamControlPolicy.autoLabelExact(context) || message == null || message.id == null)
            return;
        try {
            EntityAliasDelivery delivery = SpamIntelligenceDB.getInstance(context)
                    .alias().getDelivery(account.uuid, message.id);
            if (delivery == null || delivery.label != EntityAliasDelivery.LABEL_UNKNOWN ||
                    delivery.predicted_family_id == null)
                return;
            learnSpam(context, account, message, delivery.predicted_family_id);
            Log.i("SpamControl auto-confirm exact family=" + delivery.predicted_family_id +
                    " message=" + message.id);
        } catch (Throwable ex) {
            Log.e(ex);
        }
    }

    private static AliasCompromisePolicy.Decision aliasCompromiseDecision(
            Context context, EntityAccount account, EntityMessage message) {
        AliasTrafficAnalyzer.Result traffic = AliasTrafficAnalyzer.assess(
                context, account, message);
        AliasCompromisePolicy.Input input = new AliasCompromisePolicy.Input();
        input.explicitSpam = true;
        input.trafficSuspicious = traffic.assessment.verdict ==
                AliasTrafficScorer.Verdict.SUSPICIOUS;
        input.senderKnown = traffic.senderDomain != null;
        input.expectedContextKnown = traffic.serviceDomain != null ||
                (traffic.trustedDomains != null && !traffic.trustedDomains.isEmpty());
        input.expectedSenderMatch = traffic.senderDomain != null &&
                ((traffic.serviceDomain != null &&
                        traffic.senderDomain.equalsIgnoreCase(traffic.serviceDomain)) ||
                        containsIgnoreCase(traffic.trustedDomains, traffic.senderDomain));
        input.aliasHam = traffic.aliasHam;
        return AliasCompromisePolicy.decide(input);
    }

    private static boolean containsIgnoreCase(java.util.List<String> values, String needle) {
        if (values == null || needle == null)
            return false;
        for (String value : values)
            if (needle.equalsIgnoreCase(value))
                return true;
        return false;
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

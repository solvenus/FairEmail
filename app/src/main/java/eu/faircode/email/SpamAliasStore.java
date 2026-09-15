package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;

/** Transactional adapter between FairEmail messages and the alias registry. */
public final class SpamAliasStore {
    private SpamAliasStore() {
    }

    public static boolean observeDelivery(Context context,
                                          String accountUuid,
                                          long messageId,
                                          String deliveredTo,
                                          long received,
                                          String folderType) {
        return observeDelivery(context, accountUuid, messageId, deliveredTo,
                received, folderType, null, false);
    }

    /**
     * Record a received message exactly once while allowing later observations
     * of the same FairEmail message to enrich metadata without double counting.
     *
     * @return true only when this message created a new delivery observation.
     */
    public static boolean observeDelivery(Context context,
                                          String accountUuid,
                                          long messageId,
                                          String deliveredTo,
                                          long received,
                                          String folderType,
                                          String senderDomain,
                                          boolean hasUnsubscribe) {
        final String account = normalizeAccount(accountUuid);
        final String address = AliasRegistry.normalizeAddress(deliveredTo);
        if (context == null || account == null || address == null || messageId <= 0)
            return false;

        final long timestamp = received > 0 ? received : System.currentTimeMillis();
        final String folder = normalizeToken(folderType);
        final String sender = normalizeDomain(senderDomain);
        final SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(context);

        return db.runInTransaction(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                DaoAlias dao = db.alias();

                EntityAliasDelivery existing = dao.getDelivery(account, messageId);
                if (existing != null) {
                    if (!Objects.equals(existing.folder_type, folder)) {
                        changeFolderCount(dao, account, existing.address,
                                existing.folder_type, folder);
                        dao.setDeliveryFolder(account, messageId, folder);
                    }

                    String effectiveSender = existing.sender_domain;
                    if (sender != null && !Objects.equals(existing.sender_domain, sender)) {
                        changeDomainCount(dao, account, existing.address,
                                existing.sender_domain, sender);
                        effectiveSender = sender;
                        maybeInferServiceDomain(dao, account, existing.address, sender);
                    }

                    boolean effectiveUnsubscribe = existing.has_unsubscribe || hasUnsubscribe;
                    if (!Objects.equals(existing.sender_domain, effectiveSender) ||
                            existing.has_unsubscribe != effectiveUnsubscribe)
                        dao.setDeliveryEvidence(account, messageId,
                                effectiveSender, effectiveUnsubscribe);
                    return false;
                }

                EntityAlias alias = dao.getAlias(account, address);
                if (alias == null) {
                    alias = new EntityAlias();
                    alias.account_uuid = account;
                    alias.address = address;
                    alias.service = AliasRegistry.inferServiceName(address);
                    alias.first_seen = timestamp;
                    alias.last_seen = timestamp;
                    if (sender != null)
                        alias.service_domain = AliasDomainAffinity.inferServiceDomain(address, sender);
                    dao.insertAlias(alias);
                }

                EntityAliasDelivery delivery = new EntityAliasDelivery();
                delivery.account_uuid = account;
                delivery.message_id = messageId;
                delivery.address = address;
                delivery.received = timestamp;
                delivery.folder_type = folder;
                delivery.sender_domain = sender;
                delivery.has_unsubscribe = hasUnsubscribe;

                long inserted = dao.insertDelivery(delivery);
                if (inserted == -1)
                    return false;

                dao.observeDelivery(account, address, timestamp);
                changeFolderCount(dao, account, address, null, folder);
                if (sender != null) {
                    changeDomainCount(dao, account, address, null, sender);
                    maybeInferServiceDomain(dao, account, address, sender);
                }
                return true;
            }
        });
    }

    /** Persist the explainable traffic assessment snapshot for one delivery. */
    public static boolean setAssessment(Context context,
                                        String accountUuid,
                                        long messageId,
                                        AliasTrafficScorer.Assessment assessment) {
        final String account = normalizeAccount(accountUuid);
        if (context == null || account == null || messageId <= 0 || assessment == null)
            return false;

        JSONArray reasons = new JSONArray();
        for (String reason : assessment.reasons)
            reasons.put(reason);

        return SpamIntelligenceDB.getInstance(context).alias().setDeliveryAssessment(
                account,
                messageId,
                assessment.spamSupport,
                assessment.hamSupport,
                assessment.net,
                assessment.verdict.name(),
                reasons.toString(),
                System.currentTimeMillis()) > 0;
    }

    /**
     * Change the learned label for one delivery without double counting.
     * Labels are independent of the physical folder a message currently lives in.
     */
    public static boolean setLabel(Context context,
                                   String accountUuid,
                                   long messageId,
                                   int label,
                                   Long familyId) {
        if (label != EntityAliasDelivery.LABEL_UNKNOWN &&
                label != EntityAliasDelivery.LABEL_HAM &&
                label != EntityAliasDelivery.LABEL_SPAM)
            throw new IllegalArgumentException("label=" + label);

        final String account = normalizeAccount(accountUuid);
        if (context == null || account == null || messageId <= 0)
            return false;

        final SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(context);
        return db.runInTransaction(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                DaoAlias dao = db.alias();
                EntityAliasDelivery delivery = dao.getDelivery(account, messageId);
                if (delivery == null)
                    return false;

                int oldLabel = delivery.label;
                Long oldFamily = delivery.family_id;
                if (oldLabel == label && Objects.equals(oldFamily, familyId))
                    return false;

                int spamDelta = deltaFor(oldLabel, label, EntityAliasDelivery.LABEL_SPAM);
                int hamDelta = deltaFor(oldLabel, label, EntityAliasDelivery.LABEL_HAM);
                dao.adjustLabels(account, delivery.address, spamDelta, hamDelta, delivery.received);

                if (oldLabel == EntityAliasDelivery.LABEL_SPAM && oldFamily != null &&
                        !(label == EntityAliasDelivery.LABEL_SPAM && Objects.equals(oldFamily, familyId)))
                    changeFamilyCount(dao, account, delivery.address, oldFamily, -1);

                if (label == EntityAliasDelivery.LABEL_SPAM && familyId != null &&
                        !(oldLabel == EntityAliasDelivery.LABEL_SPAM && Objects.equals(oldFamily, familyId)))
                    changeFamilyCount(dao, account, delivery.address, familyId, 1);

                dao.setDeliveryLabel(account, messageId, label,
                        label == EntityAliasDelivery.LABEL_SPAM ? familyId : null);
                return true;
            }
        });
    }

    /** Keep the delivery ledger and cached per-alias folder counters in lockstep. */
    public static void setFolder(Context context,
                                 String accountUuid,
                                 long messageId,
                                 String folderType) {
        final String account = normalizeAccount(accountUuid);
        if (context == null || account == null || messageId <= 0)
            return;

        final String folder = normalizeToken(folderType);
        final SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(context);
        db.runInTransaction(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                DaoAlias dao = db.alias();
                EntityAliasDelivery delivery = dao.getDelivery(account, messageId);
                if (delivery == null || Objects.equals(delivery.folder_type, folder))
                    return false;

                changeFolderCount(dao, account, delivery.address,
                        delivery.folder_type, folder);
                dao.setDeliveryFolder(account, messageId, folder);
                return true;
            }
        });
    }

    private static int deltaFor(int oldLabel, int newLabel, int target) {
        int oldValue = oldLabel == target ? 1 : 0;
        int newValue = newLabel == target ? 1 : 0;
        return newValue - oldValue;
    }

    private static void maybeInferServiceDomain(DaoAlias dao,
                                                String account,
                                                String address,
                                                String senderDomain) {
        EntityAlias alias = dao.getAlias(account, address);
        if (alias == null || alias.service_domain != null)
            return;
        String inferred = AliasDomainAffinity.inferServiceDomain(address, senderDomain);
        if (inferred != null)
            dao.setServiceDomain(account, address, inferred);
    }

    private static void changeDomainCount(DaoAlias dao,
                                          String account,
                                          String address,
                                          String oldDomain,
                                          String newDomain) throws JSONException {
        if (Objects.equals(oldDomain, newDomain))
            return;

        EntityAlias alias = dao.getAlias(account, address);
        if (alias == null)
            return;

        String counts = alias.observed_domains;
        if (oldDomain != null)
            counts = AliasDomainAffinity.incrementDomainCounter(counts, oldDomain, -1);
        if (newDomain != null)
            counts = AliasDomainAffinity.incrementDomainCounter(counts, newDomain, 1);
        dao.setObservedDomains(account, address, counts);
    }

    private static void changeFolderCount(DaoAlias dao,
                                          String account,
                                          String address,
                                          String oldFolder,
                                          String newFolder) throws JSONException {
        if (Objects.equals(oldFolder, newFolder))
            return;

        EntityAlias alias = dao.getAlias(account, address);
        if (alias == null)
            return;

        String counts = alias.folder_counts;
        if (oldFolder != null)
            counts = incrementCounter(counts, oldFolder, -1);
        if (newFolder != null)
            counts = incrementCounter(counts, newFolder, 1);
        dao.setFolderCounts(account, address, counts);
    }

    private static void changeFamilyCount(DaoAlias dao,
                                          String account,
                                          String address,
                                          long familyId,
                                          int delta) throws JSONException {
        EntityAlias alias = dao.getAlias(account, address);
        String counts = incrementCounter(alias == null ? null : alias.family_counts,
                Long.toString(familyId), delta);
        dao.setFamilyCounts(account, address, counts);
    }

    static String incrementCounter(String json, String key, int delta) throws JSONException {
        JSONObject object;
        try {
            object = (json == null || json.trim().isEmpty()) ? new JSONObject() : new JSONObject(json);
        } catch (JSONException ex) {
            // A damaged aggregate must not poison ingestion. Rebuild this small cache.
            object = new JSONObject();
        }

        int next = Math.max(0, object.optInt(key, 0) + delta);
        if (next == 0)
            object.remove(key);
        else
            object.put(key, next);
        return object.toString();
    }

    private static String normalizeAccount(String value) {
        if (value == null)
            return null;
        String account = value.trim();
        return account.isEmpty() ? null : account;
    }

    private static String normalizeDomain(String value) {
        if (value == null)
            return null;
        String domain = value.trim().toLowerCase(Locale.ROOT);
        return domain.isEmpty() ? null : domain;
    }

    private static String normalizeToken(String value) {
        if (value == null)
            return null;
        String token = value.trim().toLowerCase(Locale.ROOT);
        return token.isEmpty() ? null : token;
    }
}

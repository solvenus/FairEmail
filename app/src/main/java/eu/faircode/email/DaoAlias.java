package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface DaoAlias {
    @Query("SELECT * FROM alias" +
            " WHERE account_uuid = :accountUuid" +
            " ORDER BY last_seen DESC, address COLLATE NOCASE")
    LiveData<List<EntityAlias>> liveAliases(String accountUuid);

    @Query("SELECT * FROM alias" +
            " WHERE account_uuid = :accountUuid" +
            " ORDER BY last_seen DESC, address COLLATE NOCASE")
    List<EntityAlias> getAliases(String accountUuid);

    @Query("SELECT * FROM alias" +
            " WHERE account_uuid = :accountUuid AND address = :address" +
            " LIMIT 1")
    EntityAlias getAlias(String accountUuid, String address);

    @Query("SELECT * FROM alias" +
            " WHERE account_uuid = :accountUuid AND spam_hits > 0" +
            " ORDER BY spam_hits DESC, last_spam DESC, last_seen DESC")
    LiveData<List<EntityAlias>> liveSpamAffected(String accountUuid);

    @Query("SELECT * FROM alias" +
            " WHERE smtp_reject_state <> " + EntityAlias.SMTP_REJECT_NONE +
            " ORDER BY smtp_reject_requested_at DESC, last_seen DESC")
    LiveData<List<EntityAlias>> liveSmtpManagedAliases();

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertAlias(EntityAlias alias);

    @Update
    int updateAlias(EntityAlias alias);

    @Query("UPDATE alias SET" +
            " first_seen = CASE WHEN :received < first_seen THEN :received ELSE first_seen END," +
            " last_seen = CASE WHEN :received > last_seen THEN :received ELSE last_seen END," +
            " messages = messages + 1" +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int observeDelivery(String accountUuid, String address, long received);

    @Query("UPDATE alias SET" +
            " spam_hits = MAX(0, spam_hits + :spamDelta)," +
            " ham_hits = MAX(0, ham_hits + :hamDelta)," +
            " last_spam = CASE WHEN :spamDelta > 0 AND (last_spam IS NULL OR :received > last_spam)" +
            " THEN :received ELSE last_spam END," +
            " last_ham = CASE WHEN :hamDelta > 0 AND (last_ham IS NULL OR :received > last_ham)" +
            " THEN :received ELSE last_ham END" +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int adjustLabels(String accountUuid, String address,
                     int spamDelta, int hamDelta, long received);

    @Query("UPDATE alias SET folder_counts = :folderCounts" +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int setFolderCounts(String accountUuid, String address, String folderCounts);

    @Query("UPDATE alias SET family_counts = :familyCounts" +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int setFamilyCounts(String accountUuid, String address, String familyCounts);

    @Query("UPDATE alias SET observed_domains = :observedDomains" +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int setObservedDomains(String accountUuid, String address, String observedDomains);

    @Query("UPDATE alias SET trusted_domains = :trustedDomains" +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int setTrustedDomains(String accountUuid, String address, String trustedDomains);

    @Query("UPDATE alias SET service_domain = :serviceDomain" +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int setServiceDomain(String accountUuid, String address, String serviceDomain);

    @Query("UPDATE alias SET state = :state" +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int setState(String accountUuid, String address, int state);

    @Query("UPDATE alias SET service = :service" +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int setService(String accountUuid, String address, String service);

    @Query("UPDATE alias SET note = :note" +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int setNote(String accountUuid, String address, String note);

    @Query("UPDATE alias SET replaced_by = :replacement, state = " + EntityAlias.STATE_REPLACED +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int markReplaced(String accountUuid, String address, String replacement);

    @Query("UPDATE alias SET state = " + EntityAlias.STATE_COMPROMISED +
            " WHERE account_uuid = :accountUuid AND address = :address" +
            " AND state = " + EntityAlias.STATE_ACTIVE)
    int markCompromised(String accountUuid, String address);

    @Query("UPDATE alias SET" +
            " smtp_reject_state = " + EntityAlias.SMTP_REJECT_PENDING + "," +
            " smtp_reject_provider = :provider," +
            " smtp_reject_reason = :reason," +
            " smtp_reject_requested_at = :requestedAt," +
            " smtp_reject_verified_at = NULL," +
            " smtp_reject_error = NULL" +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int markSmtpRejectPending(String accountUuid, String address,
                              String provider, String reason, long requestedAt);

    @Query("UPDATE alias SET" +
            " smtp_reject_state = " + EntityAlias.SMTP_REJECT_VERIFIED + "," +
            " smtp_reject_verified_at = :verifiedAt," +
            " smtp_reject_error = NULL" +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int markSmtpRejectVerified(String accountUuid, String address, long verifiedAt);

    @Query("UPDATE alias SET" +
            " smtp_reject_state = " + EntityAlias.SMTP_REJECT_FAILED + "," +
            " smtp_reject_error = :error" +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int markSmtpRejectFailed(String accountUuid, String address, String error);

    @Query("UPDATE alias SET" +
            " smtp_reject_state = " + EntityAlias.SMTP_RESTORE_PENDING + "," +
            " smtp_reject_error = NULL" +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int markSmtpRestorePending(String accountUuid, String address);

    @Query("UPDATE alias SET" +
            " smtp_reject_state = " + EntityAlias.SMTP_REJECT_NONE + "," +
            " smtp_reject_provider = NULL," +
            " smtp_reject_reason = NULL," +
            " smtp_reject_requested_at = NULL," +
            " smtp_reject_verified_at = NULL," +
            " smtp_reject_error = NULL" +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int clearSmtpReject(String accountUuid, String address);

    /** Clear learned spam/ham state while preserving user metadata and SMTP actuator state. */
    @Query("UPDATE alias SET" +
            " spam_hits = 0," +
            " ham_hits = 0," +
            " last_spam = NULL," +
            " last_ham = NULL," +
            " family_counts = '{}'," +
            " state = CASE WHEN state = " + EntityAlias.STATE_COMPROMISED +
            " THEN " + EntityAlias.STATE_ACTIVE + " ELSE state END" +
            " WHERE account_uuid = :accountUuid")
    int resetAliasLearning(String accountUuid);

    @Query("DELETE FROM alias WHERE account_uuid = :accountUuid")
    int deleteAliases(String accountUuid);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertDelivery(EntityAliasDelivery delivery);

    @Query("SELECT * FROM alias_delivery" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId" +
            " LIMIT 1")
    EntityAliasDelivery getDelivery(String accountUuid, long messageId);

    @Query("SELECT * FROM alias_delivery" +
            " WHERE account_uuid = :accountUuid" +
            " AND label = " + EntityAliasDelivery.LABEL_UNKNOWN +
            " AND message_id > :afterMessageId" +
            " ORDER BY message_id" +
            " LIMIT :limit")
    List<EntityAliasDelivery> getUnknownDeliveriesAfter(
            String accountUuid, long afterMessageId, int limit);

    @Query("SELECT * FROM alias_delivery" +
            " WHERE account_uuid = :accountUuid" +
            " AND (:includeReviewed OR label = " + EntityAliasDelivery.LABEL_UNKNOWN + ")" +
            " AND (traffic_verdict = 'SUSPICIOUS'" +
            "   OR folder_type = '" + EntityFolder.JUNK + "'" +
            "   OR (predicted_family_id IS NOT NULL AND family_score >= 0.999999))" +
            " ORDER BY" +
            " CASE WHEN label = " + EntityAliasDelivery.LABEL_UNKNOWN + " THEN 0 ELSE 1 END," +
            " CASE WHEN traffic_verdict = 'SUSPICIOUS' THEN 0 ELSE 1 END," +
            " spam_support DESC, family_score DESC, received DESC" +
            " LIMIT :limit")
    List<EntityAliasDelivery> getReviewQueue(String accountUuid, boolean includeReviewed, int limit);

    @Query("SELECT COUNT(*) FROM alias_delivery" +
            " WHERE account_uuid = :accountUuid" +
            " AND (:includeReviewed OR label = " + EntityAliasDelivery.LABEL_UNKNOWN + ")" +
            " AND (traffic_verdict = 'SUSPICIOUS'" +
            "   OR folder_type = '" + EntityFolder.JUNK + "'" +
            "   OR (predicted_family_id IS NOT NULL AND family_score >= 0.999999))")
    int countReviewQueue(String accountUuid, boolean includeReviewed);

    @Query("UPDATE alias_delivery SET folder_type = :folderType" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId")
    int setDeliveryFolder(String accountUuid, long messageId, String folderType);

    @Query("UPDATE alias_delivery SET sender_domain = :senderDomain," +
            " has_unsubscribe = :hasUnsubscribe" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId")
    int setDeliveryEvidence(String accountUuid, long messageId,
                            String senderDomain, boolean hasUnsubscribe);

    @Query("UPDATE alias_delivery SET" +
            " spam_support = :spamSupport," +
            " ham_support = :hamSupport," +
            " traffic_net = :net," +
            " traffic_verdict = :verdict," +
            " traffic_reasons = :reasons," +
            " assessed_at = :assessedAt" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId")
    int setDeliveryAssessment(String accountUuid, long messageId,
                              double spamSupport, double hamSupport, double net,
                              String verdict, String reasons, long assessedAt);

    @Query("UPDATE alias_delivery SET label = :label, family_id = :familyId" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId")
    int setDeliveryLabel(String accountUuid, long messageId, int label, Long familyId);

    /** Reset all derived learning on retained raw delivery observations. */
    @Query("UPDATE alias_delivery SET" +
            " label = " + EntityAliasDelivery.LABEL_UNKNOWN + "," +
            " family_id = NULL," +
            " predicted_family_id = NULL," +
            " family_score = NULL," +
            " family_score_raw = NULL," +
            " family_text = NULL," +
            " family_structure = NULL," +
            " family_links = NULL," +
            " family_sender = NULL," +
            " family_assessed_at = NULL," +
            " spam_support = NULL," +
            " ham_support = NULL," +
            " traffic_net = NULL," +
            " traffic_verdict = NULL," +
            " traffic_reasons = NULL," +
            " assessed_at = NULL" +
            " WHERE account_uuid = :accountUuid")
    int resetDeliveryLearning(String accountUuid);

    @Query("SELECT * FROM alias_delivery" +
            " WHERE account_uuid = :accountUuid AND traffic_verdict = :verdict" +
            " ORDER BY spam_support DESC, received DESC")
    LiveData<List<EntityAliasDelivery>> liveByTrafficVerdict(String accountUuid, String verdict);

    @Query("SELECT sender_domain AS domain," +
            " COUNT(*) AS messages," +
            " SUM(CASE WHEN has_unsubscribe THEN 1 ELSE 0 END) AS unsubscribe," +
            " SUM(CASE WHEN label = " + EntityAliasDelivery.LABEL_HAM + " THEN 1 ELSE 0 END) AS ham," +
            " SUM(CASE WHEN label = " + EntityAliasDelivery.LABEL_SPAM + " THEN 1 ELSE 0 END) AS spam" +
            " FROM alias_delivery" +
            " WHERE account_uuid = :accountUuid AND address = :address" +
            " AND sender_domain IS NOT NULL" +
            " GROUP BY sender_domain" +
            " ORDER BY ham DESC, unsubscribe DESC, messages DESC, sender_domain COLLATE NOCASE")
    List<TupleAliasDomainStats> getDomainStats(String accountUuid, String address);

    @Query("SELECT COUNT(*) FROM alias_delivery" +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int countDeliveries(String accountUuid, String address);

    @Query("SELECT COUNT(*) FROM alias_delivery" +
            " WHERE account_uuid = :accountUuid AND address = :address" +
            " AND label = :label")
    int countAliasLabel(String accountUuid, String address, int label);

    @Query("SELECT COUNT(*) FROM alias_delivery" +
            " WHERE account_uuid = :accountUuid AND address = :address" +
            " AND sender_domain = :senderDomain AND label = :label")
    int countSenderDomainLabel(String accountUuid, String address,
                               String senderDomain, int label);

    @Query("DELETE FROM alias_delivery WHERE account_uuid = :accountUuid")
    int deleteDeliveries(String accountUuid);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertSpamIntent(EntitySpamIntent intent);

    @Query("SELECT * FROM spam_intent" +
            " ORDER BY operation_id" +
            " LIMIT :limit")
    List<EntitySpamIntent> getSpamIntents(int limit);

    @Query("UPDATE spam_intent SET" +
            " attempts = attempts + 1," +
            " last_attempt_at = :attemptedAt" +
            " WHERE operation_id = :operationId")
    int markSpamIntentAttempt(long operationId, long attemptedAt);

    @Query("DELETE FROM spam_intent WHERE operation_id = :operationId")
    int deleteSpamIntent(long operationId);

    @Query("DELETE FROM spam_intent")
    int deleteAllSpamIntents();

    @Query("SELECT long_value FROM spam_meta WHERE `key` = :key LIMIT 1")
    Long getMetaLong(String key);

    @Query("SELECT text_value FROM spam_meta WHERE `key` = :key LIMIT 1")
    String getMetaText(String key);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putMeta(EntitySpamMeta meta);
}

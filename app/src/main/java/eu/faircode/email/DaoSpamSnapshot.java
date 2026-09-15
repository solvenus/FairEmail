package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * Low-level snapshot/restore surface for durable semantic undo.
 * No table is owned by this DAO; it operates on the existing intelligence rows.
 */
@Dao
public interface DaoSpamSnapshot {
    @Query("SELECT * FROM spam_family WHERE account_uuid = :accountUuid ORDER BY id")
    List<EntitySpamFamily> getFamilies(String accountUuid);

    @Query("SELECT * FROM spam_family ORDER BY account_uuid, id")
    List<EntitySpamFamily> getAllFamilies();

    @Query("SELECT * FROM spam_family_exemplar WHERE account_uuid = :accountUuid ORDER BY id")
    List<EntitySpamFamilyExemplar> getExemplars(String accountUuid);

    @Query("SELECT * FROM spam_family_exemplar ORDER BY account_uuid, id")
    List<EntitySpamFamilyExemplar> getAllExemplars();

    @Query("SELECT * FROM spam_family_exclusion WHERE account_uuid = :accountUuid" +
            " ORDER BY message_id, family_id")
    List<EntitySpamFamilyExclusion> getExclusions(String accountUuid);

    @Query("SELECT * FROM spam_family_exclusion ORDER BY account_uuid, message_id, family_id")
    List<EntitySpamFamilyExclusion> getAllExclusions();

    @Query("SELECT * FROM alias WHERE account_uuid = :accountUuid AND address = :address LIMIT 1")
    EntityAlias getAlias(String accountUuid, String address);

    @Query("SELECT * FROM alias ORDER BY account_uuid, address")
    List<EntityAlias> getAllAliases();

    @Query("SELECT * FROM alias_delivery" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId LIMIT 1")
    EntityAliasDelivery getDelivery(String accountUuid, long messageId);

    @Query("SELECT * FROM alias_delivery ORDER BY account_uuid, message_id")
    List<EntityAliasDelivery> getAllDeliveries();

    @Query("DELETE FROM spam_family_exclusion WHERE account_uuid = :accountUuid")
    int deleteExclusions(String accountUuid);

    @Query("DELETE FROM spam_family_exemplar WHERE account_uuid = :accountUuid")
    int deleteExemplars(String accountUuid);

    @Query("DELETE FROM spam_family WHERE account_uuid = :accountUuid")
    int deleteFamilies(String accountUuid);

    @Query("DELETE FROM spam_rescore_task WHERE account_uuid = :accountUuid")
    int deleteRescoreTasks(String accountUuid);

    @Query("DELETE FROM spam_family_exclusion")
    int deleteAllExclusions();

    @Query("DELETE FROM spam_family_exemplar")
    int deleteAllExemplars();

    @Query("DELETE FROM spam_family")
    int deleteAllFamilies();

    @Query("DELETE FROM spam_rescore_task")
    int deleteAllRescoreTasks();

    @Query("DELETE FROM spam_intent")
    int deleteAllSpamIntents();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putFamilies(List<EntitySpamFamily> rows);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putExemplars(List<EntitySpamFamilyExemplar> rows);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putExclusions(List<EntitySpamFamilyExclusion> rows);

    @Query("UPDATE alias SET" +
            " spam_hits = :spamHits," +
            " ham_hits = :hamHits," +
            " last_spam = :lastSpam," +
            " last_ham = :lastHam," +
            " family_counts = :familyCounts," +
            " state = :state" +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int restoreAliasLearning(String accountUuid, String address,
                             int spamHits, int hamHits, Long lastSpam, Long lastHam,
                             String familyCounts, int state);

    @Query("UPDATE alias_delivery SET" +
            " label = :label," +
            " family_id = :familyId," +
            " predicted_family_id = :predictedFamilyId," +
            " family_score = :familyScore," +
            " family_score_raw = :familyScoreRaw," +
            " family_text = :familyText," +
            " family_structure = :familyStructure," +
            " family_links = :familyLinks," +
            " family_sender = :familySender," +
            " family_assessed_at = :familyAssessedAt," +
            " spam_support = :spamSupport," +
            " ham_support = :hamSupport," +
            " traffic_net = :trafficNet," +
            " traffic_verdict = :trafficVerdict," +
            " traffic_reasons = :trafficReasons," +
            " assessed_at = :assessedAt" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId")
    int restoreDeliveryLearning(String accountUuid, long messageId,
                                int label, Long familyId,
                                Long predictedFamilyId,
                                Double familyScore, Double familyScoreRaw,
                                Double familyText, Double familyStructure,
                                Double familyLinks, Double familySender,
                                Long familyAssessedAt,
                                Double spamSupport, Double hamSupport,
                                Double trafficNet, String trafficVerdict,
                                String trafficReasons, Long assessedAt);

    @Query("UPDATE alias SET" +
            " spam_hits = 0," +
            " ham_hits = 0," +
            " last_spam = NULL," +
            " last_ham = NULL," +
            " family_counts = '{}'," +
            " state = CASE WHEN state = " + EntityAlias.STATE_COMPROMISED +
            " THEN " + EntityAlias.STATE_ACTIVE + " ELSE state END")
    int resetAllAliasLearning();

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
            " assessed_at = NULL")
    int resetAllDeliveryLearning();

    @Query("UPDATE alias_delivery SET" +
            " predicted_family_id = NULL," +
            " family_score = NULL," +
            " family_score_raw = NULL," +
            " family_text = NULL," +
            " family_structure = NULL," +
            " family_links = NULL," +
            " family_sender = NULL," +
            " family_assessed_at = NULL" +
            " WHERE account_uuid = :accountUuid")
    int clearPredictions(String accountUuid);
}

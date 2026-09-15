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

import java.util.List;

@Dao
public interface DaoSpamFamily {
    @Query("SELECT * FROM spam_family" +
            " WHERE account_uuid = :accountUuid AND active" +
            " ORDER BY updated_at DESC, id")
    List<EntitySpamFamily> getActiveFamilies(String accountUuid);

    @Query("SELECT * FROM spam_family" +
            " WHERE account_uuid = :accountUuid" +
            " ORDER BY updated_at DESC, id")
    LiveData<List<EntitySpamFamily>> liveFamilies(String accountUuid);

    @Query("SELECT f.id AS family_id," +
            " f.name AS name," +
            " f.active AS active," +
            " f.confirmed_count AS confirmed_count," +
            " f.created_at AS created_at," +
            " f.updated_at AS updated_at," +
            " (SELECT COUNT(*) FROM spam_family_exemplar e" +
            "   WHERE e.family_id = f.id) AS exemplar_count," +
            " (SELECT COUNT(*) FROM alias_delivery d" +
            "   WHERE d.account_uuid = f.account_uuid" +
            "   AND d.predicted_family_id = f.id) AS predicted_count," +
            " (SELECT COUNT(*) FROM alias_delivery d" +
            "   WHERE d.account_uuid = f.account_uuid" +
            "   AND d.predicted_family_id = f.id" +
            "   AND d.family_score >= :strongThreshold) AS strong_count," +
            " (SELECT COUNT(*) FROM alias_delivery d" +
            "   WHERE d.account_uuid = f.account_uuid" +
            "   AND d.predicted_family_id = f.id" +
            "   AND d.family_score >= :strongThreshold" +
            "   AND d.label = " + EntityAliasDelivery.LABEL_UNKNOWN + ") AS strong_unknown_count," +
            " (SELECT COUNT(*) FROM alias_delivery d" +
            "   WHERE d.account_uuid = f.account_uuid" +
            "   AND d.predicted_family_id = f.id" +
            "   AND d.family_score >= :strongThreshold" +
            "   AND d.label = " + EntityAliasDelivery.LABEL_HAM + ") AS strong_ham_count," +
            " (SELECT MAX(d.family_score) FROM alias_delivery d" +
            "   WHERE d.account_uuid = f.account_uuid" +
            "   AND d.predicted_family_id = f.id) AS max_score," +
            " (SELECT t.processed FROM spam_rescore_task t" +
            "   WHERE t.account_uuid = f.account_uuid AND t.family_id = f.id) AS rescore_processed," +
            " (SELECT t.matches FROM spam_rescore_task t" +
            "   WHERE t.account_uuid = f.account_uuid AND t.family_id = f.id) AS rescore_matches," +
            " (SELECT t.before_message_id FROM spam_rescore_task t" +
            "   WHERE t.account_uuid = f.account_uuid AND t.family_id = f.id) AS rescore_cursor" +
            " FROM spam_family f" +
            " WHERE f.account_uuid = :accountUuid" +
            " ORDER BY f.updated_at DESC, f.id")
    LiveData<List<TupleSpamFamilyOverview>> liveFamilyOverview(
            String accountUuid, double strongThreshold);

    @Query("SELECT * FROM spam_family WHERE id = :id LIMIT 1")
    EntitySpamFamily getFamily(long id);

    @Insert
    long insertFamily(EntitySpamFamily family);

    @Query("UPDATE spam_family SET" +
            " confirmed_count = :confirmedCount," +
            " updated_at = :updatedAt" +
            " WHERE id = :familyId")
    int setFamilyStats(long familyId, int confirmedCount, long updatedAt);

    @Query("UPDATE spam_family SET name = :name WHERE id = :familyId")
    int setFamilyName(long familyId, String name);

    @Query("UPDATE spam_family SET active = :active, updated_at = :updatedAt WHERE id = :familyId")
    int setFamilyActive(long familyId, boolean active, long updatedAt);

    @Query("DELETE FROM spam_family WHERE id = :familyId")
    int deleteFamily(long familyId);

    @Query("SELECT * FROM spam_family_exemplar" +
            " WHERE family_id = :familyId" +
            " ORDER BY created_at DESC, id DESC")
    List<EntitySpamFamilyExemplar> getExemplars(long familyId);

    @Query("SELECT * FROM spam_family_exemplar" +
            " WHERE account_uuid = :accountUuid AND source_message_id = :messageId" +
            " LIMIT 1")
    EntitySpamFamilyExemplar getExemplar(String accountUuid, long messageId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertExemplar(EntitySpamFamilyExemplar exemplar);

    @Query("DELETE FROM spam_family_exemplar WHERE id = :id")
    int deleteExemplar(long id);

    @Query("DELETE FROM spam_family_exemplar WHERE family_id = :familyId")
    int deleteExemplars(long familyId);

    @Query("DELETE FROM spam_family_exemplar" +
            " WHERE account_uuid = :accountUuid AND source_message_id = :messageId")
    int deleteExemplarByMessage(String accountUuid, long messageId);

    @Query("UPDATE spam_family_exemplar SET" +
            " family_id = :familyId," +
            " created_at = :createdAt" +
            " WHERE id = :exemplarId")
    int moveExemplar(long exemplarId, long familyId, long createdAt);

    @Query("SELECT COUNT(*) FROM spam_family_exemplar WHERE family_id = :familyId")
    int countExemplars(long familyId);

    @Query("SELECT COUNT(*) FROM alias_delivery" +
            " WHERE family_id = :familyId" +
            " AND label = " + EntityAliasDelivery.LABEL_SPAM)
    int countConfirmedMembers(long familyId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertExclusion(EntitySpamFamilyExclusion exclusion);

    @Query("DELETE FROM spam_family_exclusion" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId" +
            " AND family_id = :familyId")
    int deleteExclusion(String accountUuid, long messageId, long familyId);

    @Query("SELECT family_id FROM spam_family_exclusion" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId")
    List<Long> getExcludedFamilyIds(String accountUuid, long messageId);

    @Query("SELECT COUNT(*) FROM spam_family_exclusion" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId" +
            " AND family_id = :familyId")
    int countExclusion(String accountUuid, long messageId, long familyId);

    @Query("DELETE FROM spam_family_exclusion WHERE family_id = :familyId")
    int deleteExclusionsForFamily(long familyId);

    @Query("UPDATE alias_delivery SET" +
            " predicted_family_id = :familyId," +
            " family_score = :score," +
            " family_score_raw = :raw," +
            " family_text = :text," +
            " family_structure = :structure," +
            " family_links = :links," +
            " family_sender = :sender," +
            " family_assessed_at = :assessedAt" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId")
    int setFamilyMatch(String accountUuid, long messageId, Long familyId,
                       double score, double raw, double text, double structure,
                       double links, double sender, long assessedAt);

    @Query("UPDATE alias_delivery SET" +
            " predicted_family_id = NULL," +
            " family_score = NULL," +
            " family_score_raw = NULL," +
            " family_text = NULL," +
            " family_structure = NULL," +
            " family_links = NULL," +
            " family_sender = NULL," +
            " family_assessed_at = :assessedAt" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId")
    int clearFamilyMatch(String accountUuid, long messageId, long assessedAt);

    @Query("UPDATE alias_delivery SET" +
            " predicted_family_id = NULL," +
            " family_score = NULL," +
            " family_score_raw = NULL," +
            " family_text = NULL," +
            " family_structure = NULL," +
            " family_links = NULL," +
            " family_sender = NULL," +
            " family_assessed_at = :assessedAt" +
            " WHERE predicted_family_id = :familyId")
    int clearPredictionsForFamily(long familyId, long assessedAt);

    @Query("SELECT * FROM alias_delivery" +
            " WHERE account_uuid = :accountUuid" +
            " AND predicted_family_id IS NOT NULL" +
            " ORDER BY family_score DESC, received DESC")
    LiveData<List<EntityAliasDelivery>> liveFamilyMatches(String accountUuid);

    @Query("SELECT * FROM alias_delivery" +
            " WHERE account_uuid = :accountUuid" +
            " AND (predicted_family_id = :familyId" +
            "   OR (label = " + EntityAliasDelivery.LABEL_SPAM + " AND family_id = :familyId))" +
            " ORDER BY CASE WHEN label = " + EntityAliasDelivery.LABEL_SPAM +
            "   AND family_id = :familyId THEN 0 ELSE 1 END," +
            " family_score DESC, received DESC" +
            " LIMIT :limit")
    List<EntityAliasDelivery> getFamilyCandidates(
            String accountUuid, long familyId, int limit);

    @Query("SELECT * FROM alias_delivery" +
            " WHERE account_uuid = :accountUuid" +
            " AND message_id < :beforeExclusive" +
            " ORDER BY message_id DESC" +
            " LIMIT :limit")
    List<EntityAliasDelivery> getRescorePage(String accountUuid, long beforeExclusive, int limit);

    @Query("SELECT * FROM spam_rescore_task" +
            " WHERE account_uuid = :accountUuid AND family_id = :familyId" +
            " LIMIT 1")
    EntitySpamRescoreTask getRescoreTask(String accountUuid, long familyId);

    @Query("SELECT * FROM spam_rescore_task" +
            " ORDER BY requested_at, account_uuid, family_id" +
            " LIMIT 1")
    EntitySpamRescoreTask getNextRescoreTask();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putRescoreTask(EntitySpamRescoreTask task);

    @Query("UPDATE spam_rescore_task SET" +
            " before_message_id = :beforeMessageId," +
            " updated_at = :updatedAt," +
            " processed = :processed," +
            " matches = :matches" +
            " WHERE account_uuid = :accountUuid AND family_id = :familyId" +
            " AND requested_at = :generation")
    int checkpointRescoreTask(String accountUuid, long familyId, long generation,
                              long beforeMessageId, long updatedAt,
                              int processed, int matches);

    @Query("DELETE FROM spam_rescore_task" +
            " WHERE account_uuid = :accountUuid AND family_id = :familyId" +
            " AND requested_at = :generation")
    int deleteRescoreTask(String accountUuid, long familyId, long generation);

    @Query("DELETE FROM spam_rescore_task WHERE family_id = :familyId")
    int deleteRescoreTasksForFamily(long familyId);
}

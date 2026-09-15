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

/** Canonical message-level Spam Control state, independent of alias metadata. */
@Dao
public interface DaoSpamMessage {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(EntitySpamMessage message);

    @Query("SELECT * FROM spam_message" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId LIMIT 1")
    EntitySpamMessage get(String accountUuid, long messageId);

    @Query("UPDATE spam_message SET" +
            " received = :received," +
            " folder_type = :folderType," +
            " delivered_to = :deliveredTo" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId")
    int updateObservation(String accountUuid, long messageId, long received,
                          String folderType, String deliveredTo);

    @Query("UPDATE spam_message SET label = :label, family_id = :familyId" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId")
    int setLabel(String accountUuid, long messageId, int label, Long familyId);

    @Query("UPDATE spam_message SET" +
            " predicted_family_id = :familyId," +
            " family_score = :score," +
            " family_assessed_at = :assessedAt" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId")
    int setPrediction(String accountUuid, long messageId, Long familyId,
                      Double score, Long assessedAt);

    @Query("UPDATE spam_message SET" +
            " predicted_family_id = NULL," +
            " family_score = NULL," +
            " family_assessed_at = :assessedAt" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId")
    int clearPrediction(String accountUuid, long messageId, long assessedAt);

    @Query("UPDATE spam_message SET" +
            " predicted_family_id = NULL," +
            " family_score = NULL," +
            " family_assessed_at = :assessedAt" +
            " WHERE predicted_family_id = :familyId")
    int clearPredictionsForFamily(long familyId, long assessedAt);

    @Query("SELECT * FROM spam_message" +
            " WHERE account_uuid = :accountUuid" +
            " AND (:includeReviewed OR label = " + EntitySpamMessage.LABEL_UNKNOWN + ")" +
            " AND (folder_type = '" + EntityFolder.JUNK + "'" +
            "   OR predicted_family_id IS NOT NULL)" +
            " ORDER BY" +
            " CASE WHEN label = " + EntitySpamMessage.LABEL_UNKNOWN + " THEN 0 ELSE 1 END," +
            " CASE WHEN folder_type = '" + EntityFolder.JUNK + "' THEN 0 ELSE 1 END," +
            " family_score DESC, received DESC" +
            " LIMIT :limit")
    List<EntitySpamMessage> getReviewQueue(String accountUuid, boolean includeReviewed, int limit);

    @Query("SELECT COUNT(*) FROM spam_message" +
            " WHERE account_uuid = :accountUuid" +
            " AND (:includeReviewed OR label = " + EntitySpamMessage.LABEL_UNKNOWN + ")" +
            " AND (folder_type = '" + EntityFolder.JUNK + "'" +
            "   OR predicted_family_id IS NOT NULL)")
    int countReviewQueue(String accountUuid, boolean includeReviewed);

    @Query("SELECT * FROM spam_message" +
            " WHERE account_uuid = :accountUuid" +
            " AND label = " + EntitySpamMessage.LABEL_UNKNOWN +
            " AND message_id > :afterMessageId" +
            " ORDER BY message_id" +
            " LIMIT :limit")
    List<EntitySpamMessage> getUnknownAfter(String accountUuid, long afterMessageId, int limit);

    @Query("SELECT * FROM spam_message" +
            " WHERE account_uuid = :accountUuid" +
            " AND (predicted_family_id = :familyId" +
            "   OR (label = " + EntitySpamMessage.LABEL_SPAM + " AND family_id = :familyId))" +
            " ORDER BY" +
            " CASE WHEN label = " + EntitySpamMessage.LABEL_HAM + " THEN 0" +
            "      WHEN label = " + EntitySpamMessage.LABEL_UNKNOWN + " THEN 1 ELSE 2 END," +
            " family_score DESC, received DESC" +
            " LIMIT :limit")
    List<EntitySpamMessage> getFamilyCandidates(String accountUuid, long familyId, int limit);

    @Query("SELECT * FROM spam_message" +
            " WHERE account_uuid = :accountUuid" +
            " AND message_id < :beforeExclusive" +
            " ORDER BY message_id DESC" +
            " LIMIT :limit")
    List<EntitySpamMessage> getRescorePage(String accountUuid, long beforeExclusive, int limit);

    @Query("SELECT * FROM spam_message WHERE account_uuid = :accountUuid ORDER BY message_id")
    List<EntitySpamMessage> getAll(String accountUuid);

    @Query("SELECT * FROM spam_message ORDER BY account_uuid, message_id")
    List<EntitySpamMessage> getAll();

    @Query("SELECT COUNT(*) FROM spam_message WHERE account_uuid = :accountUuid")
    int count(String accountUuid);

    @Query("UPDATE spam_message SET" +
            " label = " + EntitySpamMessage.LABEL_UNKNOWN + "," +
            " family_id = NULL," +
            " predicted_family_id = NULL," +
            " family_score = NULL," +
            " family_assessed_at = NULL" +
            " WHERE account_uuid = :accountUuid")
    int resetLearning(String accountUuid);

    @Query("UPDATE spam_message SET" +
            " label = " + EntitySpamMessage.LABEL_UNKNOWN + "," +
            " family_id = NULL," +
            " predicted_family_id = NULL," +
            " family_score = NULL," +
            " family_assessed_at = NULL")
    int resetAllLearning();

    @Query("DELETE FROM spam_message WHERE account_uuid = :accountUuid")
    int deleteAccount(String accountUuid);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putAll(List<EntitySpamMessage> rows);
}

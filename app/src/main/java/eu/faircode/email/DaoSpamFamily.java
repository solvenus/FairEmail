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

    @Query("DELETE FROM spam_family_exemplar" +
            " WHERE account_uuid = :accountUuid AND source_message_id = :messageId")
    int deleteExemplarByMessage(String accountUuid, long messageId);

    @Query("SELECT COUNT(*) FROM spam_family_exemplar WHERE family_id = :familyId")
    int countExemplars(long familyId);
}

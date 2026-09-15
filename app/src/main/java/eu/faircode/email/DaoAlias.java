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
            " spam_hits = spam_hits + 1," +
            " last_spam = CASE WHEN last_spam IS NULL OR :received > last_spam THEN :received ELSE last_spam END" +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int observeSpam(String accountUuid, String address, long received);

    @Query("UPDATE alias SET" +
            " ham_hits = ham_hits + 1," +
            " last_ham = CASE WHEN last_ham IS NULL OR :received > last_ham THEN :received ELSE last_ham END" +
            " WHERE account_uuid = :accountUuid AND address = :address")
    int observeHam(String accountUuid, String address, long received);

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

    @Query("DELETE FROM alias WHERE account_uuid = :accountUuid")
    int deleteAliases(String accountUuid);
}

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
import androidx.room.Query;

@Dao
public interface DaoSpamActionHistory {
    @Insert
    long insert(EntitySpamActionHistory action);

    @Query("SELECT * FROM spam_action_history" +
            " WHERE undone_at IS NULL" +
            " ORDER BY id DESC LIMIT 1")
    EntitySpamActionHistory getLatestUndoable();

    @Query("SELECT * FROM spam_action_history" +
            " WHERE account_uuid = :accountUuid AND undone_at IS NULL" +
            " ORDER BY id DESC LIMIT 1")
    EntitySpamActionHistory getLatestUndoable(String accountUuid);

    @Query("SELECT * FROM spam_action_history WHERE id = :id LIMIT 1")
    EntitySpamActionHistory get(long id);

    @Query("UPDATE spam_action_history SET undone_at = :undoneAt" +
            " WHERE id = :id AND undone_at IS NULL")
    int markUndone(long id, long undoneAt);

    @Query("DELETE FROM spam_action_history WHERE id = :id")
    int delete(long id);

    @Query("DELETE FROM spam_action_history WHERE account_uuid = :accountUuid")
    int deleteForAccount(String accountUuid);
}

package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

/** Restart-safe cursor for retroactively rescoring local mail against one family. */
@Entity(
        tableName = EntitySpamRescoreTask.TABLE_NAME,
        primaryKeys = {"account_uuid", "family_id"},
        indices = {
                @Index(value = {"requested_at"}),
                @Index(value = {"updated_at"})
        }
)
public class EntitySpamRescoreTask {
    static final String TABLE_NAME = "spam_rescore_task";

    @NonNull
    public String account_uuid;

    public long family_id;

    /** Exclusive descending message-id cursor. */
    public long before_message_id = Long.MAX_VALUE;

    /** Generation token. Re-enqueueing the same family replaces the old generation. */
    public long requested_at;

    public long updated_at;
    public int processed = 0;
    public int matches = 0;
}

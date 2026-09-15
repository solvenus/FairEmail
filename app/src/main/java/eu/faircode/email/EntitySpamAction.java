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
import androidx.room.PrimaryKey;

/**
 * Durable audit/undo record for one human Spam Control action.
 *
 * The snapshot contains intelligence state only. It never contains message
 * bodies or raw EML data.
 */
@Entity(
        tableName = EntitySpamAction.TABLE_NAME,
        indices = {
                @Index(value = {"created_at"}),
                @Index(value = {"state"}),
                @Index(value = {"account_uuid"})
        }
)
public class EntitySpamAction {
    static final String TABLE_NAME = "spam_action";

    public static final int STATE_PENDING = 0;
    public static final int STATE_APPLIED = 1;
    public static final int STATE_UNDONE = 2;

    @PrimaryKey(autoGenerate = true)
    public Long id;

    @NonNull
    public String kind;

    @NonNull
    public String summary;

    public String account_uuid;
    public Long message_id;

    public long created_at;
    public int state = STATE_PENDING;
    public Long undone_at;

    /** GZIP-compressed JSON snapshot of reversible intelligence state. */
    @NonNull
    public byte[] snapshot;
}

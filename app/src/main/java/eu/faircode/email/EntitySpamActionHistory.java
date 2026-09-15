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

/** Persistent semantic undo record for explicit human actions in Spam Control. */
@Entity(
        tableName = EntitySpamActionHistory.TABLE_NAME,
        indices = {
                @Index(value = {"account_uuid"}),
                @Index(value = {"account_uuid", "created_at"}),
                @Index(value = {"undone_at"})
        }
)
public class EntitySpamActionHistory {
    static final String TABLE_NAME = "spam_action_history";

    @PrimaryKey(autoGenerate = true)
    public Long id;

    @NonNull
    public String account_uuid;

    public Long message_id;
    public Long context_family_id;

    /** Stable machine action id, for example SAME_SPAM or NOT_SPAM. */
    @NonNull
    public String action;

    /** Human description shown by the undo UI. */
    @NonNull
    public String description;

    /** JSON snapshot of the semantic state before the action. */
    @NonNull
    public String before_json;

    @NonNull
    public Long created_at;

    /** Non-null after this exact action has been undone. */
    public Long undone_at;
}

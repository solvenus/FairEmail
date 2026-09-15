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
 * Durable hand-off between FairEmail's operation queue and spam learning.
 *
 * Operation ids are monotonically assigned by FairEmail. Persisting the
 * candidate before advancing the high-water mark closes the process-death
 * window that an in-memory delayed evaluation would otherwise have.
 */
@Entity(
        tableName = EntitySpamIntent.TABLE_NAME,
        indices = {
                @Index(value = {"captured_at"}),
                @Index(value = {"message_id"})
        }
)
public class EntitySpamIntent {
    static final String TABLE_NAME = "spam_intent";

    @PrimaryKey
    public long operation_id;

    public long message_id;

    /** Folder semantics captured while the FairEmail operation still exists. */
    public String source_type;
    public String target_type;

    @NonNull
    public Long captured_at;

    @NonNull
    public Integer attempts = 0;

    public Long last_attempt_at;
}

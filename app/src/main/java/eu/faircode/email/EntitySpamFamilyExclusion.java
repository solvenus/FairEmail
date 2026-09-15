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

/** Explicit evidence that one message does not belong to one spam family. */
@Entity(
        tableName = EntitySpamFamilyExclusion.TABLE_NAME,
        primaryKeys = {"account_uuid", "message_id", "family_id"},
        indices = {
                @Index(value = {"family_id"}),
                @Index(value = {"message_id"}),
                @Index(value = {"created_at"})
        }
)
public class EntitySpamFamilyExclusion {
    static final String TABLE_NAME = "spam_family_exclusion";

    @NonNull
    public String account_uuid;

    public long message_id;
    public long family_id;
    public long created_at;

    /** Optional stable provenance/reason code, e.g. family_lab. */
    public String reason;
}

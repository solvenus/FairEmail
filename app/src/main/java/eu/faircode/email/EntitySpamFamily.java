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

/** Persistent identity for one learned spam campaign/family. */
@Entity(
        tableName = EntitySpamFamily.TABLE_NAME,
        indices = {
                @Index(value = {"account_uuid"}),
                @Index(value = {"account_uuid", "active"}),
                @Index(value = {"updated_at"})
        }
)
public class EntitySpamFamily {
    static final String TABLE_NAME = "spam_family";

    @PrimaryKey(autoGenerate = true)
    public Long id;

    @NonNull
    public String account_uuid;

    /** Optional user-facing name; null means generated Family #id in UI. */
    public String name;

    @NonNull
    public Boolean active = true;

    @NonNull
    public Integer confirmed_count = 0;

    @NonNull
    public Long created_at;

    @NonNull
    public Long updated_at;
}

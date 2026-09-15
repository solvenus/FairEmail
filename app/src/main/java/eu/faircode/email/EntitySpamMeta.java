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
import androidx.room.PrimaryKey;

/** Small durable checkpoints owned by SpamIntelligenceDB. */
@Entity(tableName = EntitySpamMeta.TABLE_NAME)
public class EntitySpamMeta {
    static final String TABLE_NAME = "spam_meta";

    @PrimaryKey
    @NonNull
    public String key;

    public Long long_value;
    public String text_value;
}

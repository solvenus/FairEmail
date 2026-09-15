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
 * One confirmed spam example represented only by hashed fingerprint features.
 * Raw message text/HTML is never stored here.
 */
@Entity(
        tableName = EntitySpamFamilyExemplar.TABLE_NAME,
        indices = {
                @Index(value = {"family_id"}),
                @Index(value = {"account_uuid", "source_message_id"}, unique = true),
                @Index(value = {"created_at"})
        }
)
public class EntitySpamFamilyExemplar {
    static final String TABLE_NAME = "spam_family_exemplar";

    @PrimaryKey(autoGenerate = true)
    public Long id;

    public long family_id;

    @NonNull
    public String account_uuid;

    /** FairEmail message id used only for reversible learning/audit. */
    public long source_message_id;

    /** SpamFamilyFingerprint.toBytes(); hashes only. */
    @NonNull
    public byte[] fingerprint;

    @NonNull
    public Long created_at;
}

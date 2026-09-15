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

/**
 * Canonical per-message Spam Control index.
 *
 * A message belongs here whether or not FairEmail retained Envelope-To.
 * Alias delivery state is optional enrichment, never the admission ticket to
 * Spam Control review/learning.
 */
@Entity(
        tableName = EntitySpamMessage.TABLE_NAME,
        primaryKeys = {"account_uuid", "message_id"},
        indices = {
                @Index(value = {"account_uuid", "folder_type"}),
                @Index(value = {"account_uuid", "label"}),
                @Index(value = {"account_uuid", "family_id"}),
                @Index(value = {"account_uuid", "predicted_family_id"}),
                @Index(value = {"received"})
        }
)
public class EntitySpamMessage {
    static final String TABLE_NAME = "spam_message";

    public static final int LABEL_UNKNOWN = 0;
    public static final int LABEL_HAM = 1;
    public static final int LABEL_SPAM = 2;

    @NonNull
    public String account_uuid;
    public long message_id;
    public long received;
    public String folder_type;
    public String delivered_to;

    /** Human/classifier truth, independent of alias availability. */
    public int label = LABEL_UNKNOWN;
    public Long family_id;

    /** Derived exact-family prediction. */
    public Long predicted_family_id;
    public Double family_score;
    public Long family_assessed_at;
}

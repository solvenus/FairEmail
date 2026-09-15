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
 * Persistent inventory entry for an SMTP delivery alias observed by FairEmail.
 *
 * This entity belongs to the isolated SpamIntelligenceDB, not FairEmail's main
 * mail database. Account UUID is therefore the stable logical owner key and no
 * cross-database foreign key is used.
 */
@Entity(
        tableName = EntityAlias.TABLE_NAME,
        indices = {
                @Index(value = {"account_uuid"}),
                @Index(value = {"account_uuid", "address"}, unique = true),
                @Index(value = {"last_seen"}),
                @Index(value = {"spam_hits"})
        }
)
public class EntityAlias {
    static final String TABLE_NAME = "alias";

    public static final int STATE_ACTIVE = 0;
    public static final int STATE_REPLACED = 1;
    public static final int STATE_DISABLED = 2;
    public static final int STATE_IGNORED = 3;

    @PrimaryKey(autoGenerate = true)
    public Long id;

    /** Stable FairEmail account UUID. */
    @NonNull
    public String account_uuid;

    /** Canonical lowercase envelope recipient, for example sd_service@example.org. */
    @NonNull
    public String address;

    /** Optional human label. Can initially be inferred from sd_<service>@... conventions. */
    public String service;

    @NonNull
    public Integer state = STATE_ACTIVE;

    @NonNull
    public Long first_seen;

    @NonNull
    public Long last_seen;

    /** Number of delivery observations recorded for this alias. */
    @NonNull
    public Integer messages = 0;

    /** Explicit/learned spam observations. This is lifetime evidence, not current-folder count. */
    @NonNull
    public Integer spam_hits = 0;

    /** Explicit/learned non-spam observations used as negative evidence. */
    @NonNull
    public Integer ham_hits = 0;

    public Long last_spam;
    public Long last_ham;

    /** Flexible JSON counters keyed by FairEmail folder type. */
    @NonNull
    public String folder_counts = "{}";

    /** Flexible JSON counters keyed by spam-family id. */
    @NonNull
    public String family_counts = "{}";

    /** Optional free-form user note for later alias operations. */
    public String note;

    /** Optional replacement alias when this address has been rotated. */
    public String replaced_by;
}

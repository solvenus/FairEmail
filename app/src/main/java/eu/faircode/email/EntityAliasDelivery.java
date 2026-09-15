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
 * Idempotence and history ledger for alias observations.
 *
 * One main FairEmail message can contribute at most one delivery observation.
 * The row survives deletion from FairEmail's mail database, which preserves
 * alias history without retaining message bodies.
 */
@Entity(
        tableName = EntityAliasDelivery.TABLE_NAME,
        primaryKeys = {"account_uuid", "message_id"},
        indices = {
                @Index(value = {"account_uuid", "address"}),
                @Index(value = {"received"}),
                @Index(value = {"label"}),
                @Index(value = {"family_id"})
        }
)
public class EntityAliasDelivery {
    static final String TABLE_NAME = "alias_delivery";

    public static final int LABEL_UNKNOWN = 0;
    public static final int LABEL_HAM = 1;
    public static final int LABEL_SPAM = 2;

    @NonNull
    public String account_uuid;

    public long message_id;

    @NonNull
    public String address;

    public long received;

    /** FairEmail folder type at the most recent observation, if known. */
    public String folder_type;

    /** User/classifier label; independent of current physical folder. */
    public int label = LABEL_UNKNOWN;

    /** Learned spam-family id, when a family match/label exists. */
    public Long family_id;
}

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
                @Index(value = {"family_id"}),
                @Index(value = {"predicted_family_id"}),
                @Index(value = {"sender_domain"}),
                @Index(value = {"traffic_verdict"})
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

    /** Registrable root domain of the message sender, when available. */
    public String sender_domain;

    /** Presence of a parsed List-Unsubscribe address/header. Evidence, not an allow rule. */
    public boolean has_unsubscribe = false;

    /** User/classifier label; independent of current physical folder. */
    public int label = LABEL_UNKNOWN;

    /** Confirmed learned spam-family id for explicit spam labels. */
    public Long family_id;

    /** Observer-only nearest learned family. This is evidence, not a label. */
    public Long predicted_family_id;
    public Double family_score;
    public Double family_score_raw;
    public Double family_text;
    public Double family_structure;
    public Double family_links;
    public Double family_sender;
    public Long family_assessed_at;

    /** Snapshot of the explainable alias/domain assessment at assessment time. */
    public Double spam_support;
    public Double ham_support;
    public Double traffic_net;
    public String traffic_verdict;
    /** JSON array of stable reason identifiers from AliasTrafficScorer. */
    public String traffic_reasons;
    public Long assessed_at;
}

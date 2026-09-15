package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Isolated local database owned by the custom spam/alias intelligence layer.
 *
 * Keeping this state outside FairEmail's main mail DB makes upstream rebases
 * and experimental schema evolution substantially safer.
 */
@Database(
        version = 1,
        entities = {
                EntityAlias.class,
                EntityAliasDelivery.class,
                EntitySpamMeta.class
        },
        exportSchema = true
)
public abstract class SpamIntelligenceDB extends RoomDatabase {
    static final String DB_NAME = "spam-intelligence";

    private static volatile SpamIntelligenceDB instance;

    public abstract DaoAlias alias();

    public static SpamIntelligenceDB getInstance(Context context) {
        SpamIntelligenceDB current = instance;
        if (current != null)
            return current;

        synchronized (SpamIntelligenceDB.class) {
            current = instance;
            if (current == null) {
                current = Room.databaseBuilder(
                                context.getApplicationContext(),
                                SpamIntelligenceDB.class,
                                DB_NAME)
                        .build();
                instance = current;
            }
            return current;
        }
    }
}

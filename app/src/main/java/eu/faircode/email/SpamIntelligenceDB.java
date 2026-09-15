package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * Isolated local database owned by the custom spam/alias intelligence layer.
 *
 * Keeping this state outside FairEmail's main mail DB makes upstream rebases
 * and experimental schema evolution substantially safer.
 */
@Database(
        version = 4,
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

    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE alias ADD COLUMN service_domain TEXT");
            db.execSQL("ALTER TABLE alias ADD COLUMN observed_domains TEXT NOT NULL DEFAULT '{}'");
            db.execSQL("ALTER TABLE alias ADD COLUMN trusted_domains TEXT NOT NULL DEFAULT '[]'");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_alias_service_domain ON alias(service_domain)");

            db.execSQL("ALTER TABLE alias_delivery ADD COLUMN sender_domain TEXT");
            db.execSQL("ALTER TABLE alias_delivery ADD COLUMN has_unsubscribe INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_alias_delivery_sender_domain ON alias_delivery(sender_domain)");
        }
    };

    private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE alias_delivery ADD COLUMN spam_support REAL");
            db.execSQL("ALTER TABLE alias_delivery ADD COLUMN ham_support REAL");
            db.execSQL("ALTER TABLE alias_delivery ADD COLUMN traffic_net REAL");
            db.execSQL("ALTER TABLE alias_delivery ADD COLUMN traffic_verdict TEXT");
            db.execSQL("ALTER TABLE alias_delivery ADD COLUMN traffic_reasons TEXT");
            db.execSQL("ALTER TABLE alias_delivery ADD COLUMN assessed_at INTEGER");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_alias_delivery_traffic_verdict ON alias_delivery(traffic_verdict)");
        }
    };

    private static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE alias ADD COLUMN smtp_reject_state INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE alias ADD COLUMN smtp_reject_provider TEXT");
            db.execSQL("ALTER TABLE alias ADD COLUMN smtp_reject_reason TEXT");
            db.execSQL("ALTER TABLE alias ADD COLUMN smtp_reject_requested_at INTEGER");
            db.execSQL("ALTER TABLE alias ADD COLUMN smtp_reject_verified_at INTEGER");
            db.execSQL("ALTER TABLE alias ADD COLUMN smtp_reject_error TEXT");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_alias_smtp_reject_state ON alias(smtp_reject_state)");
        }
    };

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
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                        .build();
                instance = current;
            }
            return current;
        }
    }
}

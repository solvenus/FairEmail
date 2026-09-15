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
        version = 10,
        entities = {
                EntityAlias.class,
                EntityAliasDelivery.class,
                EntitySpamMeta.class,
                EntitySpamIntent.class,
                EntitySpamFamily.class,
                EntitySpamFamilyExemplar.class,
                EntitySpamRescoreTask.class,
                EntitySpamFamilyExclusion.class,
                EntitySpamActionHistory.class
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
            db.execSQL("ALTER TABLE alias ADD COLUMN smtp_route_snapshot TEXT");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_alias_smtp_reject_state ON alias(smtp_reject_state)");
        }
    };

    private static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS spam_intent (" +
                    "operation_id INTEGER NOT NULL," +
                    "message_id INTEGER NOT NULL," +
                    "source_type TEXT," +
                    "target_type TEXT," +
                    "captured_at INTEGER NOT NULL," +
                    "attempts INTEGER NOT NULL," +
                    "last_attempt_at INTEGER," +
                    "PRIMARY KEY(operation_id))");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_intent_captured_at ON spam_intent(captured_at)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_intent_message_id ON spam_intent(message_id)");
        }
    };

    private static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS spam_family (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "account_uuid TEXT NOT NULL," +
                    "name TEXT," +
                    "active INTEGER NOT NULL," +
                    "confirmed_count INTEGER NOT NULL," +
                    "created_at INTEGER NOT NULL," +
                    "updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_family_account_uuid ON spam_family(account_uuid)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_family_account_uuid_active ON spam_family(account_uuid, active)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_family_updated_at ON spam_family(updated_at)");

            db.execSQL("CREATE TABLE IF NOT EXISTS spam_family_exemplar (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "family_id INTEGER NOT NULL," +
                    "account_uuid TEXT NOT NULL," +
                    "source_message_id INTEGER NOT NULL," +
                    "fingerprint BLOB NOT NULL," +
                    "created_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_family_exemplar_family_id ON spam_family_exemplar(family_id)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_spam_family_exemplar_account_uuid_source_message_id" +
                    " ON spam_family_exemplar(account_uuid, source_message_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_family_exemplar_created_at ON spam_family_exemplar(created_at)");
        }
    };

    private static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE alias_delivery ADD COLUMN predicted_family_id INTEGER");
            db.execSQL("ALTER TABLE alias_delivery ADD COLUMN family_score REAL");
            db.execSQL("ALTER TABLE alias_delivery ADD COLUMN family_score_raw REAL");
            db.execSQL("ALTER TABLE alias_delivery ADD COLUMN family_text REAL");
            db.execSQL("ALTER TABLE alias_delivery ADD COLUMN family_structure REAL");
            db.execSQL("ALTER TABLE alias_delivery ADD COLUMN family_links REAL");
            db.execSQL("ALTER TABLE alias_delivery ADD COLUMN family_sender REAL");
            db.execSQL("ALTER TABLE alias_delivery ADD COLUMN family_assessed_at INTEGER");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_alias_delivery_predicted_family_id" +
                    " ON alias_delivery(predicted_family_id)");
        }
    };

    private static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS spam_rescore_task (" +
                    "account_uuid TEXT NOT NULL," +
                    "family_id INTEGER NOT NULL," +
                    "before_message_id INTEGER NOT NULL," +
                    "requested_at INTEGER NOT NULL," +
                    "updated_at INTEGER NOT NULL," +
                    "processed INTEGER NOT NULL," +
                    "matches INTEGER NOT NULL," +
                    "PRIMARY KEY(account_uuid, family_id))");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_rescore_task_requested_at" +
                    " ON spam_rescore_task(requested_at)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_rescore_task_updated_at" +
                    " ON spam_rescore_task(updated_at)");
        }
    };

    private static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS spam_family_exclusion (" +
                    "account_uuid TEXT NOT NULL," +
                    "message_id INTEGER NOT NULL," +
                    "family_id INTEGER NOT NULL," +
                    "created_at INTEGER NOT NULL," +
                    "reason TEXT," +
                    "PRIMARY KEY(account_uuid, message_id, family_id))");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_family_exclusion_family_id" +
                    " ON spam_family_exclusion(family_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_family_exclusion_message_id" +
                    " ON spam_family_exclusion(message_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_family_exclusion_created_at" +
                    " ON spam_family_exclusion(created_at)");
        }
    };

    private static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS spam_action_history (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "account_uuid TEXT NOT NULL," +
                    "message_id INTEGER," +
                    "context_family_id INTEGER," +
                    "action TEXT NOT NULL," +
                    "description TEXT NOT NULL," +
                    "before_json TEXT NOT NULL," +
                    "created_at INTEGER NOT NULL," +
                    "undone_at INTEGER)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_action_history_account_uuid" +
                    " ON spam_action_history(account_uuid)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_action_history_account_uuid_created_at" +
                    " ON spam_action_history(account_uuid, created_at)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_action_history_undone_at" +
                    " ON spam_action_history(undone_at)");
        }
    };

    public abstract DaoAlias alias();
    public abstract DaoSpamFamily family();
    public abstract DaoSpamActionHistory actions();
    public abstract DaoSpamSnapshot snapshot();

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
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                                MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                                MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                        .build();
                instance = current;
            }
            return current;
        }
    }
}

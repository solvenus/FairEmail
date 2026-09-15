from pathlib import Path

P = Path('app/src/main/java/eu/faircode/email/SpamIntelligenceDB.java')
text = P.read_text()

def once(old, new, label):
    global text
    c = text.count(old)
    if c != 1:
        raise SystemExit(f'{label}: expected 1 match, got {c}')
    text = text.replace(old, new, 1)

once('''        version = 10,\n        entities = {\n                EntityAlias.class,\n                EntityAliasDelivery.class,\n''','''        version = 11,\n        entities = {\n                EntityAlias.class,\n                EntityAliasDelivery.class,\n                EntitySpamMessage.class,\n''','version-entity')

marker = '''    public abstract DaoAlias alias();\n'''
migration = '''    private static final Migration MIGRATION_10_11 = new Migration(10, 11) {\n        @Override\n        public void migrate(@NonNull SupportSQLiteDatabase db) {\n            db.execSQL("CREATE TABLE IF NOT EXISTS spam_message (" +\n                    "account_uuid TEXT NOT NULL," +\n                    "message_id INTEGER NOT NULL," +\n                    "received INTEGER NOT NULL," +\n                    "folder_type TEXT," +\n                    "delivered_to TEXT," +\n                    "label INTEGER NOT NULL," +\n                    "family_id INTEGER," +\n                    "predicted_family_id INTEGER," +\n                    "family_score REAL," +\n                    "family_assessed_at INTEGER," +\n                    "PRIMARY KEY(account_uuid, message_id))");\n            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_message_account_uuid_folder_type" +\n                    " ON spam_message(account_uuid, folder_type)");\n            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_message_account_uuid_label" +\n                    " ON spam_message(account_uuid, label)");\n            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_message_account_uuid_family_id" +\n                    " ON spam_message(account_uuid, family_id)");\n            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_message_account_uuid_predicted_family_id" +\n                    " ON spam_message(account_uuid, predicted_family_id)");\n            db.execSQL("CREATE INDEX IF NOT EXISTS index_spam_message_received ON spam_message(received)");\n\n            // Preserve all existing message-level learning while decoupling it from alias availability.\n            db.execSQL("INSERT OR IGNORE INTO spam_message(" +\n                    "account_uuid,message_id,received,folder_type,delivered_to,label,family_id," +\n                    "predicted_family_id,family_score,family_assessed_at) " +\n                    "SELECT account_uuid,message_id,received,folder_type,address,label,family_id," +\n                    "predicted_family_id,family_score,family_assessed_at FROM alias_delivery");\n        }\n    };\n\n'''
if text.count(marker) != 1:
    raise SystemExit('dao-marker mismatch')
text = text.replace(marker, migration + '''    public abstract DaoAlias alias();\n    public abstract DaoSpamMessage message();\n''', 1)

once('''                                MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)\n''','''                                MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)\n''','migration-list')

P.write_text(text)
print('patched SpamIntelligenceDB to v11')

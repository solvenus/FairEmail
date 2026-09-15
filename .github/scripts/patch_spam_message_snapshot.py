from pathlib import Path

ROOT = Path('app/src/main/java/eu/faircode/email')


def once(text, old, new, label):
    c = text.count(old)
    if c != 1:
        raise SystemExit(f'{label}: expected 1 match, got {c}')
    return text.replace(old, new, 1)

# DaoSpamSnapshot ---------------------------------------------------
p = ROOT / 'DaoSpamSnapshot.java'
text = p.read_text()
anchor = '''    @Query("SELECT * FROM alias_delivery ORDER BY account_uuid, message_id")\n    List<EntityAliasDelivery> getAllDeliveries();\n\n'''
insert = anchor + '''    @Query("SELECT * FROM spam_message" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId LIMIT 1")
    EntitySpamMessage getSpamMessage(String accountUuid, long messageId);

    @Query("SELECT * FROM spam_message WHERE account_uuid = :accountUuid ORDER BY message_id")
    List<EntitySpamMessage> getSpamMessages(String accountUuid);

    @Query("SELECT * FROM spam_message ORDER BY account_uuid, message_id")
    List<EntitySpamMessage> getAllSpamMessages();

'''
text = once(text, anchor, insert, 'snapshot-dao-read-message')

anchor = '''    int restoreDeliveryLearning(String accountUuid, long messageId,\n                                int label, Long familyId,\n                                Long predictedFamilyId,\n                                Double familyScore, Double familyScoreRaw,\n                                Double familyText, Double familyStructure,\n                                Double familyLinks, Double familySender,\n                                Long familyAssessedAt,\n                                Double spamSupport, Double hamSupport,\n                                Double trafficNet, String trafficVerdict,\n                                String trafficReasons, Long assessedAt);\n\n'''
insert = anchor + '''    @Query("UPDATE spam_message SET" +
            " label = :label," +
            " family_id = :familyId," +
            " predicted_family_id = :predictedFamilyId," +
            " family_score = :familyScore," +
            " family_assessed_at = :familyAssessedAt" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId")
    int restoreSpamMessageLearning(String accountUuid, long messageId,
                                   int label, Long familyId,
                                   Long predictedFamilyId, Double familyScore,
                                   Long familyAssessedAt);

    @Query("UPDATE spam_message SET" +
            " predicted_family_id = NULL," +
            " family_score = NULL," +
            " family_assessed_at = NULL" +
            " WHERE account_uuid = :accountUuid")
    int clearSpamMessagePredictions(String accountUuid);

    @Query("UPDATE spam_message SET" +
            " label = " + EntitySpamMessage.LABEL_UNKNOWN + "," +
            " family_id = NULL," +
            " predicted_family_id = NULL," +
            " family_score = NULL," +
            " family_assessed_at = NULL" +
            " WHERE account_uuid = :accountUuid")
    int resetAccountSpamMessageLearning(String accountUuid);

    @Query("UPDATE spam_message SET" +
            " label = " + EntitySpamMessage.LABEL_UNKNOWN + "," +
            " family_id = NULL," +
            " predicted_family_id = NULL," +
            " family_score = NULL," +
            " family_assessed_at = NULL")
    int resetAllSpamMessageLearning();

'''
text = once(text, anchor, insert, 'snapshot-dao-restore-message')
p.write_text(text)

# SpamLearningSnapshot ----------------------------------------------
p = ROOT / 'SpamLearningSnapshot.java'
text = p.read_text()

# specific-message capture
old = '''                if (messageId > 0) {\n                    EntityAliasDelivery delivery = dao.getDelivery(account, messageId);\n                    if (delivery != null) {\n                        root.put("delivery", deliveryToJson(delivery));\n                        EntityAlias alias = dao.getAlias(account, delivery.address);\n                        if (alias != null)\n                            root.put("alias", aliasToJson(alias));\n                    }\n                }\n'''
new = '''                if (messageId > 0) {
                    EntitySpamMessage spamMessage = dao.getSpamMessage(account, messageId);
                    if (spamMessage != null)
                        root.put("spam_message", spamMessageToJson(spamMessage));
                    EntityAliasDelivery delivery = dao.getDelivery(account, messageId);
                    if (delivery != null) {
                        root.put("delivery", deliveryToJson(delivery));
                        EntityAlias alias = dao.getAlias(account, delivery.address);
                        if (alias != null)
                            root.put("alias", aliasToJson(alias));
                    }
                }
'''
text = once(text, old, new, 'snapshot-capture-specific')

# full-account capture: add canonical messages before deliveries
anchor = '''                JSONArray deliveries = new JSONArray();\n                List<EntityAliasDelivery> deliveryRows = dao.getDeliveries(account);\n'''
insert = '''                JSONArray spamMessages = new JSONArray();
                List<EntitySpamMessage> spamMessageRows = dao.getSpamMessages(account);
                if (spamMessageRows != null)
                    for (EntitySpamMessage row : spamMessageRows)
                        if (row != null)
                            spamMessages.put(spamMessageToJson(row));
                root.put("spam_messages", spamMessages);

''' + anchor
text = once(text, anchor, insert, 'snapshot-capture-account-all')

# global capture occurrence is same deliveries anchor later; replace second via exact surrounding
anchor = '''                JSONArray deliveries = new JSONArray();\n                List<EntityAliasDelivery> deliveryRows = dao.getAllDeliveries();\n'''
insert = '''                JSONArray spamMessages = new JSONArray();
                List<EntitySpamMessage> spamMessageRows = dao.getAllSpamMessages();
                if (spamMessageRows != null)
                    for (EntitySpamMessage row : spamMessageRows)
                        if (row != null)
                            spamMessages.put(spamMessageToJson(row));
                root.put("spam_messages", spamMessages);

''' + anchor
text = once(text, anchor, insert, 'snapshot-capture-global')

# account restore clears derived canonical prediction cache
text = once(text,
'''                    dao.deleteFamilies(account);\n                    dao.clearPredictions(account);\n''',
'''                    dao.deleteFamilies(account);\n                    dao.clearPredictions(account);\n                    dao.clearSpamMessagePredictions(account);\n''',
'snapshot-restore-clear-message-predictions')

# full account restore learning
old = '''                    if (root.optBoolean("full_account_learning", false)) {\n                        dao.resetAccountDeliveryLearning(account);\n                        dao.resetAccountAliasLearning(account);\n\n                        JSONArray deliveries = root.optJSONArray("deliveries");\n'''
new = '''                    if (root.optBoolean("full_account_learning", false)) {
                        dao.resetAccountSpamMessageLearning(account);
                        dao.resetAccountDeliveryLearning(account);
                        dao.resetAccountAliasLearning(account);

                        JSONArray spamMessages = root.optJSONArray("spam_messages");
                        if (spamMessages != null)
                            for (int i = 0; i < spamMessages.length(); i++)
                                restoreSpamMessage(dao, spamMessages.getJSONObject(i));

                        JSONArray deliveries = root.optJSONArray("deliveries");
'''
text = once(text, old, new, 'snapshot-restore-account-all')

# specific account restore canonical row
old = '''                    } else {\n                        JSONObject delivery = root.optJSONObject("delivery");\n'''
new = '''                    } else {
                        JSONObject spamMessage = root.optJSONObject("spam_message");
                        if (spamMessage != null)
                            restoreSpamMessage(dao, spamMessage);
                        JSONObject delivery = root.optJSONObject("delivery");
'''
text = once(text, old, new, 'snapshot-restore-specific')

# global restore reset canonical learning
text = once(text,
'''                    dao.resetAllDeliveryLearning();\n                    dao.resetAllAliasLearning();\n''',
'''                    dao.resetAllSpamMessageLearning();\n                    dao.resetAllDeliveryLearning();\n                    dao.resetAllAliasLearning();\n''',
'snapshot-global-reset-canonical')

# global restore canonical rows before deliveries
old = '''                    JSONArray deliveries = root.getJSONArray("deliveries");\n                    for (int i = 0; i < deliveries.length(); i++) {\n'''
new = '''                    JSONArray spamMessages = root.optJSONArray("spam_messages");
                    if (spamMessages != null)
                        for (int i = 0; i < spamMessages.length(); i++) {
                            JSONObject row = spamMessages.getJSONObject(i);
                            restoreSpamMessage(dao, row);
                            String account = row.optString("account_uuid", null);
                            if (account != null)
                                accounts.add(account);
                        }

                    JSONArray deliveries = root.getJSONArray("deliveries");
                    for (int i = 0; i < deliveries.length(); i++) {
'''
text = once(text, old, new, 'snapshot-global-restore-canonical')

# resetAll canonical learning
old = '''                dao.deleteMetaByPrefix(AliasCompromiseReviewStore.globalMetaPrefix());\n                dao.resetAllDeliveryLearning();\n                dao.resetAllAliasLearning();\n'''
new = '''                dao.deleteMetaByPrefix(AliasCompromiseReviewStore.globalMetaPrefix());
                dao.resetAllSpamMessageLearning();
                dao.resetAllDeliveryLearning();
                dao.resetAllAliasLearning();
'''
text = once(text, old, new, 'snapshot-reset-all-canonical')

# serialization helpers before metaToJson
anchor = '''    private static JSONArray metaToJson(List<EntitySpamMeta> rows) throws Exception {\n'''
helpers = '''    private static JSONObject spamMessageToJson(EntitySpamMessage row) throws Exception {
        JSONObject o = new JSONObject();
        o.put("account_uuid", row.account_uuid);
        o.put("message_id", row.message_id);
        o.put("label", row.label);
        put(o, "family_id", row.family_id);
        put(o, "predicted_family_id", row.predicted_family_id);
        if (row.family_score != null)
            o.put("family_score", row.family_score);
        put(o, "family_assessed_at", row.family_assessed_at);
        return o;
    }

    private static void restoreSpamMessage(DaoSpamSnapshot dao, JSONObject o) throws Exception {
        dao.restoreSpamMessageLearning(
                o.getString("account_uuid"),
                o.getLong("message_id"),
                o.optInt("label", EntitySpamMessage.LABEL_UNKNOWN),
                optLong(o, "family_id"),
                optLong(o, "predicted_family_id"),
                o.has("family_score") && !o.isNull("family_score")
                        ? o.getDouble("family_score") : null,
                optLong(o, "family_assessed_at"));
    }

''' + anchor
text = once(text, anchor, helpers, 'snapshot-serialization-helpers')
p.write_text(text)

print('patched canonical spam_message snapshot/reset')

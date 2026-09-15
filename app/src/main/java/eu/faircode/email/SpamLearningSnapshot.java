package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Lossless semantic snapshots for Spam Control undo/reset.
 *
 * Snapshots contain only learned/derived SpamIntelligenceDB state. They never
 * contain message bodies, raw headers or raw EML data. Passive alias inventory,
 * manually configured domains and SMTP/cPanel state are not reset.
 */
final class SpamLearningSnapshot {
    private static final int VERSION = 1;
    private static final String SCOPE_ACCOUNT = "account";
    private static final String SCOPE_ALL = "all";

    private SpamLearningSnapshot() {
    }

    static String captureAccount(Context context,
                                 String accountUuid,
                                 long messageId) throws Exception {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty())
            throw new IllegalArgumentException("accountUuid");

        Context app = context.getApplicationContext();
        SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);
        final String account = accountUuid.trim();
        return db.runInTransaction(new Callable<String>() {
            @Override
            public String call() throws Exception {
                DaoSpamSnapshot dao = db.snapshot();
                JSONObject root = new JSONObject();
                root.put("version", VERSION);
                root.put("scope", SCOPE_ACCOUNT);
                root.put("account_uuid", account);
                root.put("message_id", messageId);
                root.put("families", familiesToJson(dao.getFamilies(account)));
                root.put("exemplars", exemplarsToJson(dao.getExemplars(account)));
                root.put("exclusions", exclusionsToJson(dao.getExclusions(account)));
                root.put("meta", metaToJson(dao.getMetaByPrefix(
                        SpamFamilyIdentity.accountMetaPrefix(account))));

                if (messageId > 0) {
                    EntityAliasDelivery delivery = dao.getDelivery(account, messageId);
                    if (delivery != null) {
                        root.put("delivery", deliveryToJson(delivery));
                        EntityAlias alias = dao.getAlias(account, delivery.address);
                        if (alias != null)
                            root.put("alias", aliasToJson(alias));
                    }
                }
                return encode(root);
            }
        });
    }

    /**
     * Capture every learned row for one account. Used when one human action can
     * legitimately update many exact-identity deliveries/aliases at once.
     */
    static String captureAccountAllLearning(Context context,
                                            String accountUuid) throws Exception {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty())
            throw new IllegalArgumentException("accountUuid");

        Context app = context.getApplicationContext();
        SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);
        final String account = accountUuid.trim();
        return db.runInTransaction(new Callable<String>() {
            @Override
            public String call() throws Exception {
                DaoSpamSnapshot dao = db.snapshot();
                JSONObject root = new JSONObject();
                root.put("version", VERSION);
                root.put("scope", SCOPE_ACCOUNT);
                root.put("account_uuid", account);
                root.put("message_id", 0L);
                root.put("full_account_learning", true);
                root.put("families", familiesToJson(dao.getFamilies(account)));
                root.put("exemplars", exemplarsToJson(dao.getExemplars(account)));
                root.put("exclusions", exclusionsToJson(dao.getExclusions(account)));
                root.put("meta", metaToJson(dao.getMetaByPrefix(
                        SpamFamilyIdentity.accountMetaPrefix(account))));

                JSONArray deliveries = new JSONArray();
                List<EntityAliasDelivery> deliveryRows = dao.getDeliveries(account);
                if (deliveryRows != null)
                    for (EntityAliasDelivery row : deliveryRows)
                        if (row != null)
                            deliveries.put(deliveryToJson(row));
                root.put("deliveries", deliveries);

                JSONArray aliases = new JSONArray();
                List<EntityAlias> aliasRows = dao.getAliases(account);
                if (aliasRows != null)
                    for (EntityAlias row : aliasRows)
                        if (row != null)
                            aliases.put(aliasToJson(row));
                root.put("aliases", aliases);
                return encode(root);
            }
        });
    }

    static String captureAll(Context context) throws Exception {
        if (context == null)
            throw new IllegalArgumentException("context");

        Context app = context.getApplicationContext();
        SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);
        return db.runInTransaction(new Callable<String>() {
            @Override
            public String call() throws Exception {
                DaoSpamSnapshot dao = db.snapshot();
                JSONObject root = new JSONObject();
                root.put("version", VERSION);
                root.put("scope", SCOPE_ALL);
                root.put("families", familiesToJson(dao.getAllFamilies()));
                root.put("exemplars", exemplarsToJson(dao.getAllExemplars()));
                root.put("exclusions", exclusionsToJson(dao.getAllExclusions()));
                root.put("meta", metaToJson(dao.getMetaByPrefix(
                        SpamFamilyIdentity.globalMetaPrefix())));

                JSONArray deliveries = new JSONArray();
                List<EntityAliasDelivery> deliveryRows = dao.getAllDeliveries();
                if (deliveryRows != null)
                    for (EntityAliasDelivery row : deliveryRows)
                        if (row != null)
                            deliveries.put(deliveryToJson(row));
                root.put("deliveries", deliveries);

                JSONArray aliases = new JSONArray();
                List<EntityAlias> aliasRows = dao.getAllAliases();
                if (aliasRows != null)
                    for (EntityAlias row : aliasRows)
                        if (row != null)
                            aliases.put(aliasToJson(row));
                root.put("aliases", aliases);
                return encode(root);
            }
        });
    }

    /** Restore a snapshot and return accounts whose family predictions should be rebuilt. */
    static Set<String> restore(Context context, String encoded) throws Exception {
        if (context == null || encoded == null)
            throw new IllegalArgumentException("snapshot");

        Context app = context.getApplicationContext();
        SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);
        JSONObject root = decode(encoded);
        if (root.optInt("version", -1) != VERSION)
            throw new IllegalStateException("Unsupported spam snapshot version=" +
                    root.optInt("version", -1));

        final String scope = root.getString("scope");
        final Set<String> accounts = new HashSet<>();
        db.runInTransaction(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                DaoSpamSnapshot dao = db.snapshot();
                if (SCOPE_ACCOUNT.equals(scope)) {
                    String account = root.getString("account_uuid");
                    accounts.add(account);

                    dao.deleteRescoreTasks(account);
                    dao.deleteExclusions(account);
                    dao.deleteExemplars(account);
                    dao.deleteFamilies(account);
                    dao.clearPredictions(account);
                    if (root.has("meta"))
                        dao.deleteMetaByPrefix(SpamFamilyIdentity.accountMetaPrefix(account));

                    List<EntitySpamFamily> families = familiesFromJson(root.getJSONArray("families"));
                    List<EntitySpamFamilyExemplar> exemplars = exemplarsFromJson(root.getJSONArray("exemplars"));
                    List<EntitySpamFamilyExclusion> exclusions = exclusionsFromJson(root.getJSONArray("exclusions"));
                    List<EntitySpamMeta> meta = root.has("meta")
                            ? metaFromJson(root.getJSONArray("meta"))
                            : new ArrayList<EntitySpamMeta>();
                    if (!families.isEmpty())
                        dao.putFamilies(families);
                    if (!exemplars.isEmpty())
                        dao.putExemplars(exemplars);
                    if (!exclusions.isEmpty())
                        dao.putExclusions(exclusions);
                    if (!meta.isEmpty())
                        dao.putMeta(meta);

                    if (root.optBoolean("full_account_learning", false)) {
                        dao.resetAccountDeliveryLearning(account);
                        dao.resetAccountAliasLearning(account);

                        JSONArray deliveries = root.optJSONArray("deliveries");
                        if (deliveries != null)
                            for (int i = 0; i < deliveries.length(); i++)
                                restoreDelivery(dao, deliveries.getJSONObject(i));

                        JSONArray aliases = root.optJSONArray("aliases");
                        if (aliases != null)
                            for (int i = 0; i < aliases.length(); i++)
                                restoreAlias(dao, aliases.getJSONObject(i));
                    } else {
                        JSONObject delivery = root.optJSONObject("delivery");
                        if (delivery != null)
                            restoreDelivery(dao, delivery);
                        JSONObject alias = root.optJSONObject("alias");
                        if (alias != null)
                            restoreAlias(dao, alias);
                    }
                } else if (SCOPE_ALL.equals(scope)) {
                    dao.deleteAllSpamIntents();
                    dao.deleteAllRescoreTasks();
                    dao.deleteAllExclusions();
                    dao.deleteAllExemplars();
                    dao.deleteAllFamilies();
                    if (root.has("meta"))
                        dao.deleteMetaByPrefix(SpamFamilyIdentity.globalMetaPrefix());
                    dao.resetAllDeliveryLearning();
                    dao.resetAllAliasLearning();

                    List<EntitySpamFamily> families = familiesFromJson(root.getJSONArray("families"));
                    List<EntitySpamFamilyExemplar> exemplars = exemplarsFromJson(root.getJSONArray("exemplars"));
                    List<EntitySpamFamilyExclusion> exclusions = exclusionsFromJson(root.getJSONArray("exclusions"));
                    List<EntitySpamMeta> meta = root.has("meta")
                            ? metaFromJson(root.getJSONArray("meta"))
                            : new ArrayList<EntitySpamMeta>();
                    if (!families.isEmpty())
                        dao.putFamilies(families);
                    if (!exemplars.isEmpty())
                        dao.putExemplars(exemplars);
                    if (!exclusions.isEmpty())
                        dao.putExclusions(exclusions);
                    if (!meta.isEmpty())
                        dao.putMeta(meta);

                    JSONArray deliveries = root.getJSONArray("deliveries");
                    for (int i = 0; i < deliveries.length(); i++) {
                        JSONObject row = deliveries.getJSONObject(i);
                        restoreDelivery(dao, row);
                        String account = row.optString("account_uuid", null);
                        if (account != null)
                            accounts.add(account);
                    }

                    JSONArray aliases = root.getJSONArray("aliases");
                    for (int i = 0; i < aliases.length(); i++) {
                        JSONObject row = aliases.getJSONObject(i);
                        restoreAlias(dao, row);
                        String account = row.optString("account_uuid", null);
                        if (account != null)
                            accounts.add(account);
                    }

                    for (EntitySpamFamily family : families)
                        if (family != null && family.account_uuid != null)
                            accounts.add(family.account_uuid);
                } else
                    throw new IllegalStateException("Unknown spam snapshot scope=" + scope);
                return true;
            }
        });
        return accounts;
    }

    /** Blank learned spam state globally while preserving mail and alias configuration. */
    static void resetAll(Context context) throws Exception {
        if (context == null)
            throw new IllegalArgumentException("context");
        Context app = context.getApplicationContext();
        SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);
        db.runInTransaction(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                DaoSpamSnapshot dao = db.snapshot();
                dao.deleteAllSpamIntents();
                dao.deleteAllRescoreTasks();
                dao.deleteAllExclusions();
                dao.deleteAllExemplars();
                dao.deleteAllFamilies();
                dao.deleteMetaByPrefix(SpamFamilyIdentity.globalMetaPrefix());
                dao.resetAllDeliveryLearning();
                dao.resetAllAliasLearning();
                return true;
            }
        });
    }

    private static JSONArray metaToJson(List<EntitySpamMeta> rows) throws Exception {
        JSONArray result = new JSONArray();
        if (rows == null)
            return result;
        for (EntitySpamMeta row : rows) {
            if (row == null)
                continue;
            JSONObject o = new JSONObject();
            o.put("key", row.key);
            put(o, "long_value", row.long_value);
            put(o, "text_value", row.text_value);
            result.put(o);
        }
        return result;
    }

    private static List<EntitySpamMeta> metaFromJson(JSONArray rows) throws Exception {
        List<EntitySpamMeta> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.getJSONObject(i);
            EntitySpamMeta row = new EntitySpamMeta();
            row.key = o.getString("key");
            row.long_value = optLong(o, "long_value");
            row.text_value = optString(o, "text_value");
            result.add(row);
        }
        return result;
    }

    private static JSONArray familiesToJson(List<EntitySpamFamily> rows) throws Exception {
        JSONArray result = new JSONArray();
        if (rows == null)
            return result;
        for (EntitySpamFamily row : rows) {
            if (row == null)
                continue;
            JSONObject o = new JSONObject();
            put(o, "id", row.id);
            o.put("account_uuid", row.account_uuid);
            put(o, "name", row.name);
            o.put("active", Boolean.TRUE.equals(row.active));
            o.put("confirmed_count", row.confirmed_count == null ? 0 : row.confirmed_count);
            o.put("created_at", row.created_at == null ? 0L : row.created_at);
            o.put("updated_at", row.updated_at == null ? 0L : row.updated_at);
            result.put(o);
        }
        return result;
    }

    private static List<EntitySpamFamily> familiesFromJson(JSONArray rows) throws Exception {
        List<EntitySpamFamily> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.getJSONObject(i);
            EntitySpamFamily row = new EntitySpamFamily();
            row.id = optLong(o, "id");
            row.account_uuid = o.getString("account_uuid");
            row.name = optString(o, "name");
            row.active = o.getBoolean("active");
            row.confirmed_count = o.getInt("confirmed_count");
            row.created_at = o.getLong("created_at");
            row.updated_at = o.getLong("updated_at");
            result.add(row);
        }
        return result;
    }

    private static JSONArray exemplarsToJson(List<EntitySpamFamilyExemplar> rows) throws Exception {
        JSONArray result = new JSONArray();
        if (rows == null)
            return result;
        for (EntitySpamFamilyExemplar row : rows) {
            if (row == null)
                continue;
            JSONObject o = new JSONObject();
            put(o, "id", row.id);
            o.put("family_id", row.family_id);
            o.put("account_uuid", row.account_uuid);
            o.put("source_message_id", row.source_message_id);
            o.put("fingerprint", Base64.encodeToString(row.fingerprint, Base64.NO_WRAP));
            o.put("created_at", row.created_at == null ? 0L : row.created_at);
            result.put(o);
        }
        return result;
    }

    private static List<EntitySpamFamilyExemplar> exemplarsFromJson(JSONArray rows) throws Exception {
        List<EntitySpamFamilyExemplar> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.getJSONObject(i);
            EntitySpamFamilyExemplar row = new EntitySpamFamilyExemplar();
            row.id = optLong(o, "id");
            row.family_id = o.getLong("family_id");
            row.account_uuid = o.getString("account_uuid");
            row.source_message_id = o.getLong("source_message_id");
            row.fingerprint = Base64.decode(o.getString("fingerprint"), Base64.DEFAULT);
            row.created_at = o.getLong("created_at");
            result.add(row);
        }
        return result;
    }

    private static JSONArray exclusionsToJson(List<EntitySpamFamilyExclusion> rows) throws Exception {
        JSONArray result = new JSONArray();
        if (rows == null)
            return result;
        for (EntitySpamFamilyExclusion row : rows) {
            if (row == null)
                continue;
            JSONObject o = new JSONObject();
            o.put("account_uuid", row.account_uuid);
            o.put("message_id", row.message_id);
            o.put("family_id", row.family_id);
            o.put("created_at", row.created_at);
            put(o, "reason", row.reason);
            result.put(o);
        }
        return result;
    }

    private static List<EntitySpamFamilyExclusion> exclusionsFromJson(JSONArray rows) throws Exception {
        List<EntitySpamFamilyExclusion> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.getJSONObject(i);
            EntitySpamFamilyExclusion row = new EntitySpamFamilyExclusion();
            row.account_uuid = o.getString("account_uuid");
            row.message_id = o.getLong("message_id");
            row.family_id = o.getLong("family_id");
            row.created_at = o.getLong("created_at");
            row.reason = optString(o, "reason");
            result.add(row);
        }
        return result;
    }

    private static JSONObject aliasToJson(EntityAlias row) throws Exception {
        JSONObject o = new JSONObject();
        o.put("account_uuid", row.account_uuid);
        o.put("address", row.address);
        o.put("spam_hits", row.spam_hits == null ? 0 : row.spam_hits);
        o.put("ham_hits", row.ham_hits == null ? 0 : row.ham_hits);
        put(o, "last_spam", row.last_spam);
        put(o, "last_ham", row.last_ham);
        o.put("family_counts", row.family_counts == null ? "{}" : row.family_counts);
        o.put("state", row.state == null ? EntityAlias.STATE_ACTIVE : row.state);
        return o;
    }

    private static void restoreAlias(DaoSpamSnapshot dao, JSONObject o) throws Exception {
        dao.restoreAliasLearning(
                o.getString("account_uuid"),
                o.getString("address"),
                o.getInt("spam_hits"),
                o.getInt("ham_hits"),
                optLong(o, "last_spam"),
                optLong(o, "last_ham"),
                o.getString("family_counts"),
                o.getInt("state"));
    }

    private static JSONObject deliveryToJson(EntityAliasDelivery row) throws Exception {
        JSONObject o = new JSONObject();
        o.put("account_uuid", row.account_uuid);
        o.put("message_id", row.message_id);
        o.put("label", row.label);
        put(o, "family_id", row.family_id);
        put(o, "predicted_family_id", row.predicted_family_id);
        put(o, "family_score", row.family_score);
        put(o, "family_score_raw", row.family_score_raw);
        put(o, "family_text", row.family_text);
        put(o, "family_structure", row.family_structure);
        put(o, "family_links", row.family_links);
        put(o, "family_sender", row.family_sender);
        put(o, "family_assessed_at", row.family_assessed_at);
        put(o, "spam_support", row.spam_support);
        put(o, "ham_support", row.ham_support);
        put(o, "traffic_net", row.traffic_net);
        put(o, "traffic_verdict", row.traffic_verdict);
        put(o, "traffic_reasons", row.traffic_reasons);
        put(o, "assessed_at", row.assessed_at);
        return o;
    }

    private static void restoreDelivery(DaoSpamSnapshot dao, JSONObject o) throws Exception {
        dao.restoreDeliveryLearning(
                o.getString("account_uuid"),
                o.getLong("message_id"),
                o.getInt("label"),
                optLong(o, "family_id"),
                optLong(o, "predicted_family_id"),
                optDouble(o, "family_score"),
                optDouble(o, "family_score_raw"),
                optDouble(o, "family_text"),
                optDouble(o, "family_structure"),
                optDouble(o, "family_links"),
                optDouble(o, "family_sender"),
                optLong(o, "family_assessed_at"),
                optDouble(o, "spam_support"),
                optDouble(o, "ham_support"),
                optDouble(o, "traffic_net"),
                optString(o, "traffic_verdict"),
                optString(o, "traffic_reasons"),
                optLong(o, "assessed_at"));
    }

    private static String encode(JSONObject root) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(root.toString().getBytes(StandardCharsets.UTF_8));
        }
        JSONObject envelope = new JSONObject();
        envelope.put("encoding", "gzip-base64");
        envelope.put("payload", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
        return envelope.toString();
    }

    private static JSONObject decode(String encoded) throws Exception {
        JSONObject envelope = new JSONObject(encoded);
        if (!"gzip-base64".equals(envelope.optString("encoding", null)))
            return envelope;

        byte[] compressed = Base64.decode(envelope.getString("payload"), Base64.DEFAULT);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = gzip.read(buffer)) >= 0)
                if (read > 0)
                    bytes.write(buffer, 0, read);
        }
        return new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
    }

    private static void put(JSONObject object, String key, Object value) throws Exception {
        object.put(key, value == null ? JSONObject.NULL : value);
    }

    private static String optString(JSONObject object, String key) {
        return object.isNull(key) ? null : object.optString(key, null);
    }

    private static Long optLong(JSONObject object, String key) {
        return object.isNull(key) ? null : object.optLong(key);
    }

    private static Double optDouble(JSONObject object, String key) {
        return object.isNull(key) ? null : object.optDouble(key);
    }
}

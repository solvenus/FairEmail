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

import androidx.lifecycle.LiveData;

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
 * Persistent, crash-tolerant undo for human Spam Control decisions.
 *
 * Snapshots contain only the isolated intelligence database state. They never
 * contain message bodies, raw headers or raw EML files.
 */
public final class SpamActionHistory {
    private static final int SNAPSHOT_VERSION = 1;
    private static final String SCOPE_ACCOUNT = "account";
    private static final String SCOPE_ALL = "all";

    private SpamActionHistory() {
    }

    public interface Action {
        SpamFamilyLabRepository.ActionResult run() throws Exception;
    }

    public static final class UndoResult {
        public final boolean restored;
        public final String summary;

        private UndoResult(boolean restored, String summary) {
            this.restored = restored;
            this.summary = summary;
        }

        static UndoResult none() {
            return new UndoResult(false, null);
        }
    }

    public static LiveData<EntitySpamAction> liveLatestUndoable(Context context) {
        if (context == null)
            return null;
        return SpamIntelligenceDB.getInstance(context).action().liveLatestUndoable();
    }

    public static SpamFamilyLabRepository.ActionResult applyMessageAction(
            Context context,
            String accountUuid,
            long messageId,
            String kind,
            String summary,
            Action action) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty() ||
                messageId <= 0 || action == null)
            return SpamFamilyLabRepository.ActionResult.REJECTED;

        Context app = context.getApplicationContext();
        SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);
        EntitySpamAction record = null;
        try {
            byte[] snapshot = captureAccount(db.action(), accountUuid.trim(), messageId);
            record = newAction(kind, summary, accountUuid.trim(), messageId, snapshot);
            record.id = db.action().insertAction(record);

            SpamFamilyLabRepository.ActionResult result = action.run();
            if (result == SpamFamilyLabRepository.ActionResult.APPLIED)
                db.action().setActionState(record.id, EntitySpamAction.STATE_APPLIED, null);
            else
                db.action().deleteAction(record.id);
            return result;
        } catch (Throwable ex) {
            Log.e(ex);
            // Keep a PENDING record if the mutation may already have started.
            // It is deliberately undoable on the next app run.
            return SpamFamilyLabRepository.ActionResult.REJECTED;
        }
    }

    public static boolean applyAccountAction(Context context,
                                             String accountUuid,
                                             String kind,
                                             String summary,
                                             Callable<Boolean> action) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty() || action == null)
            return false;

        Context app = context.getApplicationContext();
        SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);
        EntitySpamAction record = null;
        try {
            byte[] snapshot = captureAccount(db.action(), accountUuid.trim(), 0L);
            record = newAction(kind, summary, accountUuid.trim(), null, snapshot);
            record.id = db.action().insertAction(record);

            boolean applied = Boolean.TRUE.equals(action.call());
            if (applied)
                db.action().setActionState(record.id, EntitySpamAction.STATE_APPLIED, null);
            else
                db.action().deleteAction(record.id);
            return applied;
        } catch (Throwable ex) {
            Log.e(ex);
            return false;
        }
    }

    /**
     * Reset learned spam truth while retaining passive alias inventory, manually
     * configured domains, SMTP/cPanel state and all FairEmail mail data.
     */
    public static boolean resetLearning(Context context) {
        if (context == null)
            return false;

        Context app = context.getApplicationContext();
        SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);
        EntitySpamAction record = null;
        try {
            byte[] snapshot = captureAll(db.action());
            record = newAction("reset_learning", "Nullstill spamlæring", null, null, snapshot);
            record.id = db.action().insertAction(record);

            db.runInTransaction(() -> {
                DaoSpamAction dao = db.action();
                dao.deleteAllSpamIntents();
                dao.deleteAllRescoreTasks();
                dao.deleteAllExclusions();
                dao.deleteAllExemplars();
                dao.deleteAllFamilies();
                dao.resetAllDeliveryLearning();
                dao.resetAllAliasLearning();
            });

            db.action().setActionState(record.id, EntitySpamAction.STATE_APPLIED, null);
            return true;
        } catch (Throwable ex) {
            Log.e(ex);
            return false;
        }
    }

    /** Undo the newest not-yet-undone human action. Undo itself is deterministic. */
    public static UndoResult undoLatest(Context context) {
        if (context == null)
            return UndoResult.none();

        Context app = context.getApplicationContext();
        SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);
        EntitySpamAction record = db.action().getLatestUndoable();
        if (record == null || record.id == null || record.snapshot == null)
            return UndoResult.none();

        try {
            Set<String> rescoreAccounts = restore(db, record.snapshot);
            db.action().setActionState(record.id, EntitySpamAction.STATE_UNDONE,
                    System.currentTimeMillis());

            for (String account : rescoreAccounts)
                SpamFamilyRescorer.enqueueAllActive(app, account);
            SpamFamilyRescorer.start(app);
            return new UndoResult(true, record.summary);
        } catch (Throwable ex) {
            Log.e(ex);
            // Leave the record undoable. A transient restore failure must never
            // consume the user's only recovery point.
            return new UndoResult(false, record.summary);
        }
    }

    private static EntitySpamAction newAction(String kind,
                                              String summary,
                                              String accountUuid,
                                              Long messageId,
                                              byte[] snapshot) {
        EntitySpamAction action = new EntitySpamAction();
        action.kind = kind == null ? "action" : kind;
        action.summary = summary == null ? "Handling" : summary;
        action.account_uuid = accountUuid;
        action.message_id = messageId;
        action.created_at = System.currentTimeMillis();
        action.state = EntitySpamAction.STATE_PENDING;
        action.snapshot = snapshot;
        return action;
    }

    private static byte[] captureAccount(DaoSpamAction dao,
                                         String accountUuid,
                                         long messageId) throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", SNAPSHOT_VERSION);
        root.put("scope", SCOPE_ACCOUNT);
        root.put("account_uuid", accountUuid);
        root.put("message_id", messageId);
        root.put("families", familiesToJson(dao.getFamilies(accountUuid)));
        root.put("exemplars", exemplarsToJson(dao.getExemplars(accountUuid)));
        root.put("exclusions", exclusionsToJson(dao.getExclusions(accountUuid)));

        if (messageId > 0) {
            EntityAliasDelivery delivery = dao.getDelivery(accountUuid, messageId);
            if (delivery != null) {
                root.put("delivery", deliveryLearningToJson(delivery));
                EntityAlias alias = dao.getAlias(accountUuid, delivery.address);
                if (alias != null)
                    root.put("alias", aliasLearningToJson(alias));
            }
        }
        return compress(root.toString());
    }

    private static byte[] captureAll(DaoSpamAction dao) throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", SNAPSHOT_VERSION);
        root.put("scope", SCOPE_ALL);
        root.put("families", familiesToJson(dao.getAllFamilies()));
        root.put("exemplars", exemplarsToJson(dao.getAllExemplars()));
        root.put("exclusions", exclusionsToJson(dao.getAllExclusions()));

        JSONArray deliveries = new JSONArray();
        List<EntityAliasDelivery> deliveryRows = dao.getAllDeliveries();
        if (deliveryRows != null)
            for (EntityAliasDelivery delivery : deliveryRows)
                if (delivery != null)
                    deliveries.put(deliveryLearningToJson(delivery));
        root.put("deliveries", deliveries);

        JSONArray aliases = new JSONArray();
        List<EntityAlias> aliasRows = dao.getAllAliases();
        if (aliasRows != null)
            for (EntityAlias alias : aliasRows)
                if (alias != null)
                    aliases.put(aliasLearningToJson(alias));
        root.put("aliases", aliases);
        return compress(root.toString());
    }

    private static Set<String> restore(SpamIntelligenceDB db, byte[] encoded) throws Exception {
        JSONObject root = new JSONObject(decompress(encoded));
        if (root.optInt("version", -1) != SNAPSHOT_VERSION)
            throw new IllegalStateException("Unsupported spam undo snapshot version");

        String scope = root.getString("scope");
        Set<String> accounts = new HashSet<>();
        db.runInTransaction(() -> {
            try {
                DaoSpamAction dao = db.action();
                if (SCOPE_ACCOUNT.equals(scope)) {
                    String account = root.getString("account_uuid");
                    accounts.add(account);

                    dao.deleteRescoreTasks(account);
                    dao.deleteExclusions(account);
                    dao.deleteExemplars(account);
                    dao.deleteFamilies(account);
                    dao.clearPredictions(account);

                    List<EntitySpamFamily> families = familiesFromJson(root.getJSONArray("families"));
                    List<EntitySpamFamilyExemplar> exemplars = exemplarsFromJson(root.getJSONArray("exemplars"));
                    List<EntitySpamFamilyExclusion> exclusions = exclusionsFromJson(root.getJSONArray("exclusions"));
                    if (!families.isEmpty())
                        dao.putFamilies(families);
                    if (!exemplars.isEmpty())
                        dao.putExemplars(exemplars);
                    if (!exclusions.isEmpty())
                        dao.putExclusions(exclusions);

                    JSONObject delivery = root.optJSONObject("delivery");
                    if (delivery != null)
                        restoreDelivery(dao, delivery);
                    JSONObject alias = root.optJSONObject("alias");
                    if (alias != null)
                        restoreAlias(dao, alias);
                } else if (SCOPE_ALL.equals(scope)) {
                    dao.deleteAllSpamIntents();
                    dao.deleteAllRescoreTasks();
                    dao.deleteAllExclusions();
                    dao.deleteAllExemplars();
                    dao.deleteAllFamilies();
                    dao.resetAllDeliveryLearning();
                    dao.resetAllAliasLearning();

                    List<EntitySpamFamily> families = familiesFromJson(root.getJSONArray("families"));
                    List<EntitySpamFamilyExemplar> exemplars = exemplarsFromJson(root.getJSONArray("exemplars"));
                    List<EntitySpamFamilyExclusion> exclusions = exclusionsFromJson(root.getJSONArray("exclusions"));
                    if (!families.isEmpty())
                        dao.putFamilies(families);
                    if (!exemplars.isEmpty())
                        dao.putExemplars(exemplars);
                    if (!exclusions.isEmpty())
                        dao.putExclusions(exclusions);

                    JSONArray deliveries = root.getJSONArray("deliveries");
                    for (int i = 0; i < deliveries.length(); i++)
                        restoreDelivery(dao, deliveries.getJSONObject(i));
                    JSONArray aliases = root.getJSONArray("aliases");
                    for (int i = 0; i < aliases.length(); i++)
                        restoreAlias(dao, aliases.getJSONObject(i));

                    for (EntitySpamFamily family : families)
                        if (family != null && family.account_uuid != null)
                            accounts.add(family.account_uuid);
                } else
                    throw new IllegalStateException("Unknown spam undo scope=" + scope);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        return accounts;
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

    private static JSONObject aliasLearningToJson(EntityAlias row) throws Exception {
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

    private static void restoreAlias(DaoSpamAction dao, JSONObject o) throws Exception {
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

    private static JSONObject deliveryLearningToJson(EntityAliasDelivery row) throws Exception {
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

    private static void restoreDelivery(DaoSpamAction dao, JSONObject o) throws Exception {
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

    private static byte[] compress(String json) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static String decompress(byte[] encoded) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(encoded))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = gzip.read(buffer)) >= 0)
                if (read > 0)
                    bytes.write(buffer, 0, read);
        }
        return bytes.toString(StandardCharsets.UTF_8.name());
    }
}

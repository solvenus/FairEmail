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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/** Persistent semantic undo stack for explicit human choices in Spam Control. */
public final class SpamUndoManager {
    public static final String ACTION_SAME_SPAM = "SAME_SPAM";
    public static final String ACTION_OTHER_SPAM = "OTHER_SPAM";
    public static final String ACTION_NOT_SPAM = "NOT_SPAM";
    public static final String ACTION_RENAME = "RENAME";

    public enum UndoResult {
        APPLIED,
        NOTHING_TO_UNDO,
        FAILED
    }

    private SpamUndoManager() {
    }

    public static SpamFamilyLabRepository.ActionResult runMessageAction(
            Context context,
            String accountUuid,
            long contextFamilyId,
            long messageId,
            String action,
            String description,
            Callable<SpamFamilyLabRepository.ActionResult> delegate) {
        if (context == null || accountUuid == null || delegate == null)
            return SpamFamilyLabRepository.ActionResult.REJECTED;

        try {
            Context app = context.getApplicationContext();
            JSONObject before = snapshotMessage(app, accountUuid, messageId);
            if (before == null)
                return SpamFamilyLabRepository.ActionResult.MESSAGE_MISSING;

            SpamFamilyLabRepository.ActionResult result = delegate.call();
            if (result == SpamFamilyLabRepository.ActionResult.APPLIED)
                record(app, accountUuid, messageId, contextFamilyId,
                        action, description, before);
            return result;
        } catch (Throwable ex) {
            Log.e(ex);
            return SpamFamilyLabRepository.ActionResult.REJECTED;
        }
    }

    public static boolean renameFamily(Context context,
                                       String accountUuid,
                                       long familyId,
                                       String newName) {
        if (context == null || accountUuid == null || familyId <= 0)
            return false;
        try {
            Context app = context.getApplicationContext();
            SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);
            EntitySpamFamily family = db.family().getFamily(familyId);
            if (family == null || family.id == null ||
                    !accountUuid.equals(family.account_uuid))
                return false;

            String value = normalizeName(newName);
            if (Objects.equals(family.name, value))
                return true;

            JSONObject before = new JSONObject();
            before.put("kind", "rename");
            before.put("family_id", familyId);
            putNullable(before, "name", family.name);

            if (db.family().setFamilyName(familyId, value) != 1)
                return false;
            record(app, accountUuid, null, familyId, ACTION_RENAME,
                    "Gi spamgruppen navn", before);
            return true;
        } catch (Throwable ex) {
            Log.e(ex);
            return false;
        }
    }

    public static EntitySpamActionHistory latest(Context context, String accountUuid) {
        if (context == null || accountUuid == null)
            return null;
        try {
            return SpamIntelligenceDB.getInstance(context.getApplicationContext())
                    .actions().getLatestUndoable(accountUuid);
        } catch (Throwable ex) {
            Log.e(ex);
            return null;
        }
    }

    public static UndoResult undoLatest(Context context, String accountUuid) {
        if (context == null || accountUuid == null)
            return UndoResult.FAILED;
        Context app = context.getApplicationContext();
        SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);
        EntitySpamActionHistory history = db.actions().getLatestUndoable(accountUuid);
        if (history == null || history.id == null)
            return UndoResult.NOTHING_TO_UNDO;

        try {
            JSONObject before = new JSONObject(history.before_json);
            String kind = before.optString("kind", "message");
            boolean restored;
            if ("rename".equals(kind))
                restored = restoreRename(db, accountUuid, before);
            else
                restored = restoreMessage(app, db, accountUuid, history.message_id, before);

            if (!restored)
                return UndoResult.FAILED;
            if (db.actions().markUndone(history.id, System.currentTimeMillis()) != 1)
                return UndoResult.FAILED;
            return UndoResult.APPLIED;
        } catch (Throwable ex) {
            Log.e(ex);
            return UndoResult.FAILED;
        }
    }

    private static JSONObject snapshotMessage(Context context,
                                              String accountUuid,
                                              long messageId) throws Exception {
        SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(context);
        EntityAliasDelivery delivery = db.alias().getDelivery(accountUuid, messageId);
        if (delivery == null)
            return null;

        JSONObject out = new JSONObject();
        out.put("kind", "message");
        out.put("label", delivery.label);
        putNullable(out, "family_id", delivery.family_id);

        JSONArray exclusions = new JSONArray();
        List<Long> excluded = db.family().getExcludedFamilyIds(accountUuid, messageId);
        if (excluded != null)
            for (Long id : excluded)
                if (id != null)
                    exclusions.put(id);
        out.put("exclusions", exclusions);

        if (delivery.family_id != null) {
            EntitySpamFamily family = db.family().getFamily(delivery.family_id);
            if (family != null) {
                JSONObject f = new JSONObject();
                f.put("id", family.id);
                putNullable(f, "name", family.name);
                f.put("active", family.active);
                f.put("created_at", family.created_at);
                f.put("updated_at", family.updated_at);
                out.put("family", f);
            }
        }

        EntitySpamFamilyExemplar exemplar = db.family()
                .getExemplar(accountUuid, messageId);
        if (exemplar != null && exemplar.fingerprint != null) {
            JSONObject e = new JSONObject();
            e.put("family_id", exemplar.family_id);
            e.put("fingerprint", Base64.encodeToString(
                    exemplar.fingerprint, Base64.NO_WRAP));
            e.put("created_at", exemplar.created_at);
            out.put("exemplar", e);
        }
        return out;
    }

    private static boolean restoreRename(SpamIntelligenceDB db,
                                         String accountUuid,
                                         JSONObject before) {
        long familyId = before.optLong("family_id", 0);
        if (familyId <= 0)
            return false;
        EntitySpamFamily family = db.family().getFamily(familyId);
        if (family == null || !accountUuid.equals(family.account_uuid))
            return false;
        String name = nullableString(before, "name");
        return db.family().setFamilyName(familyId, name) == 1;
    }

    private static boolean restoreMessage(Context context,
                                          SpamIntelligenceDB db,
                                          String accountUuid,
                                          Long messageIdObject,
                                          JSONObject before) throws Exception {
        if (messageIdObject == null)
            return false;
        long messageId = messageIdObject;
        DaoAlias aliasDao = db.alias();
        DaoSpamFamily familyDao = db.family();
        EntityAliasDelivery current = aliasDao.getDelivery(accountUuid, messageId);
        if (current == null)
            return false;

        // Remove the semantic state created by the action we are undoing.
        if (current.label == EntityAliasDelivery.LABEL_SPAM)
            SpamFamilyStore.unlearnMessage(context, accountUuid, messageId, current.family_id);
        SpamAliasStore.setLabel(context, accountUuid, messageId,
                EntityAliasDelivery.LABEL_UNKNOWN, null);
        familyDao.deleteExclusionsForMessage(accountUuid, messageId);

        int oldLabel = before.optInt("label", EntityAliasDelivery.LABEL_UNKNOWN);
        Long oldFamilyId = nullableLong(before, "family_id");

        if (oldLabel == EntityAliasDelivery.LABEL_SPAM) {
            if (oldFamilyId != null)
                restoreFamilyIfMissing(familyDao, accountUuid, oldFamilyId,
                        before.optJSONObject("family"));

            restoreExemplarIfPresent(familyDao, accountUuid, messageId,
                    oldFamilyId, before.optJSONObject("exemplar"));

            SpamAliasStore.setLabel(context, accountUuid, messageId,
                    EntityAliasDelivery.LABEL_SPAM, oldFamilyId);
            if (oldFamilyId != null) {
                SpamFamilyStore.reconcileFamily(context, oldFamilyId);
                JSONObject familySnapshot = before.optJSONObject("family");
                if (familySnapshot != null && familyDao.getFamily(oldFamilyId) != null)
                    familyDao.setFamilyActive(oldFamilyId,
                            familySnapshot.optBoolean("active", true),
                            System.currentTimeMillis());
            }
        } else if (oldLabel == EntityAliasDelivery.LABEL_HAM) {
            SpamAliasStore.setLabel(context, accountUuid, messageId,
                    EntityAliasDelivery.LABEL_HAM, null);
        }

        JSONArray exclusions = before.optJSONArray("exclusions");
        if (exclusions != null)
            for (int i = 0; i < exclusions.length(); i++) {
                long familyId = exclusions.optLong(i, 0);
                if (familyId <= 0)
                    continue;
                EntitySpamFamilyExclusion exclusion = new EntitySpamFamilyExclusion();
                exclusion.account_uuid = accountUuid;
                exclusion.message_id = messageId;
                exclusion.family_id = familyId;
                exclusion.created_at = System.currentTimeMillis();
                exclusion.reason = "undo_restore";
                familyDao.insertExclusion(exclusion);
            }

        familyDao.clearFamilyMatch(accountUuid, messageId, System.currentTimeMillis());
        SpamFamilyRescorer.enqueueAllActive(context, accountUuid);
        restoreAliasState(aliasDao, accountUuid, current.address);
        return true;
    }

    private static void restoreFamilyIfMissing(DaoSpamFamily dao,
                                               String accountUuid,
                                               long familyId,
                                               JSONObject snapshot) throws Exception {
        if (dao.getFamily(familyId) != null)
            return;

        EntitySpamFamily family = new EntitySpamFamily();
        family.id = familyId;
        family.account_uuid = accountUuid;
        family.name = snapshot == null ? null : nullableString(snapshot, "name");
        family.active = snapshot == null || snapshot.optBoolean("active", true);
        family.confirmed_count = 0;
        long now = System.currentTimeMillis();
        family.created_at = snapshot == null ? now : snapshot.optLong("created_at", now);
        family.updated_at = now;
        dao.insertFamily(family);
    }

    private static void restoreExemplarIfPresent(DaoSpamFamily dao,
                                                 String accountUuid,
                                                 long messageId,
                                                 Long fallbackFamilyId,
                                                 JSONObject snapshot) {
        if (snapshot == null || dao.getExemplar(accountUuid, messageId) != null)
            return;
        String encoded = snapshot.optString("fingerprint", null);
        if (encoded == null)
            return;
        long familyId = snapshot.optLong("family_id",
                fallbackFamilyId == null ? 0 : fallbackFamilyId);
        if (familyId <= 0 || dao.getFamily(familyId) == null)
            return;

        EntitySpamFamilyExemplar exemplar = new EntitySpamFamilyExemplar();
        exemplar.family_id = familyId;
        exemplar.account_uuid = accountUuid;
        exemplar.source_message_id = messageId;
        exemplar.fingerprint = Base64.decode(encoded, Base64.DEFAULT);
        exemplar.created_at = snapshot.optLong("created_at", System.currentTimeMillis());
        dao.insertExemplar(exemplar);
    }

    private static void restoreAliasState(DaoAlias dao,
                                          String accountUuid,
                                          String address) {
        if (address == null)
            return;
        EntityAlias alias = dao.getAlias(accountUuid, address);
        if (alias == null)
            return;
        if (alias.spam_hits > 0 && alias.state == EntityAlias.STATE_ACTIVE)
            dao.markCompromised(accountUuid, address);
        else if (alias.spam_hits == 0 && alias.state == EntityAlias.STATE_COMPROMISED)
            dao.setState(accountUuid, address, EntityAlias.STATE_ACTIVE);
    }

    private static void record(Context context,
                               String accountUuid,
                               Long messageId,
                               long contextFamilyId,
                               String action,
                               String description,
                               JSONObject before) {
        EntitySpamActionHistory history = new EntitySpamActionHistory();
        history.account_uuid = accountUuid;
        history.message_id = messageId;
        history.context_family_id = contextFamilyId > 0 ? contextFamilyId : null;
        history.action = action;
        history.description = description == null ? action : description;
        history.before_json = before.toString();
        history.created_at = System.currentTimeMillis();
        SpamIntelligenceDB.getInstance(context).actions().insert(history);
    }

    private static String normalizeName(String name) {
        if (name == null)
            return null;
        String value = name.trim();
        return value.isEmpty() ? null : value;
    }

    private static void putNullable(JSONObject object, String key, Object value) throws Exception {
        object.put(key, value == null ? JSONObject.NULL : value);
    }

    private static Long nullableLong(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key))
            return null;
        return object.optLong(key);
    }

    private static String nullableString(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key))
            return null;
        return object.optString(key, null);
    }
}

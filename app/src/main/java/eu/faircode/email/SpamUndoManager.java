package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;

/** Persistent semantic undo stack for explicit human choices in Spam Control. */
public final class SpamUndoManager {
    public static final String ACTION_SPAM = "SPAM";
    public static final String ACTION_SAME_SPAM = "SAME_SPAM";
    public static final String ACTION_OTHER_SPAM = "OTHER_SPAM";
    public static final String ACTION_NOT_SPAM = "NOT_SPAM";
    public static final String ACTION_RENAME = "RENAME";
    public static final String ACTION_RESET_ALL = "RESET_ALL";

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
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty() ||
                messageId <= 0 || delegate == null)
            return SpamFamilyLabRepository.ActionResult.REJECTED;

        Context app = context.getApplicationContext();
        SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);
        Long historyId = null;
        try {
            String before = SpamLearningSnapshot.captureAccount(
                    app, accountUuid.trim(), messageId);
            historyId = recordBefore(db, accountUuid.trim(), messageId,
                    contextFamilyId, action, description, before);

            SpamFamilyLabRepository.ActionResult result = delegate.call();
            if (result != SpamFamilyLabRepository.ActionResult.APPLIED)
                db.actions().delete(historyId);
            return result;
        } catch (Throwable ex) {
            Log.e(ex);
            return SpamFamilyLabRepository.ActionResult.REJECTED;
        }
    }

    public static SpamFamilyLabRepository.BulkActionResult runBulkMessageAction(
            Context context,
            String accountUuid,
            long contextFamilyId,
            long messageId,
            String action,
            String description,
            Callable<SpamFamilyLabRepository.BulkActionResult> delegate) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty() ||
                messageId <= 0 || delegate == null)
            return SpamFamilyLabRepository.BulkActionResult.rejected();

        Context app = context.getApplicationContext();
        SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);
        Long historyId = null;
        try {
            String before = SpamLearningSnapshot.captureAccountAllLearning(
                    app, accountUuid.trim());
            historyId = recordBefore(db, accountUuid.trim(), messageId,
                    contextFamilyId, action, description, before);

            SpamFamilyLabRepository.BulkActionResult result = delegate.call();
            if (result == null || result.result != SpamFamilyLabRepository.ActionResult.APPLIED)
                db.actions().delete(historyId);
            return result == null
                    ? SpamFamilyLabRepository.BulkActionResult.rejected()
                    : result;
        } catch (Throwable ex) {
            Log.e(ex);
            if (historyId != null)
                db.actions().delete(historyId);
            return SpamFamilyLabRepository.BulkActionResult.rejected();
        }
    }

    public static boolean renameFamily(Context context,
                                       String accountUuid,
                                       long familyId,
                                       String newName) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty() || familyId <= 0)
            return false;

        Context app = context.getApplicationContext();
        SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);
        Long historyId = null;
        try {
            EntitySpamFamily family = db.family().getFamily(familyId);
            if (family == null || family.id == null ||
                    !accountUuid.trim().equals(family.account_uuid))
                return false;

            String value = normalizeName(newName);
            if (Objects.equals(family.name, value))
                return true;

            String before = SpamLearningSnapshot.captureAccount(
                    app, accountUuid.trim(), 0L);
            historyId = recordBefore(db, accountUuid.trim(), null,
                    familyId, ACTION_RENAME, "Gi spamgruppen navn", before);

            if (db.family().setFamilyName(familyId, value) != 1) {
                db.actions().delete(historyId);
                return false;
            }
            return true;
        } catch (Throwable ex) {
            Log.e(ex);
            return false;
        }
    }

    public static boolean resetAllLearning(Context context, String historyAccountUuid) {
        if (context == null || historyAccountUuid == null || historyAccountUuid.trim().isEmpty())
            return false;

        Context app = context.getApplicationContext();
        SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);
        try {
            String before = SpamLearningSnapshot.captureAll(app);
            recordBefore(db, historyAccountUuid.trim(), null, 0L,
                    ACTION_RESET_ALL, "Nullstill all spamlæring", before);
            SpamLearningSnapshot.resetAll(app);
            return true;
        } catch (Throwable ex) {
            Log.e(ex);
            return false;
        }
    }

    public static EntitySpamActionHistory latest(Context context) {
        if (context == null)
            return null;
        try {
            return SpamIntelligenceDB.getInstance(context.getApplicationContext())
                    .actions().getLatestUndoable();
        } catch (Throwable ex) {
            Log.e(ex);
            return null;
        }
    }

    public static EntitySpamActionHistory latest(Context context, String accountUuid) {
        return latest(context);
    }

    public static UndoResult undoLatest(Context context, String accountUuid) {
        if (context == null)
            return UndoResult.FAILED;

        Context app = context.getApplicationContext();
        SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);
        EntitySpamActionHistory history = db.actions().getLatestUndoable();
        if (history == null || history.id == null || history.before_json == null)
            return UndoResult.NOTHING_TO_UNDO;

        try {
            Set<String> accounts = SpamLearningSnapshot.restore(app, history.before_json);
            if (db.actions().markUndone(history.id, System.currentTimeMillis()) != 1)
                return UndoResult.FAILED;

            for (String account : accounts)
                if (account != null && !account.trim().isEmpty())
                    SpamFamilyRescorer.enqueueAllActive(app, account);
            SpamFamilyRescorer.start(app);
            return UndoResult.APPLIED;
        } catch (Throwable ex) {
            Log.e(ex);
            return UndoResult.FAILED;
        }
    }

    private static long recordBefore(SpamIntelligenceDB db,
                                     String accountUuid,
                                     Long messageId,
                                     long contextFamilyId,
                                     String action,
                                     String description,
                                     String before) {
        EntitySpamActionHistory history = new EntitySpamActionHistory();
        history.account_uuid = accountUuid;
        history.message_id = messageId;
        history.context_family_id = contextFamilyId > 0 ? contextFamilyId : null;
        history.action = action == null ? "ACTION" : action;
        history.description = description == null ? history.action : description;
        history.before_json = before;
        history.created_at = System.currentTimeMillis();
        return db.actions().insert(history);
    }

    private static String normalizeName(String name) {
        if (name == null)
            return null;
        String value = name.trim();
        return value.isEmpty() ? null : value;
    }
}

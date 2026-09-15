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
import androidx.room.InvalidationTracker;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Converts explicit user junk moves into durable spam/ham labels.
 *
 * FairEmail's MOVE queue is the common path for button, swipe and batch
 * actions. Rules/classification can use the same queue, so candidates are
 * captured immediately and evaluated after a short delay. FairEmail's own
 * $Filtered/$Classified markers, plus queued marker operations, are treated as
 * automation provenance and are never learned as user-confirmed labels.
 */
public final class SpamIntentObserver {
    private static final String META_HIGH_WATER = "spam_intent_operation_high_water";
    private static final long SETTLE_MS = 350L;

    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static final AtomicBoolean scanScheduled = new AtomicBoolean(false);
    private static final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "spam-intent");
                thread.setDaemon(true);
                return thread;
            });

    private SpamIntentObserver() {
    }

    public static void start(Context context) {
        if (context == null || !started.compareAndSet(false, true))
            return;

        final Context app = context.getApplicationContext();
        final DB mail = DB.getInstance(app);
        mail.getInvalidationTracker().addObserver(new InvalidationTracker.Observer(EntityOperation.TABLE_NAME) {
            @Override
            public void onInvalidated(@NonNull Set<String> tables) {
                scheduleScan(app, 0L);
            }
        });

        // Catch operations that existed before the observer was attached.
        scheduleScan(app, 0L);
    }

    private static void scheduleScan(Context context, long delayMs) {
        if (!scanScheduled.compareAndSet(false, true))
            return;
        executor.schedule(() -> {
            try {
                capture(context);
            } catch (Throwable ex) {
                Log.e(ex);
            } finally {
                scanScheduled.set(false);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private static void capture(Context context) throws JSONException {
        DB mail = DB.getInstance(context);
        SpamIntelligenceDB intelligence = SpamIntelligenceDB.getInstance(context);
        DaoAlias aliases = intelligence.alias();

        Long saved = aliases.getMetaLong(META_HIGH_WATER);
        long highWater = saved == null ? 0L : saved;
        long capturedMax = highWater;
        List<Candidate> candidates = new ArrayList<>();

        List<EntityOperation> operations = mail.operation().getOperations(EntityOperation.MOVE);
        if (operations != null)
            for (EntityOperation operation : operations) {
                if (operation == null || operation.id == null || operation.id <= highWater ||
                        operation.message == null || operation.args == null)
                    continue;

                capturedMax = Math.max(capturedMax, operation.id);
                JSONArray args = new JSONArray(operation.args);
                if (args.length() == 0 || args.isNull(0))
                    continue;

                Candidate candidate = new Candidate();
                candidate.operationId = operation.id;
                candidate.messageId = operation.message;
                candidate.sourceFolderId = operation.folder;
                candidate.targetFolderId = args.getLong(0);
                candidates.add(candidate);
            }

        if (capturedMax > highWater) {
            EntitySpamMeta meta = new EntitySpamMeta();
            meta.key = META_HIGH_WATER;
            meta.long_value = capturedMax;
            aliases.putMeta(meta);
        }

        if (!candidates.isEmpty())
            executor.schedule(() -> evaluate(context, candidates), SETTLE_MS, TimeUnit.MILLISECONDS);
    }

    private static void evaluate(Context context, List<Candidate> candidates) {
        try {
            DB db = DB.getInstance(context);
            Set<Long> automated = automatedMessageIds(db);

            for (Candidate candidate : candidates) {
                try {
                    EntityMessage message = db.message().getMessage(candidate.messageId);
                    EntityFolder source = db.folder().getFolder(candidate.sourceFolderId);
                    EntityFolder target = db.folder().getFolder(candidate.targetFolderId);
                    if (message == null || source == null || target == null)
                        continue;

                    if (automated.contains(message.id) ||
                            message.hasKeyword(MessageHelper.FLAG_CLASSIFIED) ||
                            message.hasKeyword(MessageHelper.FLAG_FILTERED) ||
                            message.auto_classified)
                        continue;

                    EntityAccount account = db.account().getAccount(message.account);
                    if (account == null)
                        continue;

                    if (!EntityFolder.JUNK.equals(source.type) &&
                            EntityFolder.JUNK.equals(target.type)) {
                        SpamIntelligence.learnSpam(context, account, message, null);
                        Log.i("SpamIntent user-spam op=" + candidate.operationId +
                                " message=" + message.id);
                    } else if (EntityFolder.JUNK.equals(source.type) &&
                            !EntityFolder.JUNK.equals(target.type) &&
                            !EntityFolder.TRASH.equals(target.type)) {
                        SpamIntelligence.learnHam(context, account, message);
                        Log.i("SpamIntent user-ham op=" + candidate.operationId +
                                " message=" + message.id);
                    }
                } catch (Throwable ex) {
                    Log.e(ex);
                }
            }
        } catch (Throwable ex) {
            Log.e(ex);
        }
    }

    /** Find automation markers already queued even if the message keyword write races us. */
    private static Set<Long> automatedMessageIds(DB db) {
        Set<Long> automated = new HashSet<>();
        try {
            List<EntityOperation> keywords = db.operation().getOperations(EntityOperation.KEYWORD);
            if (keywords == null)
                return automated;

            for (EntityOperation operation : keywords) {
                if (operation == null || operation.message == null || operation.args == null)
                    continue;
                try {
                    JSONArray args = new JSONArray(operation.args);
                    if (args.length() < 2 || !args.optBoolean(1, false))
                        continue;
                    String keyword = args.optString(0, null);
                    if (MessageHelper.FLAG_FILTERED.equals(keyword) ||
                            MessageHelper.FLAG_CLASSIFIED.equals(keyword))
                        automated.add(operation.message);
                } catch (JSONException ex) {
                    Log.w(ex);
                }
            }
        } catch (Throwable ex) {
            Log.e(ex);
        }
        return automated;
    }

    private static final class Candidate {
        long operationId;
        long messageId;
        long sourceFolderId;
        long targetFolderId;
    }
}

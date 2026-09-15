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
 * persisted immediately and evaluated after a short settling period.
 * FairEmail's own $Filtered/$Classified markers, plus queued marker operations,
 * are treated as automation provenance and are never learned as user labels.
 */
public final class SpamIntentObserver {
    private static final String META_HIGH_WATER = "spam_intent_operation_high_water";
    private static final long SETTLE_MS = 750L;
    private static final long RETRY_MS = 1500L;
    private static final long MAX_PENDING_AGE_MS = 5 * 60 * 1000L;
    private static final int MAX_ATTEMPTS = 5;
    private static final int BATCH_SIZE = 100;

    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static final AtomicBoolean scanScheduled = new AtomicBoolean(false);
    private static final AtomicBoolean scanAgain = new AtomicBoolean(false);
    private static final AtomicBoolean evaluateScheduled = new AtomicBoolean(false);
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

        // Catch operations that existed before the observer was attached and
        // pending intents left by a previous process.
        scheduleScan(app, 0L);
        scheduleEvaluate(app, SETTLE_MS);
    }

    private static void scheduleScan(Context context, long delayMs) {
        if (!scanScheduled.compareAndSet(false, true)) {
            scanAgain.set(true);
            return;
        }

        executor.schedule(() -> {
            try {
                capture(context);
            } catch (Throwable ex) {
                Log.e(ex);
            } finally {
                scanScheduled.set(false);
                if (scanAgain.getAndSet(false))
                    scheduleScan(context, 0L);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private static void scheduleEvaluate(Context context, long delayMs) {
        if (!evaluateScheduled.compareAndSet(false, true))
            return;

        executor.schedule(() -> {
            boolean retry = false;
            try {
                retry = evaluatePending(context);
            } catch (Throwable ex) {
                Log.e(ex);
                retry = true;
            } finally {
                evaluateScheduled.set(false);
                if (retry)
                    scheduleEvaluate(context, RETRY_MS);
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
        long now = System.currentTimeMillis();
        List<EntitySpamIntent> candidates = new ArrayList<>();

        List<EntityOperation> operations = mail.operation().getOperations(EntityOperation.MOVE);
        if (operations != null)
            for (EntityOperation operation : operations) {
                if (operation == null || operation.id == null || operation.id <= highWater)
                    continue;

                // Advance across every observed MOVE id even if this particular
                // row cannot become a useful intent candidate.
                capturedMax = Math.max(capturedMax, operation.id);
                if (operation.message == null || operation.args == null)
                    continue;

                JSONArray args = new JSONArray(operation.args);
                if (args.length() == 0 || args.isNull(0))
                    continue;

                EntityFolder source = mail.folder().getFolder(operation.folder);
                EntityFolder target = mail.folder().getFolder(args.getLong(0));
                if (source == null || target == null)
                    continue;

                // Persist only transitions that could semantically be a spam or
                // not-spam action. Ordinary folder moves never enter our queue.
                boolean toJunk = !EntityFolder.JUNK.equals(source.type) &&
                        EntityFolder.JUNK.equals(target.type);
                boolean fromJunk = EntityFolder.JUNK.equals(source.type) &&
                        !EntityFolder.JUNK.equals(target.type) &&
                        !EntityFolder.TRASH.equals(target.type);
                if (!toJunk && !fromJunk)
                    continue;

                EntitySpamIntent candidate = new EntitySpamIntent();
                candidate.operation_id = operation.id;
                candidate.message_id = operation.message;
                candidate.source_type = source.type;
                candidate.target_type = target.type;
                candidate.captured_at = now;
                candidates.add(candidate);
            }

        final long finalCapturedMax = capturedMax;
        if (finalCapturedMax > highWater || !candidates.isEmpty()) {
            intelligence.runInTransaction(new Runnable() {
                @Override
                public void run() {
                    for (EntitySpamIntent candidate : candidates)
                        aliases.insertSpamIntent(candidate);

                    if (finalCapturedMax > highWater) {
                        EntitySpamMeta meta = new EntitySpamMeta();
                        meta.key = META_HIGH_WATER;
                        meta.long_value = finalCapturedMax;
                        aliases.putMeta(meta);
                    }
                }
            });
        }

        if (!candidates.isEmpty())
            scheduleEvaluate(context, SETTLE_MS);
    }

    /**
     * @return true if durable pending work remains and should be retried.
     */
    private static boolean evaluatePending(Context context) {
        DB db = DB.getInstance(context);
        DaoAlias aliases = SpamIntelligenceDB.getInstance(context).alias();
        List<EntitySpamIntent> pending = aliases.getSpamIntents(BATCH_SIZE);
        if (pending == null || pending.isEmpty())
            return false;

        Set<Long> automated = automatedMessageIds(db);
        long now = System.currentTimeMillis();
        boolean retry = false;

        for (EntitySpamIntent candidate : pending) {
            if (candidate == null)
                continue;

            try {
                EntityMessage message = db.message().getMessage(candidate.message_id);
                if (message == null) {
                    if (expired(candidate, now)) {
                        // No surviving message means automation provenance cannot
                        // be proven. Drop rather than poison the learning model.
                        aliases.deleteSpamIntent(candidate.operation_id);
                        Log.w("SpamIntent expired missing message op=" + candidate.operation_id);
                    } else {
                        aliases.markSpamIntentAttempt(candidate.operation_id, now);
                        retry = true;
                    }
                    continue;
                }

                if (automated.contains(message.id) ||
                        message.hasKeyword(MessageHelper.FLAG_CLASSIFIED) ||
                        message.hasKeyword(MessageHelper.FLAG_FILTERED) ||
                        message.auto_classified) {
                    aliases.deleteSpamIntent(candidate.operation_id);
                    Log.i("SpamIntent automation ignored op=" + candidate.operation_id +
                            " message=" + message.id);
                    continue;
                }

                EntityAccount account = db.account().getAccount(message.account);
                if (account == null) {
                    if (expired(candidate, now))
                        aliases.deleteSpamIntent(candidate.operation_id);
                    else {
                        aliases.markSpamIntentAttempt(candidate.operation_id, now);
                        retry = true;
                    }
                    continue;
                }

                if (!EntityFolder.JUNK.equals(candidate.source_type) &&
                        EntityFolder.JUNK.equals(candidate.target_type)) {
                    SpamIntelligence.learnSpam(context, account, message, null);
                    aliases.deleteSpamIntent(candidate.operation_id);
                    Log.i("SpamIntent user-spam op=" + candidate.operation_id +
                            " message=" + message.id);
                } else if (EntityFolder.JUNK.equals(candidate.source_type) &&
                        !EntityFolder.JUNK.equals(candidate.target_type) &&
                        !EntityFolder.TRASH.equals(candidate.target_type)) {
                    SpamIntelligence.learnHam(context, account, message);
                    aliases.deleteSpamIntent(candidate.operation_id);
                    Log.i("SpamIntent user-ham op=" + candidate.operation_id +
                            " message=" + message.id);
                } else {
                    aliases.deleteSpamIntent(candidate.operation_id);
                }
            } catch (Throwable ex) {
                Log.e(ex);
                if (expired(candidate, now))
                    aliases.deleteSpamIntent(candidate.operation_id);
                else {
                    aliases.markSpamIntentAttempt(candidate.operation_id, now);
                    retry = true;
                }
            }
        }

        // More than one batch can exist after bulk operations.
        if (pending.size() >= BATCH_SIZE)
            retry = true;
        return retry;
    }

    private static boolean expired(EntitySpamIntent candidate, long now) {
        long age = Math.max(0L, now - candidate.captured_at);
        return age >= MAX_PENDING_AGE_MS || candidate.attempts >= MAX_ATTEMPTS;
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
}

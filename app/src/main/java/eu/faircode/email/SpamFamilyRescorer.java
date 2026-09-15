package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Restart-safe observer-only scan of already indexed mail when a spam family
 * gains or loses an exemplar. This class never moves, deletes, or labels mail.
 */
public final class SpamFamilyRescorer {
    private static final int PAGE_SIZE = 24;
    private static final long PAGE_DELAY_MS = 35L;

    private static final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "spam-family-rescore");
                thread.setDaemon(true);
                return thread;
            });
    private static final AtomicBoolean scheduled = new AtomicBoolean(false);

    private SpamFamilyRescorer() {
    }

    /** Resume any persisted work after process restart. */
    public static void start(Context context) {
        if (context == null)
            return;
        schedule(context.getApplicationContext(), 0L);
    }

    /**
     * Coalescing enqueue. Re-enqueueing the same account/family resets the cursor
     * with a newer generation. An older worker can no longer checkpoint over it.
     */
    public static void enqueue(Context context, String accountUuid, long familyId) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty() || familyId <= 0)
            return;
        final Context app = context.getApplicationContext();
        final String account = accountUuid.trim();
        executor.execute(() -> {
            try {
                DaoSpamFamily dao = SpamIntelligenceDB.getInstance(app).family();
                EntitySpamRescoreTask previous = dao.getRescoreTask(account, familyId);
                long now = System.currentTimeMillis();
                long generation = now;
                if (previous != null && generation <= previous.requested_at)
                    generation = previous.requested_at + 1L;

                EntitySpamRescoreTask task = new EntitySpamRescoreTask();
                task.account_uuid = account;
                task.family_id = familyId;
                task.before_message_id = Long.MAX_VALUE;
                task.requested_at = generation;
                task.updated_at = now;
                dao.putRescoreTask(task);
                Log.i("SpamFamily rescore queued account=" + account +
                        " family=" + familyId + " generation=" + generation);
            } catch (Throwable ex) {
                Log.e(ex);
            } finally {
                schedule(app, 0L);
            }
        });
    }

    private static void schedule(Context context, long delayMs) {
        if (!scheduled.compareAndSet(false, true))
            return;
        executor.schedule(() -> {
            boolean more = false;
            try {
                more = processOnePage(context);
            } catch (Throwable ex) {
                Log.e(ex);
            } finally {
                scheduled.set(false);
                if (more)
                    schedule(context, PAGE_DELAY_MS);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** @return true when persisted work remains after this page. */
    private static boolean processOnePage(Context context) {
        SpamIntelligenceDB intelligence = SpamIntelligenceDB.getInstance(context);
        DaoSpamFamily dao = intelligence.family();
        EntitySpamRescoreTask task = dao.getNextRescoreTask();
        if (task == null)
            return false;

        SpamFamilyStore.FamilyMatcher matcher = SpamFamilyStore.loadMatcher(
                context, task.account_uuid, task.family_id);
        if (matcher == null) {
            dao.deleteRescoreTask(task.account_uuid, task.family_id, task.requested_at);
            return dao.getNextRescoreTask() != null;
        }

        List<EntityAliasDelivery> page = dao.getRescorePage(
                task.account_uuid, task.before_message_id, PAGE_SIZE);
        if (page == null || page.isEmpty()) {
            finish(dao, task);
            return dao.getNextRescoreTask() != null;
        }

        DB mail = DB.getInstance(context);
        long nextBefore = task.before_message_id;
        int processed = task.processed;
        int matches = task.matches;

        for (EntityAliasDelivery delivery : page) {
            if (delivery == null)
                continue;
            nextBefore = Math.min(nextBefore, delivery.message_id);
            processed++;

            try {
                EntityMessage message = mail.message().getMessage(delivery.message_id);
                if (message == null)
                    continue;

                SpamFamilyFingerprint fingerprint =
                        SpamFamilyMessageAdapter.fromMessage(context, message);
                if (fingerprint == null)
                    continue;

                SpamFamilyEngine.Score candidate = matcher.score(fingerprint);
                if (candidate.value >= SpamFamilyEngine.DEFAULT_DETECT_THRESHOLD)
                    matches++;

                SpamFamilyPredictionPolicy.Action action = SpamFamilyPredictionPolicy.decide(
                        delivery.predicted_family_id,
                        delivery.family_score,
                        task.family_id,
                        candidate.value);
                switch (action) {
                    case RECOMPUTE_ALL:
                        // Pruning/correction weakened the current winner. Let all
                        // active families compete again for this one message.
                        applyBestMatch(context, dao, task.account_uuid,
                                delivery.message_id, fingerprint);
                        break;
                    case USE_CANDIDATE:
                        setMatch(dao, task.account_uuid, delivery.message_id,
                                task.family_id, candidate, System.currentTimeMillis());
                        break;
                    case KEEP_CURRENT:
                    default:
                        break;
                }
            } catch (Throwable ex) {
                // One damaged/missing historical message must not stop the scan.
                Log.w(ex);
            }
        }

        long now = System.currentTimeMillis();
        if (page.size() < PAGE_SIZE) {
            // A newer enqueue may have replaced this generation while the page
            // was running. Generation-guarded delete intentionally leaves it.
            int deleted = dao.deleteRescoreTask(
                    task.account_uuid, task.family_id, task.requested_at);
            if (deleted > 0)
                Log.i("SpamFamily rescore complete account=" + task.account_uuid +
                        " family=" + task.family_id +
                        " processed=" + processed + " matches=" + matches);
        } else {
            int checkpointed = dao.checkpointRescoreTask(
                    task.account_uuid,
                    task.family_id,
                    task.requested_at,
                    nextBefore,
                    now,
                    processed,
                    matches);
            if (checkpointed == 0)
                Log.i("SpamFamily rescore superseded account=" + task.account_uuid +
                        " family=" + task.family_id +
                        " generation=" + task.requested_at);
        }

        return dao.getNextRescoreTask() != null;
    }

    private static void finish(DaoSpamFamily dao, EntitySpamRescoreTask task) {
        int deleted = dao.deleteRescoreTask(
                task.account_uuid, task.family_id, task.requested_at);
        if (deleted > 0)
            Log.i("SpamFamily rescore complete account=" + task.account_uuid +
                    " family=" + task.family_id +
                    " processed=" + task.processed + " matches=" + task.matches);
    }

    private static void applyBestMatch(Context context,
                                       DaoSpamFamily dao,
                                       String accountUuid,
                                       long messageId,
                                       SpamFamilyFingerprint fingerprint) {
        SpamFamilyStore.Match best = SpamFamilyStore.match(context, accountUuid, fingerprint);
        long assessedAt = System.currentTimeMillis();
        if (best.familyId == null)
            dao.clearFamilyMatch(accountUuid, messageId, assessedAt);
        else
            setMatch(dao, accountUuid, messageId, best.familyId,
                    best.score, assessedAt);
    }

    private static void setMatch(DaoSpamFamily dao,
                                 String accountUuid,
                                 long messageId,
                                 long familyId,
                                 SpamFamilyEngine.Score score,
                                 long assessedAt) {
        dao.setFamilyMatch(accountUuid, messageId, familyId,
                score.value, score.raw, score.text, score.structure,
                score.links, score.sender, assessedAt);
    }
}

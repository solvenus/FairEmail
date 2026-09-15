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

/** Restart-safe observer-only scan using exact sender-name + subject family identity. */
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

    public static void start(Context context) {
        if (context == null)
            return;
        schedule(context.getApplicationContext(), 0L);
    }

    public static void enqueue(Context context, String accountUuid, long familyId) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty() || familyId <= 0)
            return;
        final Context app = context.getApplicationContext();
        final String account = accountUuid.trim();
        executor.execute(() -> {
            try {
                DaoSpamFamily dao = SpamIntelligenceDB.getInstance(app).family();
                putTask(dao, account, familyId, System.currentTimeMillis());
            } catch (Throwable ex) {
                Log.e(ex);
            } finally {
                schedule(app, 0L);
            }
        });
    }

    public static void enqueueAllActive(Context context, String accountUuid) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty())
            return;
        final Context app = context.getApplicationContext();
        final String account = accountUuid.trim();
        executor.execute(() -> {
            try {
                DaoSpamFamily dao = SpamIntelligenceDB.getInstance(app).family();
                List<EntitySpamFamily> families = dao.getActiveFamilies(account);
                long generationBase = System.currentTimeMillis();
                if (families != null)
                    for (int i = 0; i < families.size(); i++) {
                        EntitySpamFamily family = families.get(i);
                        if (family == null || family.id == null)
                            continue;
                        putTask(dao, account, family.id, generationBase + i);
                    }
            } catch (Throwable ex) {
                Log.e(ex);
            } finally {
                schedule(app, 0L);
            }
        });
    }

    private static void putTask(DaoSpamFamily dao,
                                String account,
                                long familyId,
                                long requestedGeneration) {
        EntitySpamRescoreTask previous = dao.getRescoreTask(account, familyId);
        long generation = requestedGeneration;
        if (previous != null && generation <= previous.requested_at)
            generation = previous.requested_at + 1L;

        EntitySpamRescoreTask task = new EntitySpamRescoreTask();
        task.account_uuid = account;
        task.family_id = familyId;
        task.before_message_id = Long.MAX_VALUE;
        task.requested_at = generation;
        task.updated_at = System.currentTimeMillis();
        dao.putRescoreTask(task);
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

    private static boolean processOnePage(Context context) {
        SpamIntelligenceDB intelligence = SpamIntelligenceDB.getInstance(context);
        DaoSpamFamily dao = intelligence.family();
        EntitySpamRescoreTask task = dao.getNextRescoreTask();
        if (task == null)
            return false;

        EntitySpamFamily family = dao.getFamily(task.family_id);
        if (family == null || family.id == null || !family.active ||
                !task.account_uuid.equals(family.account_uuid)) {
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

                long assessedAt = System.currentTimeMillis();
                if (!SpamControlPolicy.exactFamilyDetection(context)) {
                    dao.clearFamilyMatch(task.account_uuid, delivery.message_id, assessedAt);
                    continue;
                }

                SpamFamilyIdentity.Identity identity =
                        SpamFamilyMessageAdapter.identityFromMessage(message);
                SpamFamilyStore.Match best = identity == null
                        ? null
                        : SpamFamilyStore.matchIdentity(
                                context, task.account_uuid, delivery.message_id, identity.key);

                if (best == null || best.familyId == null) {
                    dao.clearFamilyMatch(task.account_uuid, delivery.message_id, assessedAt);
                    continue;
                }

                if (best.familyId == task.family_id)
                    matches++;
                SpamFamilyEngine.Score score = best.score;
                dao.setFamilyMatch(task.account_uuid, delivery.message_id, best.familyId,
                        score.value, score.raw, score.text, score.structure,
                        score.links, score.sender, assessedAt);
            } catch (Throwable ex) {
                Log.w(ex);
            }
        }

        long now = System.currentTimeMillis();
        if (page.size() < PAGE_SIZE) {
            int deleted = dao.deleteRescoreTask(
                    task.account_uuid, task.family_id, task.requested_at);
            if (deleted > 0)
                Log.i("SpamFamily exact rescore complete account=" + task.account_uuid +
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
                        " family=" + task.family_id);
        }

        return dao.getNextRescoreTask() != null;
    }

    private static void finish(DaoSpamFamily dao, EntitySpamRescoreTask task) {
        dao.deleteRescoreTask(task.account_uuid, task.family_id, task.requested_at);
    }
}

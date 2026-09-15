package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;
import android.database.Cursor;

import androidx.sqlite.db.SimpleSQLiteQuery;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Incrementally imports existing FairEmail message metadata into the isolated
 * alias/spam-intelligence database.
 *
 * Folder placement is recorded as context only. Historical Junk/Spam placement
 * is deliberately not converted into an explicit spam label because folders can
 * contain mistakes and manually moved newsletters.
 */
public final class AliasBackfill {
    private static final String META_HIGH_WATER = "alias_backfill_high_water:v1";
    private static final int BATCH_SIZE = 100;

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile boolean completedThisProcess = false;

    private AliasBackfill() {
    }

    public static void schedule(Context context) {
        if (context == null || completedThisProcess || !running.compareAndSet(false, true))
            return;

        final Context app = context.getApplicationContext();
        Helper.getSerialExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Result result = runNow(app);
                    completedThisProcess = true;
                    Log.i("AliasBackfill" +
                            " scanned=" + result.scanned +
                            " observed=" + result.observed +
                            " from=" + result.startHighWater +
                            " through=" + result.endHighWater);
                } catch (Throwable ex) {
                    // Leave the checkpoint at the last completed batch. The next
                    // schedule/app start safely retries because the delivery ledger
                    // is idempotent.
                    Log.e(ex);
                } finally {
                    running.set(false);
                }
            }
        });
    }

    static Result runNow(Context context) throws Exception {
        DB mail = DB.getInstance(context);
        SpamIntelligenceDB intelligence = SpamIntelligenceDB.getInstance(context);
        DaoAlias aliases = intelligence.alias();
        SupportSQLiteDatabase readable = mail.getOpenHelper().getReadableDatabase();

        long highWater = valueOrZero(aliases.getMetaLong(META_HIGH_WATER));
        final long start = highWater;
        final long target = getMaxMessageId(readable);
        if (highWater >= target)
            return new Result(start, highWater, 0, 0);

        Map<Long, EntityAccount> accountCache = new HashMap<>();
        Map<Long, EntityFolder> folderCache = new HashMap<>();
        Set<Long> seenAccounts = new HashSet<>();
        long scanned = 0;
        long observed = 0;

        while (highWater < target) {
            long[] ids = getNextIds(readable, highWater, target, BATCH_SIZE);
            if (ids.length == 0) {
                // Gaps are expected after message deletion. Once no rows remain in
                // the frozen range, advancing to target is safe.
                highWater = target;
                putHighWater(aliases, highWater);
                break;
            }

            long batchEnd = highWater;
            for (long id : ids) {
                EntityMessage message = mail.message().getMessage(id);
                batchEnd = Math.max(batchEnd, id);
                scanned++;
                if (message == null || message.id == null || message.deliveredto == null ||
                        message.account == null || message.folder == null)
                    continue;

                EntityAccount account = accountCache.get(message.account);
                if (account == null) {
                    account = mail.account().getAccount(message.account);
                    if (account != null)
                        accountCache.put(message.account, account);
                }

                EntityFolder folder = folderCache.get(message.folder);
                if (folder == null) {
                    folder = mail.folder().getFolder(message.folder);
                    if (folder != null)
                        folderCache.put(message.folder, folder);
                }

                if (account == null || account.uuid == null || folder == null)
                    continue;

                AliasDomainAffinity.Evidence evidence = AliasDomainAffinity.fromMessage(context, message);
                boolean inserted = SpamAliasStore.observeDelivery(
                        context,
                        account.uuid,
                        message.id,
                        message.deliveredto,
                        message.received,
                        folder.type,
                        evidence.senderDomain,
                        evidence.unsubscribe);
                if (inserted)
                    observed++;

                AliasTrafficAnalyzer.Result traffic = AliasTrafficAnalyzer.assess(context, account, message);
                if (traffic.alias != null)
                    SpamAliasStore.setAssessment(
                            context, account.uuid, message.id, traffic.assessment);

                // Null identity is the only historical case requiring immediate
                // per-message repair. Existing identities can use sender_extra at
                // reply time after the final account-level synchronization below.
                if (message.identity == null)
                    AliasSenderManager.synchronizeForMessage(context, account, message);

                if (account.id != null)
                    seenAccounts.add(account.id);
            }

            // Checkpoint only after the whole batch completed. Any exception above
            // causes this batch to be replayed safely on the next run.
            highWater = batchEnd;
            putHighWater(aliases, highWater);
            Thread.yield();
        }

        // Populate the final exact sender-extra regex once per identity after the
        // registry contains all historical aliases discovered in this snapshot.
        for (Long accountId : seenAccounts) {
            EntityAccount account = accountCache.get(accountId);
            if (account == null)
                account = mail.account().getAccount(accountId);
            if (account == null)
                continue;
            List<EntityIdentity> identities = mail.identity().getSynchronizingIdentities(accountId);
            if (identities == null)
                continue;
            for (EntityIdentity identity : identities)
                AliasSenderManager.synchronizeIdentity(context, account, identity);
        }

        return new Result(start, highWater, scanned, observed);
    }

    private static long getMaxMessageId(SupportSQLiteDatabase db) {
        try (Cursor cursor = db.query("SELECT MAX(id) FROM message")) {
            if (!cursor.moveToFirst() || cursor.isNull(0))
                return 0;
            return cursor.getLong(0);
        }
    }

    private static long[] getNextIds(SupportSQLiteDatabase db,
                                     long after,
                                     long through,
                                     int limit) {
        SimpleSQLiteQuery query = new SimpleSQLiteQuery(
                "SELECT id FROM message" +
                        " WHERE id > ? AND id <= ?" +
                        " ORDER BY id LIMIT ?",
                new Object[]{after, through, limit});

        try (Cursor cursor = db.query(query)) {
            long[] result = new long[cursor.getCount()];
            int index = 0;
            while (cursor.moveToNext())
                result[index++] = cursor.getLong(0);
            return result;
        }
    }

    private static void putHighWater(DaoAlias dao, long value) {
        EntitySpamMeta meta = new EntitySpamMeta();
        meta.key = META_HIGH_WATER;
        meta.long_value = value;
        dao.putMeta(meta);
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0 : Math.max(0, value);
    }

    static final class Result {
        final long startHighWater;
        final long endHighWater;
        final long scanned;
        final long observed;

        Result(long startHighWater, long endHighWater, long scanned, long observed) {
            this.startHighWater = startHighWater;
            this.endHighWater = endHighWater;
            this.scanned = scanned;
            this.observed = observed;
        }
    }
}

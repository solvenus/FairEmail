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
import java.util.concurrent.Callable;

/** Atomic correction path for moving confirmed spam between learned families. */
public final class SpamFamilyReassigner {
    public enum Status {
        APPLIED,
        ALREADY_ASSIGNED,
        NOT_CONFIRMED_SPAM,
        FAMILY_MISSING,
        INCONSISTENT,
        FAILED
    }

    public static final class Result {
        public final Status status;
        public final Long oldFamilyId;
        public final long newFamilyId;
        public final boolean oldFamilyDeleted;

        private Result(Status status,
                       Long oldFamilyId,
                       long newFamilyId,
                       boolean oldFamilyDeleted) {
            this.status = status;
            this.oldFamilyId = oldFamilyId;
            this.newFamilyId = newFamilyId;
            this.oldFamilyDeleted = oldFamilyDeleted;
        }

        public boolean applied() {
            return status == Status.APPLIED || status == Status.ALREADY_ASSIGNED;
        }
    }

    private SpamFamilyReassigner() {
    }

    public static Result reassign(Context context,
                                  EntityAccount account,
                                  EntityMessage message,
                                  long targetFamilyId) {
        if (context == null || account == null || account.uuid == null ||
                message == null || message.id == null || targetFamilyId <= 0)
            return new Result(Status.INCONSISTENT, null, targetFamilyId, false);

        final Context app = context.getApplicationContext();
        final String accountUuid = account.uuid;
        final long messageId = message.id;
        final SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);

        Result result;
        try {
            result = db.runInTransaction(new Callable<Result>() {
                @Override
                public Result call() throws Exception {
                    DaoAlias aliasDao = db.alias();
                    DaoSpamFamily familyDao = db.family();

                    EntitySpamFamily target = familyDao.getFamily(targetFamilyId);
                    if (target == null || target.id == null ||
                            !accountUuid.equals(target.account_uuid))
                        return new Result(Status.FAMILY_MISSING, null,
                                targetFamilyId, false);

                    EntityAliasDelivery delivery = aliasDao.getDelivery(accountUuid, messageId);
                    if (delivery == null)
                        return new Result(Status.INCONSISTENT, null,
                                targetFamilyId, false);
                    if (delivery.label != EntityAliasDelivery.LABEL_SPAM ||
                            delivery.family_id == null)
                        return new Result(Status.NOT_CONFIRMED_SPAM,
                                delivery.family_id, targetFamilyId, false);

                    long oldFamilyId = delivery.family_id;
                    if (oldFamilyId == targetFamilyId) {
                        familyDao.deleteExclusion(accountUuid, messageId, targetFamilyId);
                        return new Result(Status.ALREADY_ASSIGNED,
                                oldFamilyId, targetFamilyId, false);
                    }

                    EntitySpamFamily source = familyDao.getFamily(oldFamilyId);
                    if (source == null || source.id == null ||
                            !accountUuid.equals(source.account_uuid))
                        return new Result(Status.INCONSISTENT,
                                oldFamilyId, targetFamilyId, false);

                    EntityAlias alias = aliasDao.getAlias(accountUuid, delivery.address);
                    if (alias == null)
                        return new Result(Status.INCONSISTENT,
                                oldFamilyId, targetFamilyId, false);

                    EntitySpamFamilyExemplar exemplar =
                            familyDao.getExemplar(accountUuid, messageId);
                    if (exemplar != null &&
                            (exemplar.id == null || exemplar.family_id != oldFamilyId))
                        return new Result(Status.INCONSISTENT,
                                oldFamilyId, targetFamilyId, false);

                    long now = System.currentTimeMillis();
                    if (!target.active)
                        requireOne(familyDao.setFamilyActive(targetFamilyId, true, now),
                                "reactivate target family");

                    if (exemplar != null)
                        requireOne(familyDao.moveExemplar(
                                        exemplar.id, targetFamilyId, now),
                                "move exemplar");

                    String counts = alias.family_counts;
                    counts = SpamAliasStore.incrementCounter(
                            counts, Long.toString(oldFamilyId), -1);
                    counts = SpamAliasStore.incrementCounter(
                            counts, Long.toString(targetFamilyId), 1);
                    requireOne(aliasDao.setFamilyCounts(
                                    accountUuid, delivery.address, counts),
                            "move alias family counter");

                    requireOne(aliasDao.setDeliveryLabel(
                                    accountUuid,
                                    messageId,
                                    EntityAliasDelivery.LABEL_SPAM,
                                    targetFamilyId),
                            "move delivery family");
                    familyDao.deleteExclusion(accountUuid, messageId, targetFamilyId);

                    pruneTarget(familyDao, targetFamilyId);

                    int targetConfirmed = familyDao.countConfirmedMembers(targetFamilyId);
                    requireOne(familyDao.setFamilyStats(
                                    targetFamilyId, targetConfirmed, now),
                            "update target family stats");

                    int sourceConfirmed = familyDao.countConfirmedMembers(oldFamilyId);
                    boolean deleted = false;
                    if (sourceConfirmed <= 0) {
                        familyDao.clearPredictionsForFamily(oldFamilyId, now);
                        familyDao.deleteRescoreTasksForFamily(oldFamilyId);
                        familyDao.deleteExemplars(oldFamilyId);
                        familyDao.deleteExclusionsForFamily(oldFamilyId);
                        requireOne(familyDao.deleteFamily(oldFamilyId),
                                "delete empty source family");
                        deleted = true;
                    } else
                        requireOne(familyDao.setFamilyStats(
                                        oldFamilyId, sourceConfirmed, now),
                                "update source family stats");

                    return new Result(Status.APPLIED,
                            oldFamilyId, targetFamilyId, deleted);
                }
            });
        } catch (Throwable ex) {
            Log.e(ex);
            return new Result(Status.FAILED, null, targetFamilyId, false);
        }

        if (result.applied()) {
            try {
                refreshPrediction(app, accountUuid, message);
                SpamFamilyRescorer.enqueue(app, accountUuid, targetFamilyId);
                if (result.status == Status.APPLIED &&
                        result.oldFamilyId != null &&
                        result.oldFamilyId != targetFamilyId) {
                    if (result.oldFamilyDeleted)
                        SpamFamilyRescorer.enqueueAllActive(app, accountUuid);
                    else
                        SpamFamilyRescorer.enqueue(app, accountUuid, result.oldFamilyId);
                }
            } catch (Throwable ex) {
                // The transaction is already committed. Post-commit observer
                // refresh must never turn a successful correction into a lie.
                Log.e(ex);
            }
        }
        return result;
    }

    private static void pruneTarget(DaoSpamFamily dao, long familyId) {
        List<EntitySpamFamilyExemplar> exemplars = dao.getExemplars(familyId);
        if (exemplars == null)
            return;
        for (int i = SpamFamilyEngine.DEFAULT_MAX_EXEMPLARS; i < exemplars.size(); i++) {
            EntitySpamFamilyExemplar old = exemplars.get(i);
            if (old != null && old.id != null)
                dao.deleteExemplar(old.id);
        }
    }

    private static void refreshPrediction(Context context,
                                          String accountUuid,
                                          EntityMessage message) {
        if (message == null || message.id == null)
            return;
        DaoSpamFamily dao = SpamIntelligenceDB.getInstance(context).family();
        long assessedAt = System.currentTimeMillis();
        SpamFamilyFingerprint fingerprint =
                SpamFamilyMessageAdapter.fromMessage(context, message);
        if (fingerprint == null) {
            dao.clearFamilyMatch(accountUuid, message.id, assessedAt);
            return;
        }

        SpamFamilyStore.Match match = SpamFamilyStore.matchForMessage(
                context, accountUuid, message.id, fingerprint);
        if (match.familyId == null) {
            dao.clearFamilyMatch(accountUuid, message.id, assessedAt);
            return;
        }

        SpamFamilyEngine.Score score = match.score;
        dao.setFamilyMatch(accountUuid, message.id, match.familyId,
                score.value, score.raw, score.text, score.structure,
                score.links, score.sender, assessedAt);
    }

    private static void requireOne(int changed, String operation) {
        if (changed != 1)
            throw new IllegalStateException(operation + " changed=" + changed);
    }
}

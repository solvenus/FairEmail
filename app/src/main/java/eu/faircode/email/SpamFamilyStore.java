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

/** Persistent counterpart of SpamFamilyEngine.Model. */
public final class SpamFamilyStore {
    private static final int MIN_LEARN_EVIDENCE = 12;

    private SpamFamilyStore() {
    }

    public static final class Match {
        public final Long familyId;
        public final SpamFamilyEngine.Score score;
        public final boolean spamLike;

        private Match(Long familyId, SpamFamilyEngine.Score score, boolean spamLike) {
            this.familyId = familyId;
            this.score = score;
            this.spamLike = spamLike;
        }
    }

    public static final class LearnResult {
        public final Long familyId;
        public final boolean created;
        public final boolean learned;
        public final SpamFamilyEngine.Score previousBest;

        private LearnResult(Long familyId, boolean created, boolean learned,
                            SpamFamilyEngine.Score previousBest) {
            this.familyId = familyId;
            this.created = created;
            this.learned = learned;
            this.previousBest = previousBest;
        }
    }

    public static synchronized Match match(Context context,
                                           String accountUuid,
                                           SpamFamilyFingerprint fingerprint) {
        if (context == null || accountUuid == null || fingerprint == null)
            return new Match(null, SpamFamilyEngine.Score.ZERO, false);

        Best best = findBest(SpamIntelligenceDB.getInstance(context).family(),
                accountUuid, fingerprint);
        return new Match(best.familyId, best.score,
                best.familyId != null && best.score.value >= SpamFamilyEngine.DEFAULT_DETECT_THRESHOLD);
    }

    public static synchronized LearnResult learnSpam(Context context,
                                                     String accountUuid,
                                                     long messageId,
                                                     SpamFamilyFingerprint fingerprint) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty() ||
                messageId <= 0 || fingerprint == null ||
                fingerprint.evidenceCount() < MIN_LEARN_EVIDENCE)
            return new LearnResult(null, false, false, SpamFamilyEngine.Score.ZERO);

        DaoSpamFamily dao = SpamIntelligenceDB.getInstance(context).family();

        EntitySpamFamilyExemplar existing = dao.getExemplar(accountUuid, messageId);
        if (existing != null) {
            EntitySpamFamily family = dao.getFamily(existing.family_id);
            if (family != null && !Boolean.TRUE.equals(family.active))
                dao.setFamilyActive(family.id, true, System.currentTimeMillis());
            return new LearnResult(existing.family_id, false, false,
                    scoreSafely(fingerprint, existing.fingerprint));
        }

        Best best = findBest(dao, accountUuid, fingerprint);
        long now = System.currentTimeMillis();
        long familyId;
        boolean created;

        if (best.familyId != null &&
                best.score.value >= SpamFamilyEngine.DEFAULT_JOIN_THRESHOLD) {
            familyId = best.familyId;
            created = false;
        } else {
            EntitySpamFamily family = new EntitySpamFamily();
            family.account_uuid = accountUuid;
            family.created_at = now;
            family.updated_at = now;
            family.confirmed_count = 0;
            family.active = true;
            familyId = dao.insertFamily(family);
            created = true;
        }

        EntitySpamFamilyExemplar exemplar = new EntitySpamFamilyExemplar();
        exemplar.family_id = familyId;
        exemplar.account_uuid = accountUuid;
        exemplar.source_message_id = messageId;
        exemplar.fingerprint = fingerprint.toBytes();
        exemplar.created_at = now;

        long inserted = dao.insertExemplar(exemplar);
        if (inserted == -1) {
            EntitySpamFamilyExemplar raced = dao.getExemplar(accountUuid, messageId);
            if (created && dao.countExemplars(familyId) == 0)
                dao.deleteFamily(familyId);
            return new LearnResult(raced == null ? null : raced.family_id,
                    false, false, best.score);
        }

        prune(dao, familyId);
        refreshFamilyStats(dao, familyId, now);
        return new LearnResult(familyId, created, true, best.score);
    }

    public static synchronized Long unlearnMessage(Context context,
                                                   String accountUuid,
                                                   long messageId) {
        if (context == null || accountUuid == null || messageId <= 0)
            return null;

        DaoSpamFamily dao = SpamIntelligenceDB.getInstance(context).family();
        EntitySpamFamilyExemplar exemplar = dao.getExemplar(accountUuid, messageId);
        if (exemplar == null)
            return null;

        long familyId = exemplar.family_id;
        dao.deleteExemplarByMessage(accountUuid, messageId);
        refreshFamilyStats(dao, familyId, System.currentTimeMillis());
        return familyId;
    }

    private static Best findBest(DaoSpamFamily dao,
                                 String accountUuid,
                                 SpamFamilyFingerprint fingerprint) {
        Long winner = null;
        SpamFamilyEngine.Score best = SpamFamilyEngine.Score.ZERO;
        List<EntitySpamFamily> families = dao.getActiveFamilies(accountUuid);
        if (families != null)
            for (EntitySpamFamily family : families) {
                if (family == null || family.id == null)
                    continue;
                List<EntitySpamFamilyExemplar> exemplars = dao.getExemplars(family.id);
                if (exemplars == null)
                    continue;
                for (EntitySpamFamilyExemplar exemplar : exemplars) {
                    if (exemplar == null || exemplar.fingerprint == null)
                        continue;
                    SpamFamilyEngine.Score score = scoreSafely(fingerprint, exemplar.fingerprint);
                    if (score.value > best.value) {
                        best = score;
                        winner = family.id;
                    }
                }
            }
        return new Best(winner, best);
    }

    private static SpamFamilyEngine.Score scoreSafely(SpamFamilyFingerprint fingerprint,
                                                       byte[] encoded) {
        try {
            return SpamFamilyEngine.compare(fingerprint,
                    SpamFamilyFingerprint.fromBytes(encoded));
        } catch (Throwable ex) {
            // One corrupt exemplar must not disable all family matching.
            Log.w(ex);
            return SpamFamilyEngine.Score.ZERO;
        }
    }

    private static void prune(DaoSpamFamily dao, long familyId) {
        List<EntitySpamFamilyExemplar> exemplars = dao.getExemplars(familyId);
        if (exemplars == null)
            return;
        for (int i = SpamFamilyEngine.DEFAULT_MAX_EXEMPLARS; i < exemplars.size(); i++) {
            EntitySpamFamilyExemplar old = exemplars.get(i);
            if (old != null && old.id != null)
                dao.deleteExemplar(old.id);
        }
    }

    private static void refreshFamilyStats(DaoSpamFamily dao, long familyId, long now) {
        int count = dao.countExemplars(familyId);
        if (count <= 0)
            dao.deleteFamily(familyId);
        else
            dao.setFamilyStats(familyId, count, now);
    }

    private static final class Best {
        final Long familyId;
        final SpamFamilyEngine.Score score;

        Best(Long familyId, SpamFamilyEngine.Score score) {
            this.familyId = familyId;
            this.score = score;
        }
    }
}

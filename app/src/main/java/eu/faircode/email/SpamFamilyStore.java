package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /** Immutable in-memory view of one family's bounded exemplar set. */
    public static final class FamilyMatcher {
        public final long familyId;
        private final List<SpamFamilyFingerprint> exemplars;

        private FamilyMatcher(long familyId, List<SpamFamilyFingerprint> exemplars) {
            this.familyId = familyId;
            this.exemplars = exemplars;
        }

        public SpamFamilyEngine.Score score(SpamFamilyFingerprint fingerprint) {
            if (fingerprint == null)
                return SpamFamilyEngine.Score.ZERO;
            SpamFamilyEngine.Score best = SpamFamilyEngine.Score.ZERO;
            for (SpamFamilyFingerprint exemplar : exemplars) {
                SpamFamilyEngine.Score score = SpamFamilyEngine.compare(fingerprint, exemplar);
                if (score.value > best.value)
                    best = score;
            }
            return best;
        }

        public Match match(SpamFamilyFingerprint fingerprint) {
            SpamFamilyEngine.Score score = score(fingerprint);
            return new Match(familyId, score,
                    score.value >= SpamFamilyEngine.DEFAULT_DETECT_THRESHOLD);
        }

        public int exemplarCount() {
            return exemplars.size();
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
        return matchInternal(context, accountUuid, 0L, fingerprint);
    }

    /** Match while honoring explicit per-message family exclusions. */
    public static synchronized Match matchForMessage(Context context,
                                                     String accountUuid,
                                                     long messageId,
                                                     SpamFamilyFingerprint fingerprint) {
        return matchInternal(context, accountUuid, messageId, fingerprint);
    }

    private static Match matchInternal(Context context,
                                       String accountUuid,
                                       long messageId,
                                       SpamFamilyFingerprint fingerprint) {
        if (context == null || accountUuid == null || fingerprint == null)
            return new Match(null, SpamFamilyEngine.Score.ZERO, false);

        DaoSpamFamily dao = SpamIntelligenceDB.getInstance(context).family();
        Set<Long> excluded = excludedFamilies(dao, accountUuid, messageId);
        Best best = findBest(dao, accountUuid, fingerprint, excluded);
        return new Match(best.familyId, best.score,
                best.familyId != null && best.score.value >= SpamFamilyEngine.DEFAULT_DETECT_THRESHOLD);
    }

    /** Load and decode one family once for an entire retroactive scan. */
    public static synchronized FamilyMatcher loadMatcher(Context context,
                                                         String accountUuid,
                                                         long familyId) {
        if (context == null || accountUuid == null || familyId <= 0)
            return null;

        DaoSpamFamily dao = SpamIntelligenceDB.getInstance(context).family();
        EntitySpamFamily family = dao.getFamily(familyId);
        if (family == null || family.id == null || !family.active ||
                !accountUuid.equals(family.account_uuid))
            return null;

        List<EntitySpamFamilyExemplar> stored = dao.getExemplars(familyId);
        if (stored == null || stored.isEmpty())
            return null;

        List<SpamFamilyFingerprint> decoded = new ArrayList<>();
        for (EntitySpamFamilyExemplar exemplar : stored) {
            if (exemplar == null || exemplar.fingerprint == null)
                continue;
            try {
                decoded.add(SpamFamilyFingerprint.fromBytes(exemplar.fingerprint));
            } catch (Throwable ex) {
                Log.w(ex);
            }
        }
        return decoded.isEmpty() ? null : new FamilyMatcher(familyId, decoded);
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
            if (family != null && !family.active)
                dao.setFamilyActive(family.id, true, System.currentTimeMillis());
            return new LearnResult(existing.family_id, false, false,
                    scoreSafely(fingerprint, existing.fingerprint));
        }

        Best best = findBest(dao, accountUuid, fingerprint,
                excludedFamilies(dao, accountUuid, messageId));
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

        return insertExemplar(dao, accountUuid, messageId, fingerprint,
                familyId, created, best.score, now);
    }

    /**
     * Explicit user assignment to an existing family. Unlike automatic joining,
     * this never substitutes a different family merely because it scores higher.
     * Explicit confirmation also cancels an earlier exclusion of that exact pair.
     */
    public static synchronized LearnResult learnSpamIntoFamily(Context context,
                                                               String accountUuid,
                                                               long messageId,
                                                               SpamFamilyFingerprint fingerprint,
                                                               long requestedFamilyId) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty() ||
                messageId <= 0 || requestedFamilyId <= 0)
            return new LearnResult(null, false, false, SpamFamilyEngine.Score.ZERO);

        DaoSpamFamily dao = SpamIntelligenceDB.getInstance(context).family();
        EntitySpamFamily requested = dao.getFamily(requestedFamilyId);
        if (requested == null || requested.id == null ||
                !accountUuid.equals(requested.account_uuid))
            return new LearnResult(null, false, false, SpamFamilyEngine.Score.ZERO);

        if (!requested.active)
            dao.setFamilyActive(requestedFamilyId, true, System.currentTimeMillis());

        EntitySpamFamilyExemplar existing = dao.getExemplar(accountUuid, messageId);
        if (existing != null) {
            SpamFamilyEngine.Score score = fingerprint == null
                    ? SpamFamilyEngine.Score.ZERO
                    : scoreSafely(fingerprint, existing.fingerprint);
            if (existing.family_id == requestedFamilyId)
                dao.deleteExclusion(accountUuid, messageId, requestedFamilyId);
            // A message already exemplifying another family is not silently
            // moved here. Family reassignment deserves an explicit operation.
            return new LearnResult(existing.family_id, false, false, score);
        }

        if (fingerprint == null || fingerprint.evidenceCount() < MIN_LEARN_EVIDENCE)
            return new LearnResult(requestedFamilyId, false, false,
                    SpamFamilyEngine.Score.ZERO);

        SpamFamilyEngine.Score previous = bestInFamily(dao, requestedFamilyId, fingerprint);
        dao.deleteExclusion(accountUuid, messageId, requestedFamilyId);
        return insertExemplar(dao, accountUuid, messageId, fingerprint,
                requestedFamilyId, false, previous, System.currentTimeMillis());
    }

    public static synchronized Long unlearnMessage(Context context,
                                                   String accountUuid,
                                                   long messageId,
                                                   Long familyHint) {
        if (context == null || accountUuid == null || messageId <= 0)
            return familyHint;

        DaoSpamFamily dao = SpamIntelligenceDB.getInstance(context).family();
        EntitySpamFamilyExemplar exemplar = dao.getExemplar(accountUuid, messageId);
        Long familyId = exemplar == null ? familyHint : exemplar.family_id;
        if (exemplar != null)
            dao.deleteExemplarByMessage(accountUuid, messageId);
        if (familyId != null)
            reconcileFamily(dao, familyId, System.currentTimeMillis());
        return familyId;
    }

    public static synchronized void reconcileFamily(Context context, Long familyId) {
        if (context == null || familyId == null)
            return;
        reconcileFamily(SpamIntelligenceDB.getInstance(context).family(),
                familyId, System.currentTimeMillis());
    }

    private static LearnResult insertExemplar(DaoSpamFamily dao,
                                              String accountUuid,
                                              long messageId,
                                              SpamFamilyFingerprint fingerprint,
                                              long familyId,
                                              boolean created,
                                              SpamFamilyEngine.Score previous,
                                              long now) {
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
                    false, false, previous);
        }

        prune(dao, familyId);

        // The ledger label is written immediately after this call. Keep a new
        // family alive provisionally; reconcileFamily() replaces this value with
        // the real lifetime confirmed count once the label commit succeeds.
        int confirmed = dao.countConfirmedMembers(familyId);
        dao.setFamilyStats(familyId, Math.max(1, confirmed), now);
        return new LearnResult(familyId, created, true, previous);
    }

    private static Best findBest(DaoSpamFamily dao,
                                 String accountUuid,
                                 SpamFamilyFingerprint fingerprint,
                                 Set<Long> excluded) {
        Long winner = null;
        SpamFamilyEngine.Score best = SpamFamilyEngine.Score.ZERO;
        List<EntitySpamFamily> families = dao.getActiveFamilies(accountUuid);
        if (families != null)
            for (EntitySpamFamily family : families) {
                if (family == null || family.id == null ||
                        (excluded != null && excluded.contains(family.id)))
                    continue;
                SpamFamilyEngine.Score score = bestInFamily(dao, family.id, fingerprint);
                if (score.value > best.value) {
                    best = score;
                    winner = family.id;
                }
            }
        return new Best(winner, best);
    }

    private static Set<Long> excludedFamilies(DaoSpamFamily dao,
                                              String accountUuid,
                                              long messageId) {
        if (messageId <= 0)
            return null;
        List<Long> ids = dao.getExcludedFamilyIds(accountUuid, messageId);
        if (ids == null || ids.isEmpty())
            return null;
        return new HashSet<>(ids);
    }

    private static SpamFamilyEngine.Score bestInFamily(DaoSpamFamily dao,
                                                        long familyId,
                                                        SpamFamilyFingerprint fingerprint) {
        SpamFamilyEngine.Score best = SpamFamilyEngine.Score.ZERO;
        List<EntitySpamFamilyExemplar> exemplars = dao.getExemplars(familyId);
        if (exemplars == null)
            return best;
        for (EntitySpamFamilyExemplar exemplar : exemplars) {
            if (exemplar == null || exemplar.fingerprint == null)
                continue;
            SpamFamilyEngine.Score score = scoreSafely(fingerprint, exemplar.fingerprint);
            if (score.value > best.value)
                best = score;
        }
        return best;
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

    private static void reconcileFamily(DaoSpamFamily dao, long familyId, long now) {
        int confirmed = dao.countConfirmedMembers(familyId);
        if (confirmed <= 0) {
            // Predictions/exclusions are evidence, not foreign keys. Clear them
            // before deleting the model so UI can never point at a dead family.
            dao.clearPredictionsForFamily(familyId, now);
            dao.deleteExclusionsForFamily(familyId);
            dao.deleteRescoreTasksForFamily(familyId);
            dao.deleteExemplars(familyId);
            dao.deleteFamily(familyId);
        } else
            dao.setFamilyStats(familyId, confirmed, now);
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

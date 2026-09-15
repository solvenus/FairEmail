/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package eu.faircode.email;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Online, explainable grouping and matching of fingerprints learned from known spam. */
public final class SpamFamilyEngine {
    public static final double DEFAULT_JOIN_THRESHOLD = 0.50;
    public static final double DEFAULT_DETECT_THRESHOLD = 0.56;
    public static final int DEFAULT_MAX_EXEMPLARS = 32;

    private SpamFamilyEngine() {
    }

    public static Score compare(SpamFamilyFingerprint a, SpamFamilyFingerprint b) {
        double text = jaccard(a.text, b.text);
        double structure = jaccard(a.structure, b.structure);
        double links = jaccard(a.links, b.links);
        double sender = jaccard(a.sender, b.sender);

        double balanced = weighted4(
                text, a.text, b.text, 0.48,
                structure, a.structure, b.structure, 0.30,
                links, a.links, b.links, 0.16,
                sender, a.sender, b.sender, 0.06);
        double template = weighted3(
                structure, a.structure, b.structure, 0.60,
                links, a.links, b.links, 0.25,
                text, a.text, b.text, 0.15);
        double content = weighted3(
                text, a.text, b.text, 0.72,
                links, a.links, b.links, 0.18,
                structure, a.structure, b.structure, 0.10);
        double infrastructure = weighted3(
                links, a.links, b.links, 0.58,
                structure, a.structure, b.structure, 0.27,
                sender, a.sender, b.sender, 0.15);

        double raw = Math.max(balanced, Math.max(template, Math.max(content, infrastructure)));
        int minEvidence = Math.min(a.evidenceCount(), b.evidenceCount());
        double evidence = Math.min(1.0, Math.sqrt(minEvidence / 30.0));
        return new Score(raw * (0.72 + 0.28 * evidence), raw, evidence,
                text, structure, links, sender);
    }

    private static double weighted3(
            double s1, Set<Long> a1, Set<Long> b1, double w1,
            double s2, Set<Long> a2, Set<Long> b2, double w2,
            double s3, Set<Long> a3, Set<Long> b3, double w3) {
        double sum = 0, weights = 0;
        if (!a1.isEmpty() && !b1.isEmpty()) { sum += s1 * w1; weights += w1; }
        if (!a2.isEmpty() && !b2.isEmpty()) { sum += s2 * w2; weights += w2; }
        if (!a3.isEmpty() && !b3.isEmpty()) { sum += s3 * w3; weights += w3; }
        return weights == 0 ? 0 : sum / weights;
    }

    private static double weighted4(
            double s1, Set<Long> a1, Set<Long> b1, double w1,
            double s2, Set<Long> a2, Set<Long> b2, double w2,
            double s3, Set<Long> a3, Set<Long> b3, double w3,
            double s4, Set<Long> a4, Set<Long> b4, double w4) {
        double sum = 0, weights = 0;
        if (!a1.isEmpty() && !b1.isEmpty()) { sum += s1 * w1; weights += w1; }
        if (!a2.isEmpty() && !b2.isEmpty()) { sum += s2 * w2; weights += w2; }
        if (!a3.isEmpty() && !b3.isEmpty()) { sum += s3 * w3; weights += w3; }
        if (!a4.isEmpty() && !b4.isEmpty()) { sum += s4 * w4; weights += w4; }
        return weights == 0 ? 0 : sum / weights;
    }

    private static double jaccard(Set<Long> a, Set<Long> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<Long> small = a.size() <= b.size() ? a : b;
        Set<Long> large = a.size() <= b.size() ? b : a;
        int intersection = 0;
        for (Long value : small) if (large.contains(value)) intersection++;
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0 : (double) intersection / union;
    }

    public static final class Model {
        private final List<Family> families = new ArrayList<>();
        private final double joinThreshold;
        private final double detectThreshold;
        private final int maxExemplars;
        private long nextFamilyId = 1;

        public Model() {
            this(DEFAULT_JOIN_THRESHOLD, DEFAULT_DETECT_THRESHOLD, DEFAULT_MAX_EXEMPLARS);
        }

        public Model(double joinThreshold, double detectThreshold, int maxExemplars) {
            if (joinThreshold < 0 || joinThreshold > 1) throw new IllegalArgumentException("joinThreshold");
            if (detectThreshold < 0 || detectThreshold > 1) throw new IllegalArgumentException("detectThreshold");
            if (maxExemplars < 1) throw new IllegalArgumentException("maxExemplars");
            this.joinThreshold = joinThreshold;
            this.detectThreshold = detectThreshold;
            this.maxExemplars = maxExemplars;
        }

        public Assignment learnSpam(SpamFamilyFingerprint fingerprint) {
            Match best = bestMatch(fingerprint);
            Family family;
            boolean created;
            if (best.family != null && best.score.value >= joinThreshold) {
                family = best.family;
                created = false;
            } else {
                family = new Family(nextFamilyId++);
                families.add(family);
                best = Match.none();
                created = true;
            }
            family.add(fingerprint, maxExemplars);
            return new Assignment(family.id, created, best.score);
        }

        public Match match(SpamFamilyFingerprint fingerprint) {
            Match best = bestMatch(fingerprint);
            if (best.family == null) return best;
            return new Match(best.family, best.score, best.score.value >= detectThreshold);
        }

        public int familyCount() {
            return families.size();
        }

        public List<Long> familyIds() {
            List<Long> ids = new ArrayList<>();
            for (Family family : families) ids.add(family.id);
            return Collections.unmodifiableList(ids);
        }

        private Match bestMatch(SpamFamilyFingerprint fingerprint) {
            Family winner = null;
            Score score = Score.ZERO;
            for (Family family : families) {
                Score candidate = family.score(fingerprint);
                if (candidate.value > score.value) {
                    winner = family;
                    score = candidate;
                }
            }
            return new Match(winner, score, false);
        }
    }

    public static final class Score {
        static final Score ZERO = new Score(0, 0, 0, 0, 0, 0, 0);
        public final double value, raw, evidence, text, structure, links, sender;

        private Score(double value, double raw, double evidence, double text,
                      double structure, double links, double sender) {
            this.value = value;
            this.raw = raw;
            this.evidence = evidence;
            this.text = text;
            this.structure = structure;
            this.links = links;
            this.sender = sender;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "score=%.3f raw=%.3f evidence=%.3f text=%.3f html=%.3f links=%.3f sender=%.3f",
                    value, raw, evidence, text, structure, links, sender);
        }
    }

    public static final class Match {
        public final Long familyId;
        public final Score score;
        public final boolean spamLike;
        private final Family family;

        private Match(Family family, Score score, boolean spamLike) {
            this.family = family;
            this.familyId = family == null ? null : family.id;
            this.score = score;
            this.spamLike = spamLike;
        }

        static Match none() {
            return new Match(null, Score.ZERO, false);
        }
    }

    public static final class Assignment {
        public final long familyId;
        public final boolean created;
        public final Score previousBestScore;

        private Assignment(long familyId, boolean created, Score previousBestScore) {
            this.familyId = familyId;
            this.created = created;
            this.previousBestScore = previousBestScore;
        }
    }

    private static final class Family {
        final long id;
        final ArrayDeque<SpamFamilyFingerprint> exemplars = new ArrayDeque<>();

        Family(long id) {
            this.id = id;
        }

        void add(SpamFamilyFingerprint fingerprint, int max) {
            exemplars.addLast(fingerprint);
            while (exemplars.size() > max) exemplars.removeFirst();
        }

        Score score(SpamFamilyFingerprint fingerprint) {
            Score best = Score.ZERO;
            for (SpamFamilyFingerprint exemplar : exemplars) {
                Score score = compare(fingerprint, exemplar);
                if (score.value > best.value) best = score;
            }
            return best;
        }
    }
}

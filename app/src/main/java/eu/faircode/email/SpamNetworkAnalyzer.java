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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Broader infrastructure/template relationships between exact spam families.
 * Family identity is never changed by this analyzer.
 */
public final class SpamNetworkAnalyzer {
    private static final double NETWORK_THRESHOLD = 0.50;

    private SpamNetworkAnalyzer() {
    }

    public static List<Network> analyze(Context context, String accountUuid) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty())
            return Collections.emptyList();

        DaoSpamFamily dao = SpamIntelligenceDB.getInstance(context).family();
        List<EntitySpamFamily> families = dao.getActiveFamilies(accountUuid.trim());
        if (families == null || families.size() < 2)
            return Collections.emptyList();

        List<FamilyData> data = new ArrayList<>();
        for (EntitySpamFamily family : families) {
            if (family == null || family.id == null)
                continue;
            List<EntitySpamFamilyExemplar> stored = dao.getExemplars(family.id);
            List<SpamFamilyFingerprint> fingerprints = new ArrayList<>();
            if (stored != null)
                for (EntitySpamFamilyExemplar exemplar : stored) {
                    if (exemplar == null || exemplar.fingerprint == null)
                        continue;
                    try {
                        fingerprints.add(SpamFamilyFingerprint.fromBytes(exemplar.fingerprint));
                    } catch (Throwable ex) {
                        Log.w(ex);
                    }
                }
            if (!fingerprints.isEmpty())
                data.add(new FamilyData(family.id, fingerprints));
        }
        if (data.size() < 2)
            return Collections.emptyList();

        Map<Long, Set<Long>> graph = new HashMap<>();
        Map<String, Double> edgeScores = new HashMap<>();
        for (int i = 0; i < data.size(); i++)
            for (int j = i + 1; j < data.size(); j++) {
                FamilyData a = data.get(i);
                FamilyData b = data.get(j);
                double score = networkScore(a.fingerprints, b.fingerprints);
                if (score < NETWORK_THRESHOLD)
                    continue;
                graph.computeIfAbsent(a.familyId, ignored -> new HashSet<>()).add(b.familyId);
                graph.computeIfAbsent(b.familyId, ignored -> new HashSet<>()).add(a.familyId);
                edgeScores.put(edgeKey(a.familyId, b.familyId), score);
            }

        Set<Long> visited = new HashSet<>();
        List<Network> result = new ArrayList<>();
        for (Long start : graph.keySet()) {
            if (!visited.add(start))
                continue;
            List<Long> pending = new ArrayList<>();
            List<Long> members = new ArrayList<>();
            pending.add(start);
            for (int p = 0; p < pending.size(); p++) {
                Long current = pending.get(p);
                members.add(current);
                Set<Long> neighbors = graph.get(current);
                if (neighbors == null)
                    continue;
                for (Long neighbor : neighbors)
                    if (visited.add(neighbor))
                        pending.add(neighbor);
            }
            Collections.sort(members);
            double strongest = 0.0;
            for (int i = 0; i < members.size(); i++)
                for (int j = i + 1; j < members.size(); j++) {
                    Double edge = edgeScores.get(edgeKey(members.get(i), members.get(j)));
                    if (edge != null)
                        strongest = Math.max(strongest, edge);
                }
            if (members.size() >= 2)
                result.add(new Network(members, strongest));
        }

        result.sort(Comparator
                .comparingInt((Network n) -> n.familyIds.size()).reversed()
                .thenComparingDouble(n -> -n.strongestScore));
        return Collections.unmodifiableList(result);
    }

    private static double networkScore(List<SpamFamilyFingerprint> a,
                                       List<SpamFamilyFingerprint> b) {
        double best = 0.0;
        for (SpamFamilyFingerprint left : a)
            for (SpamFamilyFingerprint right : b) {
                SpamFamilyEngine.Score score = SpamFamilyEngine.compare(left, right);
                double infrastructure =
                        0.50 * score.structure +
                        0.35 * score.links +
                        0.15 * score.sender;
                best = Math.max(best, infrastructure);
            }
        return best;
    }

    private static String edgeKey(long a, long b) {
        return Math.min(a, b) + ":" + Math.max(a, b);
    }

    private static final class FamilyData {
        final long familyId;
        final List<SpamFamilyFingerprint> fingerprints;

        FamilyData(long familyId, List<SpamFamilyFingerprint> fingerprints) {
            this.familyId = familyId;
            this.fingerprints = fingerprints;
        }
    }

    public static final class Network {
        public final List<Long> familyIds;
        public final double strongestScore;

        Network(List<Long> familyIds, double strongestScore) {
            this.familyIds = Collections.unmodifiableList(new ArrayList<>(familyIds));
            this.strongestScore = strongestScore;
        }
    }
}

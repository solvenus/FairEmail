package eu.faircode.email;

/** Pure decision policy for updating observer-only family predictions. */
public final class SpamFamilyPredictionPolicy {
    private static final double EPSILON = 1e-9;

    public enum Action {
        KEEP_CURRENT,
        USE_CANDIDATE,
        RECOMPUTE_ALL
    }

    private SpamFamilyPredictionPolicy() {
    }

    public static Action decide(Long currentFamilyId,
                                Double currentScore,
                                long candidateFamilyId,
                                double candidateScore) {
        if (candidateFamilyId <= 0 || !Double.isFinite(candidateScore))
            return Action.KEEP_CURRENT;

        double current = currentScore == null || !Double.isFinite(currentScore)
                ? -1.0 : currentScore;
        boolean same = currentFamilyId != null && currentFamilyId == candidateFamilyId;

        if (same && candidateScore + EPSILON < current)
            return Action.RECOMPUTE_ALL;
        if (same)
            return Action.USE_CANDIDATE;
        if (candidateScore > current + EPSILON)
            return Action.USE_CANDIDATE;
        return Action.KEEP_CURRENT;
    }
}

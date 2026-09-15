package eu.faircode.email;

public final class SpamFamilyPredictionPolicyLab {
    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    public static void main(String[] args) {
        require(SpamFamilyPredictionPolicy.decide(null, null, 7L, 0.20) ==
                        SpamFamilyPredictionPolicy.Action.USE_CANDIDATE,
                "first observed family should become current even below spam threshold");

        require(SpamFamilyPredictionPolicy.decide(7L, 0.80, 9L, 0.79) ==
                        SpamFamilyPredictionPolicy.Action.KEEP_CURRENT,
                "weaker new family must not steal prediction ownership");

        require(SpamFamilyPredictionPolicy.decide(7L, 0.80, 9L, 0.81) ==
                        SpamFamilyPredictionPolicy.Action.USE_CANDIDATE,
                "stronger family must be able to take ownership");

        require(SpamFamilyPredictionPolicy.decide(7L, 0.80, 7L, 0.80) ==
                        SpamFamilyPredictionPolicy.Action.USE_CANDIDATE,
                "same family should refresh component evidence when unchanged");

        require(SpamFamilyPredictionPolicy.decide(7L, 0.80, 7L, 0.60) ==
                        SpamFamilyPredictionPolicy.Action.RECOMPUTE_ALL,
                "weakened current winner must reopen competition across families");

        require(SpamFamilyPredictionPolicy.decide(7L, 0.80, 9L, Double.NaN) ==
                        SpamFamilyPredictionPolicy.Action.KEEP_CURRENT,
                "non-finite candidate score must fail closed");

        require(SpamFamilyPredictionPolicy.decide(7L, Double.NaN, 9L, 0.10) ==
                        SpamFamilyPredictionPolicy.Action.USE_CANDIDATE,
                "corrupt current score must not permanently lock prediction ownership");

        System.out.println("PASS SpamFamilyPredictionPolicyLab");
    }
}

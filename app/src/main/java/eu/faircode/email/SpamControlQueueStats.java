package eu.faircode.email;

import android.content.Context;

/**
 * Small source of truth for queue totals shown by Spam Control.
 * The UI may page the actual queue, but the total must never be inferred from
 * the current page size.
 */
public final class SpamControlQueueStats {
    private SpamControlQueueStats() {
    }

    public static int countReview(Context context, String accountUuid) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty())
            return 0;
        boolean includeReviewed = !SpamControlPolicy.hideReviewed(context);
        return Math.max(0, SpamIntelligenceDB.getInstance(context)
                .message().countReviewQueue(accountUuid.trim(), includeReviewed));
    }
}

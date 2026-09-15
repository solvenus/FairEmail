package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure-Java policy for the lifecycle AFTER spam and alias compromise have been
 * evaluated as separate truths.
 *
 * This policy computes advisory lifecycle/readiness, not authorization. The
 * human can explicitly veto replacement/readiness advice and request SMTP burn.
 * A spam hit alone is never proof that the alias itself is compromised.
 */
public final class AliasBurnPolicy {
    private AliasBurnPolicy() {
    }

    public enum ServerState {
        NONE,
        REJECT_PENDING,
        REJECT_VERIFIED,
        FAILED,
        RESTORE_PENDING
    }

    public enum Verdict {
        HEALTHY,
        REVIEW_COMPROMISE,
        COMPROMISED,
        ROTATE_FIRST,
        VERIFY_REPLACEMENT,
        READY_TO_BURN,
        SERVER_PENDING,
        SMTP_DEAD,
        SERVER_FAILED
    }

    public static final class Input {
        public int spamHits;
        public int hamHits;
        public boolean aliasCompromised;
        public boolean compromiseNeedsReview;
        public boolean serviceDomainKnown;
        public boolean trustedDomainsConfigured;
        public boolean replacementConfigured;
        public boolean replacementVerified;
        public ServerState serverState = ServerState.NONE;
    }

    public static final class Result {
        public final Verdict verdict;
        public final boolean compromised;
        public final boolean burnAllowed;
        public final List<String> reasons;

        private Result(Verdict verdict,
                       boolean compromised,
                       boolean burnAllowed,
                       List<String> reasons) {
            this.verdict = verdict;
            this.compromised = compromised;
            this.burnAllowed = burnAllowed;
            this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
        }

        @Override
        public String toString() {
            return verdict + " compromised=" + compromised +
                    " burnAllowed=" + burnAllowed +
                    " reasons=" + reasons;
        }
    }

    public static Result evaluate(Input input) {
        if (input == null)
            throw new IllegalArgumentException("input");

        List<String> reasons = new ArrayList<>();
        int spam = Math.max(0, input.spamHits);
        int ham = Math.max(0, input.hamHits);
        boolean compromised = input.aliasCompromised;

        if (input.serverState == ServerState.REJECT_VERIFIED) {
            reasons.add("smtp-rejection-verified");
            return new Result(Verdict.SMTP_DEAD, compromised, false, reasons);
        }
        if (input.serverState == ServerState.REJECT_PENDING ||
                input.serverState == ServerState.RESTORE_PENDING) {
            reasons.add("server-operation-pending");
            return new Result(Verdict.SERVER_PENDING, compromised, false, reasons);
        }
        if (input.serverState == ServerState.FAILED) {
            reasons.add("server-operation-failed");
            return new Result(Verdict.SERVER_FAILED, compromised, false, reasons);
        }

        if (!compromised) {
            if (input.compromiseNeedsReview) {
                if (spam > 0)
                    reasons.add("confirmed-spam=" + spam);
                reasons.add("alias-compromise-needs-review");
                return new Result(Verdict.REVIEW_COMPROMISE, false, false, reasons);
            }
            if (spam > 0)
                reasons.add("confirmed-spam-without-alias-compromise=" + spam);
            else
                reasons.add("no-confirmed-spam");
            return new Result(Verdict.HEALTHY, false, false, reasons);
        }

        reasons.add("alias-compromise-confirmed");
        if (spam > 0)
            reasons.add("confirmed-spam=" + spam);

        if (input.replacementConfigured) {
            reasons.add("replacement-configured");
            if (!input.replacementVerified) {
                reasons.add("replacement-not-yet-verified");
                return new Result(Verdict.VERIFY_REPLACEMENT, true, false, reasons);
            }
            reasons.add("replacement-verified");
            return new Result(Verdict.READY_TO_BURN, true, true, reasons);
        }

        boolean legitimateContext = ham > 0 ||
                input.serviceDomainKnown ||
                input.trustedDomainsConfigured;
        if (legitimateContext) {
            if (ham > 0)
                reasons.add("legitimate-history=" + ham);
            if (input.serviceDomainKnown)
                reasons.add("service-domain-known");
            if (input.trustedDomainsConfigured)
                reasons.add("trusted-domains-configured");
            reasons.add("replacement-required-before-burn");
            return new Result(Verdict.ROTATE_FIRST, true, false, reasons);
        }

        reasons.add("no-replacement-yet");
        return new Result(Verdict.COMPROMISED, true, false, reasons);
    }
}

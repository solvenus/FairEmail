package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

/**
 * Provider-neutral contract for making a delivery alias physically reject mail
 * at SMTP time. Implementations must verify the remote state after mutation.
 */
public interface AliasServerActuator {
    String provider();

    /** Inspect, mutate if safe, then read back and verify. */
    Result burn(String address, String failureMessage) throws Exception;

    /** Restore the provider-neutral route snapshot captured before burn. */
    Result restore(String address, String routeSnapshot) throws Exception;

    final class Result {
        public final boolean changed;
        public final boolean verified;
        /** Provider-neutral JSON containing routing metadata only. */
        public final String routeSnapshot;
        public final String error;

        public Result(boolean changed, boolean verified, String routeSnapshot, String error) {
            this.changed = changed;
            this.verified = verified;
            this.routeSnapshot = routeSnapshot;
            this.error = error;
        }

        public static Result verified(boolean changed, String routeSnapshot) {
            return new Result(changed, true, routeSnapshot, null);
        }

        public static Result failed(String routeSnapshot, String error) {
            return new Result(false, false, routeSnapshot, error);
        }
    }
}

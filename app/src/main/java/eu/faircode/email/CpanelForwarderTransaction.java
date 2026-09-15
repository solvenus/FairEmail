package eu.faircode.email;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java transaction engine for replacing cPanel forwarder routes.
 *
 * The engine never decides whether the user is allowed to burn an alias. Its
 * only job is to distinguish rollback-safe route sets from explicit unsafe
 * override and to restore the pre-operation route set when a safe mutation
 * fails halfway through.
 */
public final class CpanelForwarderTransaction {
    public interface Backend {
        List<String> list() throws Exception;
        void delete(String destination) throws Exception;
        void add(Map<String, String> arguments) throws Exception;
    }

    public static final class Result {
        public final boolean success;
        public final boolean changed;
        public final boolean unsafeRequired;
        public final boolean rollbackAttempted;
        public final boolean rollbackSucceeded;
        public final String error;
        public final List<String> before;
        public final List<String> after;

        private Result(boolean success,
                       boolean changed,
                       boolean unsafeRequired,
                       boolean rollbackAttempted,
                       boolean rollbackSucceeded,
                       String error,
                       List<String> before,
                       List<String> after) {
            this.success = success;
            this.changed = changed;
            this.unsafeRequired = unsafeRequired;
            this.rollbackAttempted = rollbackAttempted;
            this.rollbackSucceeded = rollbackSucceeded;
            this.error = error;
            this.before = immutable(before);
            this.after = immutable(after);
        }
    }

    private CpanelForwarderTransaction() {
    }

    public static Result burn(Backend backend,
                              String address,
                              String failureMessage,
                              String homeDirectory,
                              boolean allowUnsafe) {
        if (backend == null)
            throw new IllegalArgumentException("backend");

        List<String> before;
        try {
            before = clean(backend.list());
        } catch (Throwable ex) {
            return failed(false, false, "preflight-list-failed:" + compact(ex),
                    Collections.<String>emptyList(), Collections.<String>emptyList());
        }

        if (containsFail(before))
            return new Result(true, false, false, false, false, null, before, before);

        String unsafe = firstUnrestorable(before, homeDirectory);
        if (unsafe != null && !allowUnsafe)
            return new Result(false, false, true, false, false,
                    "route-not-roundtrippable:" + unsafe, before, before);

        String failDestination = ":fail: " + normalizeFailureMessage(failureMessage);
        boolean rollbackSafe = unsafe == null;
        try {
            replace(backend, address,
                    Collections.singletonList(failDestination), homeDirectory, true);
            List<String> after = clean(backend.list());
            if (!isPureFail(after))
                throw new IllegalStateException("read-back-not-pure-fail-route");
            return new Result(true, !semanticallyEqual(before, after, homeDirectory),
                    false, false, false, null, before, after);
        } catch (Throwable operationError) {
            if (!rollbackSafe) {
                return failed(false, false,
                        "unsafe-burn-failed-no-rollback:" + compact(operationError),
                        before, safeList(backend));
            }
            Rollback rollback = rollback(backend, address, before, homeDirectory);
            return new Result(false, false, false, true, rollback.success,
                    "burn-failed:" + compact(operationError) +
                            (rollback.success ? ":rolled-back" :
                                    ":rollback-failed:" + rollback.error),
                    before, rollback.after);
        }
    }

    public static Result restore(Backend backend,
                                 String address,
                                 List<String> target,
                                 String homeDirectory,
                                 boolean allowUnsafe) {
        if (backend == null)
            throw new IllegalArgumentException("backend");
        List<String> desired = clean(target);
        List<String> before;
        try {
            before = clean(backend.list());
        } catch (Throwable ex) {
            return failed(false, false, "preflight-list-failed:" + compact(ex),
                    Collections.<String>emptyList(), Collections.<String>emptyList());
        }

        if (semanticallyEqual(before, desired, homeDirectory))
            return new Result(true, false, false, false, false, null, before, before);

        String unsafeDesired = firstUnrestorable(desired, homeDirectory);
        String unsafeCurrent = firstUnrestorable(before, homeDirectory);
        String unsafe = unsafeDesired != null ? unsafeDesired : unsafeCurrent;
        if (unsafe != null && !allowUnsafe)
            return new Result(false, false, true, false, false,
                    "route-not-roundtrippable:" + unsafe, before, before);

        boolean rollbackSafe = unsafeCurrent == null;
        try {
            replace(backend, address, desired, homeDirectory, allowUnsafe);
            List<String> after = clean(backend.list());
            if (!semanticallyEqual(after, desired, homeDirectory))
                throw new IllegalStateException("restore-read-back-mismatch");
            return new Result(true, true, false, false, false, null, before, after);
        } catch (Throwable operationError) {
            if (!rollbackSafe) {
                return failed(false, false,
                        "unsafe-restore-failed-no-rollback:" + compact(operationError),
                        before, safeList(backend));
            }
            Rollback rollback = rollback(backend, address, before, homeDirectory);
            return new Result(false, false, false, true, rollback.success,
                    "restore-failed:" + compact(operationError) +
                            (rollback.success ? ":rolled-back" :
                                    ":rollback-failed:" + rollback.error),
                    before, rollback.after);
        }
    }

    private static void replace(Backend backend,
                                String address,
                                List<String> target,
                                String homeDirectory,
                                boolean allowUnsafe) throws Exception {
        List<String> current = clean(backend.list());
        for (String destination : current)
            backend.delete(destination);

        List<String> afterDelete = clean(backend.list());
        if (!afterDelete.isEmpty())
            throw new IllegalStateException("delete-read-back-not-empty");

        for (String destination : target) {
            CpanelForwarderRoutePlan.Spec spec =
                    CpanelForwarderRoutePlan.parse(destination, homeDirectory);
            if (!spec.restorable) {
                if (allowUnsafe)
                    throw new IllegalStateException("cannot-recreate-unsafe-route:" +
                            spec.reason + ":" + destination);
                throw new IllegalStateException("route-not-roundtrippable:" +
                        spec.reason + ":" + destination);
            }
            backend.add(spec.addArguments(address));
        }
    }

    private static Rollback rollback(Backend backend,
                                     String address,
                                     List<String> before,
                                     String homeDirectory) {
        try {
            replace(backend, address, before, homeDirectory, false);
            List<String> after = clean(backend.list());
            if (!semanticallyEqual(before, after, homeDirectory))
                return new Rollback(false, "rollback-read-back-mismatch", after);
            return new Rollback(true, null, after);
        } catch (Throwable ex) {
            return new Rollback(false, compact(ex), safeList(backend));
        }
    }

    private static boolean containsFail(List<String> routes) {
        for (String route : routes)
            if (CpanelForwarderRoutePlan.parse(route, null).kind ==
                    CpanelForwarderRoutePlan.Kind.FAIL)
                return true;
        return false;
    }

    private static boolean isPureFail(List<String> routes) {
        if (routes.isEmpty())
            return false;
        for (String route : routes)
            if (CpanelForwarderRoutePlan.parse(route, null).kind !=
                    CpanelForwarderRoutePlan.Kind.FAIL)
                return false;
        return true;
    }

    private static String firstUnrestorable(List<String> routes, String homeDirectory) {
        for (String route : routes) {
            CpanelForwarderRoutePlan.Spec spec =
                    CpanelForwarderRoutePlan.parse(route, homeDirectory);
            if (!spec.restorable)
                return spec.reason + ":" + route;
        }
        return null;
    }

    static boolean semanticallyEqual(List<String> left,
                                     List<String> right,
                                     String homeDirectory) {
        List<String> a = semanticKeys(left, homeDirectory);
        List<String> b = semanticKeys(right, homeDirectory);
        return a != null && b != null && a.equals(b);
    }

    private static List<String> semanticKeys(List<String> routes, String homeDirectory) {
        List<String> result = new ArrayList<>();
        for (String route : clean(routes)) {
            CpanelForwarderRoutePlan.Spec spec =
                    CpanelForwarderRoutePlan.parse(route, homeDirectory);
            if (!spec.restorable)
                return null;
            result.add(spec.key(homeDirectory));
        }
        Collections.sort(result);
        return result;
    }

    private static List<String> safeList(Backend backend) {
        try {
            return clean(backend.list());
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private static List<String> clean(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values != null)
            for (String value : values)
                if (value != null && !value.trim().isEmpty())
                    result.add(value.trim());
        return result;
    }

    private static List<String> immutable(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(clean(values)));
    }

    private static Result failed(boolean unsafeRequired,
                                 boolean rollbackAttempted,
                                 String error,
                                 List<String> before,
                                 List<String> after) {
        return new Result(false, false, unsafeRequired, rollbackAttempted, false,
                error, before, after);
    }

    private static String normalizeFailureMessage(String value) {
        if (value == null || value.trim().isEmpty())
            return "No such person at this address";
        return value.trim();
    }

    private static String compact(Throwable ex) {
        if (ex == null)
            return "unknown-error";
        String message = ex.getMessage();
        if (message == null || message.trim().isEmpty())
            return ex.getClass().getSimpleName();
        message = message.replace('\n', ' ').replace('\r', ' ').trim();
        return ex.getClass().getSimpleName() + ":" + message;
    }

    private static final class Rollback {
        final boolean success;
        final String error;
        final List<String> after;

        Rollback(boolean success, String error, List<String> after) {
            this.success = success;
            this.error = error;
            this.after = after;
        }
    }
}

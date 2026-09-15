package eu.faircode.email;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Adversarial zero-dependency tests for cPanel forwarder replacement/rollback. */
public final class CpanelForwarderTransactionLab {
    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String address = "xy_service@example.org";
        String home = "/home/cptest";

        FakeBackend normal = new FakeBackend("target@example.net");
        CpanelForwarderTransaction.Result burn = CpanelForwarderTransaction.burn(
                normal, address, "No such person at this address", home, false);
        require(burn.success && burn.changed,
                "supported explicit route should be transactionally replaced");
        require(normal.routes.size() == 1 && normal.routes.get(0).startsWith(":fail:"),
                "successful burn must end as pure fail route");

        CpanelForwarderTransaction.Result restored = CpanelForwarderTransaction.restore(
                normal, address, burn.before, home, false);
        require(restored.success && restored.changed,
                "explicit route snapshot should restore");
        require(CpanelForwarderTransaction.semanticallyEqual(
                        normal.routes, Arrays.asList("target@example.net"), home),
                "restored route does not match original");

        FakeBackend failAdd = new FakeBackend("one@example.net", "two@example.net");
        failAdd.failNextAdd = true;
        CpanelForwarderTransaction.Result addFailure = CpanelForwarderTransaction.burn(
                failAdd, address, "dead", home, false);
        require(!addFailure.success && addFailure.rollbackAttempted && addFailure.rollbackSucceeded,
                "safe add failure must roll back");
        require(CpanelForwarderTransaction.semanticallyEqual(
                        failAdd.routes, Arrays.asList("one@example.net", "two@example.net"), home),
                "add failure rollback did not restore both routes");

        FakeBackend failDelete = new FakeBackend("target@example.net");
        failDelete.failNextDelete = true;
        CpanelForwarderTransaction.Result deleteFailure = CpanelForwarderTransaction.burn(
                failDelete, address, "dead", home, false);
        require(!deleteFailure.success && deleteFailure.rollbackAttempted &&
                        deleteFailure.rollbackSucceeded,
                "safe delete failure must reconcile original state");
        require(CpanelForwarderTransaction.semanticallyEqual(
                        failDelete.routes, Arrays.asList("target@example.net"), home),
                "delete failure rollback changed original route");

        FakeBackend unknown = new FakeBackend(":custom-exim-router:");
        CpanelForwarderTransaction.Result blocked = CpanelForwarderTransaction.burn(
                unknown, address, "dead", home, false);
        require(!blocked.success && blocked.unsafeRequired,
                "unknown route must request explicit unsafe override");
        require(unknown.deleteCalls == 0 && unknown.addCalls == 0,
                "safe preflight must not mutate unknown routes");

        FakeBackend unsafe = new FakeBackend(":custom-exim-router:");
        CpanelForwarderTransaction.Result forced = CpanelForwarderTransaction.burn(
                unsafe, address, "dead", home, true);
        require(forced.success && forced.changed,
                "explicit unsafe override must be able to burn unknown route");
        require(unsafe.routes.size() == 1 && unsafe.routes.get(0).startsWith(":fail:"),
                "unsafe override did not end in fail route");

        FakeBackend unsafeFailure = new FakeBackend(":custom-exim-router:");
        unsafeFailure.failNextAdd = true;
        CpanelForwarderTransaction.Result forcedFailure = CpanelForwarderTransaction.burn(
                unsafeFailure, address, "dead", home, true);
        require(!forcedFailure.success && !forcedFailure.rollbackAttempted &&
                        !forcedFailure.rollbackSucceeded,
                "unsafe failure must not pretend rollback was possible");

        FakeBackend pipe = new FakeBackend("|/home/cptest/bin/mail-handler");
        CpanelForwarderTransaction.Result pipeBurn = CpanelForwarderTransaction.burn(
                pipe, address, "dead", home, false);
        require(pipeBurn.success, "known-home pipe should be rollback-safe");
        CpanelForwarderTransaction.Result pipeRestore = CpanelForwarderTransaction.restore(
                pipe, address, pipeBurn.before, home, false);
        require(pipeRestore.success &&
                        CpanelForwarderTransaction.semanticallyEqual(
                                pipe.routes, pipeBurn.before, home),
                "pipe route failed round-trip restore");

        FakeBackend alreadyDead = new FakeBackend(":fail: Existing reject");
        CpanelForwarderTransaction.Result noChange = CpanelForwarderTransaction.burn(
                alreadyDead, address, "dead", home, false);
        require(noChange.success && !noChange.changed && alreadyDead.deleteCalls == 0,
                "already-dead alias must be no-op");

        FakeBackend mixedFail = new FakeBackend(
                ":fail: Existing reject", "still-forwarded@example.net");
        CpanelForwarderTransaction.Result mixed = CpanelForwarderTransaction.burn(
                mixedFail, address, "dead", home, false);
        require(mixed.success && mixed.changed,
                "mixed fail+forward must be replaced, not accepted as already dead");
        require(mixedFail.routes.size() == 1 && mixedFail.routes.get(0).startsWith(":fail:"),
                "mixed fail+forward must end as pure fail route");
        require(mixedFail.deleteCalls == 2,
                "mixed fail+forward must delete every pre-existing route");

        System.out.println("PASS cpanel-transaction safeBurn+restore+rollback+unsafeVeto+pipe+pureFail");
    }

    private static final class FakeBackend implements CpanelForwarderTransaction.Backend {
        final List<String> routes = new ArrayList<>();
        int deleteCalls;
        int addCalls;
        boolean failNextDelete;
        boolean failNextAdd;

        FakeBackend(String... initial) {
            routes.addAll(Arrays.asList(initial));
        }

        @Override
        public List<String> list() {
            return new ArrayList<>(routes);
        }

        @Override
        public void delete(String destination) throws Exception {
            deleteCalls++;
            if (failNextDelete) {
                failNextDelete = false;
                throw new Exception("injected-delete-failure");
            }
            if (!routes.remove(destination))
                throw new Exception("route-not-found:" + destination);
        }

        @Override
        public void add(Map<String, String> arguments) throws Exception {
            addCalls++;
            if (failNextAdd) {
                failNextAdd = false;
                throw new Exception("injected-add-failure");
            }
            String option = arguments.get("fwdopt");
            if ("fwd".equals(option))
                routes.add(arguments.get("fwdemail"));
            else if ("fail".equals(option))
                routes.add(":fail: " + value(arguments.get("failmsgs"),
                        "No such person at this address"));
            else if ("blackhole".equals(option))
                routes.add(":blackhole:");
            else if ("pipe".equals(option))
                routes.add("|/home/cptest/" + arguments.get("pipefwd"));
            else if ("system".equals(option))
                routes.add(arguments.get("fwdsystem"));
            else
                throw new Exception("unknown-option:" + option);
        }

        private static String value(String value, String fallback) {
            return value == null || value.isEmpty() ? fallback : value;
        }
    }
}

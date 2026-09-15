package eu.faircode.email;

import java.util.Map;

/** Zero-dependency regression checks for reversible cPanel route planning. */
public final class CpanelForwarderRoutePlanLab {
    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String address = "xy_service@example.org";
        String home = "/home/cptest";

        CpanelForwarderRoutePlan.Spec forward = CpanelForwarderRoutePlan.parse(
                "target@example.net", home);
        require(forward.kind == CpanelForwarderRoutePlan.Kind.FORWARD && forward.restorable,
                "ordinary forward must be reversible");
        Map<String, String> fwd = forward.addArguments(address);
        require("fwd".equals(fwd.get("fwdopt")) &&
                        "target@example.net".equals(fwd.get("fwdemail")),
                "ordinary forward arguments wrong");

        CpanelForwarderRoutePlan.Spec fail = CpanelForwarderRoutePlan.parse(
                ":fail: No such person at this address", home);
        require(fail.kind == CpanelForwarderRoutePlan.Kind.FAIL && fail.restorable,
                "fail route must be reversible");
        Map<String, String> failArgs = fail.addArguments(address);
        require("fail".equals(failArgs.get("fwdopt")) &&
                        "No such person at this address".equals(failArgs.get("failmsgs")),
                "fail route arguments wrong");

        CpanelForwarderRoutePlan.Spec blackhole = CpanelForwarderRoutePlan.parse(
                ":blackhole:", home);
        require(blackhole.kind == CpanelForwarderRoutePlan.Kind.BLACKHOLE &&
                        "blackhole".equals(blackhole.addArguments(address).get("fwdopt")),
                "blackhole route must be reversible");

        CpanelForwarderRoutePlan.Spec pipe = CpanelForwarderRoutePlan.parse(
                "|/home/cptest/bin/mail-handler", home);
        require(pipe.kind == CpanelForwarderRoutePlan.Kind.PIPE && pipe.restorable,
                "pipe inside home must be reversible");
        require("bin/mail-handler".equals(pipe.addArguments(address).get("pipefwd")),
                "pipe must round-trip as home-relative UAPI path");
        require(CpanelForwarderRoutePlan.semanticallyEqual(
                        "|/home/cptest/bin/mail-handler", "|/home/cptest/bin/mail-handler", home),
                "pipe semantic equality failed");

        CpanelForwarderRoutePlan.Spec pipeUnknownHome = CpanelForwarderRoutePlan.parse(
                "|/home/cptest/bin/mail-handler", null);
        require(!pipeUnknownHome.restorable &&
                        "pipe-home-directory-unknown".equals(pipeUnknownHome.reason),
                "absolute pipe without home must not pretend rollback is safe");

        CpanelForwarderRoutePlan.Spec pipeOutside = CpanelForwarderRoutePlan.parse(
                "|/opt/mail-handler", home);
        require(!pipeOutside.restorable &&
                        "pipe-outside-home-directory".equals(pipeOutside.reason),
                "pipe outside cPanel home must not pretend rollback is safe");

        CpanelForwarderRoutePlan.Spec system = CpanelForwarderRoutePlan.parse(
                "cptest", home);
        require(system.kind == CpanelForwarderRoutePlan.Kind.SYSTEM && system.restorable,
                "system route must be reversible");
        require("cptest".equals(system.addArguments(address).get("fwdsystem")),
                "system route arguments wrong");

        CpanelForwarderRoutePlan.Spec unknown = CpanelForwarderRoutePlan.parse(
                ":unknown-exim-token:", home);
        require(!unknown.restorable && unknown.kind == CpanelForwarderRoutePlan.Kind.UNKNOWN,
                "unknown Exim token must be explicit non-roundtrippable state");

        require(CpanelForwarderRoutePlan.semanticallyEqual(
                        "Target@Example.NET", "target@example.net", home),
                "forward destination comparison should tolerate cPanel/email case normalization");

        System.out.println("PASS cpanel-route-plan " +
                forward.kind + "," + fail.kind + "," + blackhole.kind + "," +
                pipe.kind + "," + system.kind + "," + unknown.reason);
    }
}

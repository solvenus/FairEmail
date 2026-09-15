package eu.faircode.email;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure-Java translation between cPanel list_forwarders destinations and
 * add_forwarder arguments. Keeping this Android-free lets CI prove that a
 * route snapshot is reconstructable before server mutation is allowed to rely
 * on automatic rollback.
 */
public final class CpanelForwarderRoutePlan {
    public enum Kind {
        FORWARD,
        FAIL,
        BLACKHOLE,
        PIPE,
        SYSTEM,
        UNKNOWN
    }

    public static final class Spec {
        public final Kind kind;
        public final String listedDestination;
        public final String payload;
        public final boolean restorable;
        public final String reason;

        private Spec(Kind kind,
                     String listedDestination,
                     String payload,
                     boolean restorable,
                     String reason) {
            this.kind = kind;
            this.listedDestination = listedDestination;
            this.payload = payload;
            this.restorable = restorable;
            this.reason = reason;
        }

        public Map<String, String> addArguments(String address) {
            if (!restorable)
                throw new IllegalStateException(reason == null ? "route-not-restorable" : reason);
            String normalized = normalizeAddress(address);
            String domain = domain(normalized);
            if (normalized == null || domain == null)
                throw new IllegalArgumentException("address");

            Map<String, String> args = new TreeMap<>();
            args.put("domain", domain);
            args.put("email", normalized);
            switch (kind) {
                case FORWARD:
                    args.put("fwdopt", "fwd");
                    args.put("fwdemail", payload);
                    break;
                case FAIL:
                    args.put("fwdopt", "fail");
                    if (payload != null && !payload.isEmpty())
                        args.put("failmsgs", payload);
                    break;
                case BLACKHOLE:
                    args.put("fwdopt", "blackhole");
                    break;
                case PIPE:
                    args.put("fwdopt", "pipe");
                    args.put("pipefwd", payload);
                    break;
                case SYSTEM:
                    args.put("fwdopt", "system");
                    args.put("fwdsystem", payload);
                    break;
                default:
                    throw new IllegalStateException("unknown-route");
            }
            return Collections.unmodifiableMap(args);
        }

        /** Semantic comparison key after cPanel normalization. */
        public String key(String homeDirectory) {
            String value = payload == null ? "" : payload;
            if (kind == Kind.FORWARD || kind == Kind.SYSTEM)
                value = value.toLowerCase(java.util.Locale.ROOT);
            if (kind == Kind.PIPE)
                value = pipeAbsolute(value, homeDirectory);
            return kind.name() + "|" + value;
        }
    }

    private CpanelForwarderRoutePlan() {
    }

    public static Spec parse(String destination, String homeDirectory) {
        if (destination == null)
            return unknown(null, "missing-destination");
        String value = destination.trim();
        if (value.isEmpty())
            return unknown(destination, "empty-destination");

        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith(":fail:")) {
            String message = value.substring(6).trim();
            return new Spec(Kind.FAIL, value, message, true, null);
        }
        if (lower.equals(":blackhole:") || lower.startsWith(":blackhole:"))
            return new Spec(Kind.BLACKHOLE, value, null, true, null);

        if (value.charAt(0) == '|') {
            String path = value.substring(1).trim();
            if (path.isEmpty())
                return unknown(value, "empty-pipe-path");

            if (path.charAt(0) != '/')
                return new Spec(Kind.PIPE, value, path, true, null);

            String home = normalizeHome(homeDirectory);
            if (home == null)
                return unknown(value, "pipe-home-directory-unknown");
            String prefix = home + "/";
            if (!path.startsWith(prefix))
                return unknown(value, "pipe-outside-home-directory");
            String relative = path.substring(prefix.length());
            if (relative.isEmpty())
                return unknown(value, "empty-pipe-relative-path");
            return new Spec(Kind.PIPE, value, relative, true, null);
        }

        if (value.indexOf('@') > 0)
            return new Spec(Kind.FORWARD, value, value, true, null);

        // cPanel's system forwarder is represented by the target system user.
        // Avoid guessing for route syntax that begins with another Exim token.
        if (value.charAt(0) == ':')
            return unknown(value, "unknown-exim-route-token");
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0)
            return unknown(value, "unknown-route-path");
        return new Spec(Kind.SYSTEM, value, value, true, null);
    }

    public static boolean semanticallyEqual(String a, String b, String homeDirectory) {
        Spec left = parse(a, homeDirectory);
        Spec right = parse(b, homeDirectory);
        return left.restorable && right.restorable &&
                left.key(homeDirectory).equals(right.key(homeDirectory));
    }

    private static Spec unknown(String listed, String reason) {
        return new Spec(Kind.UNKNOWN, listed, null, false, reason);
    }

    private static String pipeAbsolute(String payload, String homeDirectory) {
        if (payload == null)
            return "";
        if (payload.startsWith("/"))
            return payload;
        String home = normalizeHome(homeDirectory);
        return home == null ? payload : home + "/" + payload;
    }

    private static String normalizeHome(String value) {
        if (value == null)
            return null;
        String home = value.trim();
        if (home.isEmpty())
            return null;
        while (home.length() > 1 && home.endsWith("/"))
            home = home.substring(0, home.length() - 1);
        return home;
    }

    private static String normalizeAddress(String value) {
        if (value == null)
            return null;
        String address = value.trim().toLowerCase(java.util.Locale.ROOT);
        return address.isEmpty() ? null : address;
    }

    private static String domain(String address) {
        if (address == null)
            return null;
        int at = address.lastIndexOf('@');
        if (at <= 0 || at + 1 >= address.length())
            return null;
        return address.substring(at + 1);
    }
}

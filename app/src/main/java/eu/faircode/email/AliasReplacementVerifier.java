package eu.faircode.email;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/** Derives whether a configured replacement alias is demonstrably in use. */
public final class AliasReplacementVerifier {
    private AliasReplacementVerifier() {
    }

    public static Result verify(EntityAlias oldAlias, EntityAlias replacement) {
        if (oldAlias == null || replacement == null)
            return new Result(false, "replacement-not-observed");
        if (replacement.messages == null || replacement.messages <= 0)
            return new Result(false, "replacement-has-no-mail");

        if (replacement.ham_hits != null && replacement.ham_hits > 0)
            return new Result(true, "replacement-has-legitimate-history");

        Set<String> expected = new HashSet<>();
        add(expected, oldAlias.service_domain);
        addTrusted(expected, oldAlias.trusted_domains);

        if (!expected.isEmpty()) {
            String replacementService = normalize(replacement.service_domain);
            if (replacementService != null && expected.contains(replacementService))
                return new Result(true, "replacement-service-domain-matches");

            Set<String> observed = observedDomains(replacement.observed_domains);
            for (String domain : expected)
                if (observed.contains(domain))
                    return new Result(true, "replacement-observed-expected-domain");
        }

        return new Result(false, "replacement-needs-legitimate-evidence");
    }

    private static void addTrusted(Set<String> result, String json) {
        if (TextUtils.isEmpty(json))
            return;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++)
                add(result, array.optString(i, null));
        } catch (Throwable ex) {
            Log.w(ex);
        }
    }

    private static Set<String> observedDomains(String json) {
        Set<String> result = new HashSet<>();
        if (TextUtils.isEmpty(json))
            return result;
        try {
            JSONObject object = new JSONObject(json);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (object.optInt(key, 0) > 0)
                    add(result, key);
            }
        } catch (Throwable ex) {
            Log.w(ex);
        }
        return result;
    }

    private static void add(Set<String> result, String value) {
        String normalized = normalize(value);
        if (normalized != null)
            result.add(normalized);
    }

    private static String normalize(String value) {
        if (value == null)
            return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    public static final class Result {
        public final boolean verified;
        public final String reason;

        Result(boolean verified, String reason) {
            this.verified = verified;
            this.reason = reason;
        }
    }
}

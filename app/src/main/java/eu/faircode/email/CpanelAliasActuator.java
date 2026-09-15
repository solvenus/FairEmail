package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * cPanel UAPI implementation of AliasServerActuator.
 *
 * Explicit routes are replaced through CpanelForwarderTransaction. The default
 * mode only mutates route sets that can be reconstructed and rolled back. An
 * explicit user veto can enable unsafe burn; that mode never pretends rollback
 * is available for route syntax we cannot reproduce.
 */
public final class CpanelAliasActuator implements AliasServerActuator {
    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 20_000;

    private final Config config;
    private final boolean allowUnsafeBurn;

    public CpanelAliasActuator(Config config) {
        this(config, false);
    }

    public CpanelAliasActuator(Config config, boolean allowUnsafeBurn) {
        if (config == null)
            throw new IllegalArgumentException("config");
        this.config = config;
        this.allowUnsafeBurn = allowUnsafeBurn;
    }

    @Override
    public String provider() {
        return "cpanel";
    }

    @Override
    public Result burn(String address, String failureMessage) throws Exception {
        String normalized = normalizeAddress(address);
        if (normalized == null)
            return Result.failed(null, "invalid-address");
        if (domain(normalized) == null)
            return Result.failed(null, "invalid-domain");

        String home = homeDirectory();
        CpanelForwarderTransaction.Result transaction = CpanelForwarderTransaction.burn(
                backend(normalized), normalized, failureMessage, home, allowUnsafeBurn);
        String snapshot = transaction.before.isEmpty() &&
                transaction.error != null && transaction.error.startsWith("preflight-list-failed:")
                ? null : snapshot(normalized, destinations(normalized, transaction.before));

        if (transaction.success)
            return Result.verified(transaction.changed, snapshot);

        String error = transaction.error;
        if (transaction.unsafeRequired)
            error = "unsafe-required:" + error;
        return Result.failed(snapshot, error);
    }

    @Override
    public Result restore(String address, String routeSnapshot) throws Exception {
        String normalized = normalizeAddress(address);
        if (normalized == null)
            return Result.failed(routeSnapshot, "invalid-address");

        Snapshot snapshot = parseSnapshot(routeSnapshot);
        if (snapshot == null || !normalized.equalsIgnoreCase(snapshot.address))
            return Result.failed(routeSnapshot, "invalid-route-snapshot");

        List<String> target = new ArrayList<>();
        for (Route route : snapshot.routes)
            target.add(route.destination);

        CpanelForwarderTransaction.Result transaction = CpanelForwarderTransaction.restore(
                backend(normalized), normalized, target, homeDirectory(), false);
        if (transaction.success)
            return Result.verified(transaction.changed, routeSnapshot);
        return Result.failed(routeSnapshot,
                transaction.unsafeRequired
                        ? "restore-unsafe-route:" + transaction.error
                        : transaction.error);
    }

    private CpanelForwarderTransaction.Backend backend(final String address) {
        return new CpanelForwarderTransaction.Backend() {
            @Override
            public List<String> list() throws Exception {
                Probe probe = CpanelAliasActuator.this.probe(address);
                List<String> result = new ArrayList<>();
                for (Route route : probe.routes)
                    result.add(route.destination);
                return result;
            }

            @Override
            public void delete(String destination) throws Exception {
                Map<String, String> args = new TreeMap<>();
                args.put("address", address);
                args.put("forwarder", destination);
                call("Email", "delete_forwarder", args);
            }

            @Override
            public void add(Map<String, String> arguments) throws Exception {
                call("Email", "add_forwarder", arguments);
            }
        };
    }

    private String homeDirectory() {
        try {
            JSONObject root = call("Variables", "get_user_information",
                    Collections.<String, String>emptyMap());
            JSONObject result = root.optJSONObject("result");
            JSONObject data = result == null ? null : result.optJSONObject("data");
            if (data == null)
                return null;
            return first(data, "HOMEDIR", "homedir", "HOME", "home");
        } catch (Throwable ex) {
            // Home directory is only needed to prove absolute pipe routes
            // round-trippable. Unknown home therefore downgrades those routes
            // to explicit unsafe-veto instead of guessing a server path.
            Log.w(ex);
            return null;
        }
    }

    private static List<Route> destinations(String address, List<String> destinations) {
        List<Route> routes = new ArrayList<>();
        if (destinations != null)
            for (String destination : destinations)
                if (!TextUtils.isEmpty(destination))
                    routes.add(new Route(address, destination.trim(), isFail(destination)));
        return routes;
    }

    /** Read-only remote inspection used by burn/restore and later UI diagnostics. */
    Probe probe(String address) throws Exception {
        String normalized = normalizeAddress(address);
        if (normalized == null)
            throw new IllegalArgumentException("address");
        String domain = domain(normalized);

        Map<String, String> args = new TreeMap<>();
        args.put("domain", domain);
        // Filtering is an optimization only; exact matching is still performed
        // locally because the remote filter is not a security boundary.
        args.put("regex", localPart(normalized));
        JSONObject root = call("Email", "list_forwarders", args);

        JSONArray data = getDataArray(root);
        List<Route> routes = new ArrayList<>();
        if (data != null)
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null)
                    continue;

                String source = first(item, "forward", "address", "email");
                String destination = first(item, "dest", "destination", "forwarder");
                source = normalizeAddress(source);
                if (source == null || !normalized.equalsIgnoreCase(source) ||
                        TextUtils.isEmpty(destination))
                    continue;

                destination = destination.trim();
                boolean fail = isFail(destination);
                routes.add(new Route(source, destination, fail));
            }

        boolean rejected = false;
        for (Route route : routes)
            if (route.fail) {
                rejected = true;
                break;
            }

        return new Probe(Collections.unmodifiableList(routes), rejected);
    }

    private JSONObject call(String module, String function, Map<String, String> args)
            throws IOException, JSONException {
        Uri.Builder builder = Uri.parse(config.baseUrl).buildUpon()
                .appendPath("execute")
                .appendPath(module)
                .appendPath(function);
        for (Map.Entry<String, String> entry : args.entrySet())
            if (entry.getValue() != null)
                builder.appendQueryParameter(entry.getKey(), entry.getValue());

        HttpURLConnection connection = null;
        try {
            URL url = new URL(builder.build().toString());
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization",
                    "cpanel " + config.username + ":" + config.apiToken);
            connection.setRequestProperty("User-Agent", "FairEmail-SpamIntelligence/1");

            int code = connection.getResponseCode();
            InputStream stream = (code >= 200 && code < 300)
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String body = readAll(stream);
            if (TextUtils.isEmpty(body))
                throw new IOException("cPanel empty response HTTP " + code);

            JSONObject root = new JSONObject(body);
            if (code < 200 || code >= 300)
                throw new IOException("cPanel HTTP " + code + ": " + apiError(root));

            JSONObject result = root.optJSONObject("result");
            if (result == null)
                throw new IOException("cPanel response missing result");
            if (result.optInt("status", 0) != 1)
                throw new IOException("cPanel UAPI failure: " + apiError(root));

            return root;
        } finally {
            if (connection != null)
                connection.disconnect();
        }
    }

    private static JSONArray getDataArray(JSONObject root) {
        JSONObject result = root == null ? null : root.optJSONObject("result");
        if (result == null)
            return null;
        Object data = result.opt("data");
        if (data instanceof JSONArray)
            return (JSONArray) data;
        if (data instanceof JSONObject) {
            JSONArray one = new JSONArray();
            one.put(data);
            return one;
        }
        return null;
    }

    private static String apiError(JSONObject root) {
        if (root == null)
            return "unknown error";
        JSONObject result = root.optJSONObject("result");
        if (result == null)
            return "unknown error";

        Object errors = result.opt("errors");
        if (errors instanceof JSONArray && ((JSONArray) errors).length() > 0)
            return String.valueOf(((JSONArray) errors).opt(0));
        if (errors != null && errors != JSONObject.NULL)
            return String.valueOf(errors);

        Object messages = result.opt("messages");
        if (messages instanceof JSONArray && ((JSONArray) messages).length() > 0)
            return String.valueOf(((JSONArray) messages).opt(0));
        return "unknown error";
    }

    private static String snapshot(String address, List<Route> routes) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("provider", "cpanel");
        root.put("address", address);
        JSONArray array = new JSONArray();
        for (Route route : routes) {
            JSONObject item = new JSONObject();
            item.put("source", route.source);
            item.put("destination", route.destination);
            item.put("fail", route.fail);
            array.put(item);
        }
        root.put("routes", array);
        return root.toString();
    }

    private static Snapshot parseSnapshot(String json) {
        if (TextUtils.isEmpty(json))
            return null;
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("version", -1) != 1 ||
                    !"cpanel".equals(root.optString("provider")))
                return null;
            String address = normalizeAddress(root.optString("address", null));
            JSONArray array = root.optJSONArray("routes");
            if (address == null || array == null)
                return null;

            List<Route> routes = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null)
                    return null;
                String source = normalizeAddress(item.optString("source", null));
                String destination = item.optString("destination", null);
                if (source == null || TextUtils.isEmpty(destination))
                    return null;
                routes.add(new Route(source, destination, item.optBoolean("fail", isFail(destination))));
            }
            return new Snapshot(address, Collections.unmodifiableList(routes));
        } catch (Throwable ex) {
            return null;
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null)
            return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0)
                result.append(buffer, 0, read);
        }
        return result.toString();
    }

    private static String first(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, null);
            if (!TextUtils.isEmpty(value))
                return value;
        }
        return null;
    }

    private static boolean isFail(String destination) {
        if (destination == null)
            return false;
        return destination.trim().toLowerCase(Locale.ROOT).startsWith(":fail:");
    }

    private static String normalizeAddress(String value) {
        return AliasRegistry.normalizeAddress(value);
    }

    private static String domain(String address) {
        if (address == null)
            return null;
        int at = address.lastIndexOf('@');
        if (at <= 0 || at + 1 >= address.length())
            return null;
        return address.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    private static String localPart(String address) {
        int at = address == null ? -1 : address.lastIndexOf('@');
        return at <= 0 ? address : address.substring(0, at);
    }

    public static final class Config {
        public final String baseUrl;
        public final String username;
        public final String apiToken;

        public Config(String baseUrl, String username, String apiToken) {
            if (TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(username) || TextUtils.isEmpty(apiToken))
                throw new IllegalArgumentException("cPanel config incomplete");
            String base = baseUrl.trim();
            while (base.endsWith("/"))
                base = base.substring(0, base.length() - 1);
            this.baseUrl = base;
            this.username = username.trim();
            this.apiToken = apiToken;
        }
    }

    static final class Probe {
        final List<Route> routes;
        final boolean rejected;

        Probe(List<Route> routes, boolean rejected) {
            this.routes = routes;
            this.rejected = rejected;
        }
    }

    static final class Route {
        final String source;
        final String destination;
        final boolean fail;

        Route(String source, String destination, boolean fail) {
            this.source = source;
            this.destination = destination;
            this.fail = fail;
        }
    }

    private static final class Snapshot {
        final String address;
        final List<Route> routes;

        Snapshot(String address, List<Route> routes) {
            this.address = address;
            this.routes = routes;
        }
    }
}

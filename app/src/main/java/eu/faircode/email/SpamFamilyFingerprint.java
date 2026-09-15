/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package eu.faircode.email;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mutation-resistant hashed features for spam-family matching. */
public final class SpamFamilyFingerprint {
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s\\\"'<>]+|www\\.[^\\s\\\"'<>]+");
    private static final Pattern EMAIL = Pattern.compile("(?i)[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}])[+\\-]?\\d[\\d.,:/\\-]*(?![\\p{L}])");
    private static final Pattern LONG_ID = Pattern.compile("(?i)\\b[a-z0-9_-]{12,}\\b");
    private static final Pattern TAG = Pattern.compile("(?is)<\\s*(/?)\\s*([a-z0-9]+)(?:\\s[^>]*)?>");
    private static final Pattern HREF = Pattern.compile("(?is)href\\s*=\\s*([\\\"'])(.*?)\\1");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}<>]+(?:['’][\\p{L}]+)?");

    private static final int SERIAL_MAGIC = 0x53464631; // SFF1
    private static final int MAX_SERIALIZED_FEATURES = 100_000;

    final Set<Long> text;
    final Set<Long> structure;
    final Set<Long> links;
    final Set<Long> sender;

    private SpamFamilyFingerprint(Set<Long> text, Set<Long> structure,
                                  Set<Long> links, Set<Long> sender) {
        this.text = immutable(text);
        this.structure = immutable(structure);
        this.links = immutable(links);
        this.sender = immutable(sender);
    }

    public static SpamFamilyFingerprint fromRaw(String senderAddress,
                                                 String senderName,
                                                 String subject,
                                                 String plainText,
                                                 String html) {
        Set<Long> text = new HashSet<>();
        addText(text, subject, "s");
        addText(text, plainText, "b");
        if ((plainText == null || plainText.trim().isEmpty()) && html != null)
            addText(text, stripHtml(html), "b");

        Set<Long> structure = new HashSet<>();
        addStructure(structure, html);

        Set<Long> links = new HashSet<>();
        addLinks(links, html);
        addLinks(links, plainText);

        Set<Long> sender = new HashSet<>();
        addSender(sender, senderAddress, senderName);
        return new SpamFamilyFingerprint(text, structure, links, sender);
    }

    /** Compact deterministic representation containing hashes only, never raw mail text. */
    public byte[] toBytes() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(SERIAL_MAGIC);
            writeSet(out, text);
            writeSet(out, structure);
            writeSet(out, links);
            writeSet(out, sender);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException ex) {
            // ByteArrayOutputStream should not throw, but keep the contract explicit.
            throw new IllegalStateException(ex);
        }
    }

    public static SpamFamilyFingerprint fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 4)
            throw new IllegalArgumentException("fingerprint bytes");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != SERIAL_MAGIC)
                throw new IllegalArgumentException("unsupported fingerprint format");
            Set<Long> text = readSet(in);
            Set<Long> structure = readSet(in);
            Set<Long> links = readSet(in);
            Set<Long> sender = readSet(in);
            if (in.available() != 0)
                throw new IllegalArgumentException("trailing fingerprint data");
            return new SpamFamilyFingerprint(text, structure, links, sender);
        } catch (IOException ex) {
            throw new IllegalArgumentException("invalid fingerprint", ex);
        }
    }

    private static void writeSet(DataOutputStream out, Set<Long> values) throws IOException {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        out.writeInt(sorted.size());
        for (Long value : sorted)
            out.writeLong(value);
    }

    private static Set<Long> readSet(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_SERIALIZED_FEATURES)
            throw new IllegalArgumentException("feature count=" + count);
        Set<Long> values = new HashSet<>(Math.max(16, count * 2));
        for (int i = 0; i < count; i++)
            values.add(in.readLong());
        return values;
    }

    public int evidenceCount() {
        return text.size() + structure.size() + links.size() + sender.size();
    }

    public int textFeatureCount() { return text.size(); }
    public int structureFeatureCount() { return structure.size(); }
    public int linkFeatureCount() { return links.size(); }
    public int senderFeatureCount() { return sender.size(); }

    static String normalizeVolatile(String raw) {
        String value = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        value = URL.matcher(value).replaceAll(" <url> ");
        value = EMAIL.matcher(value).replaceAll(" <email> ");
        value = NUMBER.matcher(value).replaceAll(" <num> ");
        value = LONG_ID.matcher(value).replaceAll(" <id> ");
        return value.replaceAll("\\s+", " ").trim();
    }

    private static void addText(Set<Long> out, String raw, String channel) {
        if (raw == null || raw.trim().isEmpty()) return;
        List<String> tokens = tokens(normalizeVolatile(raw));
        for (String token : tokens)
            out.add(hash(channel + ":u:" + token));
        for (int width = 2; width <= 4; width++)
            for (int i = 0; i + width <= tokens.size(); i++) {
                StringBuilder sb = new StringBuilder(channel).append(':').append(width).append(':');
                for (int j = 0; j < width; j++) {
                    if (j > 0) sb.append(' ');
                    sb.append(tokens.get(i + j));
                }
                out.add(hash(sb.toString()));
            }
    }

    private static void addStructure(Set<Long> out, String html) {
        if (html == null || html.trim().isEmpty()) return;
        List<String> tags = new ArrayList<>();
        Matcher matcher = TAG.matcher(html);
        while (matcher.find()) {
            String tag = matcher.group(2).toLowerCase(Locale.ROOT);
            if ("script".equals(tag) || "style".equals(tag)) continue;
            String marker = (matcher.group(1).isEmpty() ? "+" : "-") + tag;
            tags.add(marker);
            out.add(hash("tag:" + marker));
        }
        for (int width = 2; width <= 5; width++)
            for (int i = 0; i + width <= tags.size(); i++) {
                StringBuilder sb = new StringBuilder("dom:");
                for (int j = 0; j < width; j++) {
                    if (j > 0) sb.append('/');
                    sb.append(tags.get(i + j));
                }
                out.add(hash(sb.toString()));
            }

        String lower = html.toLowerCase(Locale.ROOT);
        addCount(out, "table", count(lower, "<table"));
        addCount(out, "img", count(lower, "<img"));
        addCount(out, "a", count(lower, "<a"));
        addCount(out, "button", count(lower, "<button"));
    }

    private static void addCount(Set<Long> out, String name, int count) {
        int bucket = count == 0 ? 0 : count == 1 ? 1 : count <= 3 ? 2 : count <= 7 ? 3 : 4;
        out.add(hash("count:" + name + ':' + bucket));
    }

    private static int count(String text, String needle) {
        int result = 0, from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) return result;
            result++;
            from = at + needle.length();
        }
    }

    private static void addLinks(Set<Long> out, String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        Matcher href = HREF.matcher(raw);
        while (href.find()) addLink(out, href.group(2));
        Matcher url = URL.matcher(raw);
        while (url.find()) addLink(out, url.group());
    }

    private static void addLink(Set<Long> out, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("www.")) value = "https://" + value;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host != null) {
                host = host.toLowerCase(Locale.ROOT);
                if (host.startsWith("www.")) host = host.substring(4);
                out.add(hash("host:" + host));
                out.add(hash("domain:" + lastLabels(host, 2)));
            }
            String path = uri.getPath();
            if (path != null) {
                String[] segments = path.split("/");
                int emitted = 0;
                for (String segment : segments) {
                    String normalized = LONG_ID.matcher(NUMBER.matcher(segment.toLowerCase(Locale.ROOT))
                            .replaceAll("<n>")).replaceAll("<id>");
                    if (normalized.isEmpty()) continue;
                    out.add(hash("path:" + emitted + ':' + normalized));
                    if (++emitted >= 3) break;
                }
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static void addSender(Set<Long> out, String address, String name) {
        if (address != null) {
            String lower = address.trim().toLowerCase(Locale.ROOT);
            int at = lower.lastIndexOf('@');
            if (at > 0 && at + 1 < lower.length()) {
                String local = lower.substring(0, at);
                String domain = lower.substring(at + 1);
                out.add(hash("sdomain:" + domain));
                out.add(hash("sbase:" + lastLabels(domain, 2)));
                out.add(hash("slocal-shape:" + shape(local)));
            }
        }
        if (name != null)
            for (String token : tokens(normalizeVolatile(name)))
                out.add(hash("sname:" + token));
    }

    private static String shape(String value) {
        StringBuilder out = new StringBuilder();
        char previous = 0;
        int run = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            char type = Character.isLetter(c) ? 'a' : Character.isDigit(c) ? '9' : 'x';
            if (type == previous) run++;
            else {
                appendRun(out, previous, run);
                previous = type;
                run = 1;
            }
        }
        appendRun(out, previous, run);
        return out.toString();
    }

    private static void appendRun(StringBuilder out, char type, int count) {
        if (count > 0) out.append(type).append(Math.min(8, count));
    }

    private static String lastLabels(String host, int labels) {
        String[] parts = host.split("\\.");
        if (parts.length <= labels) return host;
        StringBuilder out = new StringBuilder();
        for (int i = parts.length - labels; i < parts.length; i++) {
            if (out.length() > 0) out.append('.');
            out.append(parts[i]);
        }
        return out.toString();
    }

    private static String stripHtml(String html) {
        return html.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ");
    }

    private static List<String> tokens(String value) {
        List<String> result = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(value);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() > 1 || token.startsWith("<")) result.add(token);
        }
        return result;
    }

    private static Set<Long> immutable(Set<Long> value) {
        return Collections.unmodifiableSet(new HashSet<>(value));
    }

    private static long hash(String value) {
        // FNV-1a 64: stable across processes/platforms and cheap enough for message ingestion.
        long hash = 0xcbf29ce484222325L;
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}

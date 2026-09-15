package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;
import android.text.TextUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persistent, local, sanitized diagnostics for Spam Control.
 *
 * Never log API tokens, Authorization headers or message bodies here.
 */
public final class SpamControlLog {
    private static final String FILE_NAME = "spam-control-diagnostics.log";
    private static final long MAX_BYTES = 512 * 1024;
    private static final int MAX_MESSAGE_CHARS = 6000;
    private static final Object LOCK = new Object();

    private SpamControlLog() {
    }

    public static void i(Context context, String category, String message) {
        append(context, "INFO", category, message, null);
    }

    public static void d(Context context, String category, String message) {
        append(context, "DEBUG", category, message, null);
    }

    public static void t(Context context, String category, String message) {
        append(context, "TRACE", category, message, null);
    }

    public static void w(Context context, String category, String message) {
        append(context, "WARN", category, message, null);
    }

    public static void e(Context context, String category, String message, Throwable error) {
        append(context, "ERROR", category, message, error);
    }

    public static String read(Context context) {
        if (context == null)
            return "";
        synchronized (LOCK) {
            try {
                File file = file(context);
                if (!file.exists())
                    return "";
                byte[] bytes = new byte[(int) Math.min(file.length(), MAX_BYTES)];
                try (FileInputStream input = new FileInputStream(file)) {
                    int offset = 0;
                    while (offset < bytes.length) {
                        int read = input.read(bytes, offset, bytes.length - offset);
                        if (read < 0)
                            break;
                        offset += read;
                    }
                    return new String(bytes, 0, offset, StandardCharsets.UTF_8);
                }
            } catch (Throwable ex) {
                Log.e(ex);
                return "Kunne ikke lese diagnostikkloggen: " + ex.getClass().getSimpleName();
            }
        }
    }

    public static void clear(Context context) {
        if (context == null)
            return;
        synchronized (LOCK) {
            try {
                File file = file(context);
                if (file.exists() && !file.delete())
                    new FileOutputStream(file, false).close();
            } catch (Throwable ex) {
                Log.e(ex);
            }
        }
    }

    public static String sanitize(String value) {
        if (value == null)
            return "";
        String clean = value;
        clean = clean.replaceAll("(?i)(authorization\\s*[:=]\\s*cpanel\\s+[^:]+:)[^\\s,;\\\"]+", "$1<redacted>");
        clean = clean.replaceAll("(?i)((?:api[_ -]?token|token|password)\\s*[:=]\\s*)[^\\s,;\\\"]+", "$1<redacted>");
        clean = clean.replaceAll("(?i)([?&](?:api[_-]?token|token|password)=)[^&\\s]+", "$1<redacted>");
        clean = clean.replace('\r', ' ').trim();
        if (clean.length() > MAX_MESSAGE_CHARS)
            clean = clean.substring(0, MAX_MESSAGE_CHARS) + " …<truncated>";
        return clean;
    }

    private static void append(Context context, String level, String category,
                               String message, Throwable error) {
        if (context == null)
            return;
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            try {
                File file = file(app);
                if (file.exists() && file.length() > MAX_BYTES)
                    rotate(file);

                String timestamp = new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
                StringBuilder line = new StringBuilder();
                line.append(timestamp)
                        .append(" [").append(level).append("] [")
                        .append(TextUtils.isEmpty(category) ? "GENERAL" : category)
                        .append("] ")
                        .append(sanitize(message));
                if (error != null) {
                    line.append(" | ")
                            .append(error.getClass().getSimpleName());
                    if (!TextUtils.isEmpty(error.getMessage()))
                        line.append(": ").append(sanitize(error.getMessage()));
                }
                line.append('\n');

                try (FileOutputStream output = new FileOutputStream(file, true)) {
                    output.write(line.toString().getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            } catch (Throwable ex) {
                Log.e(ex);
            }
        }
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static void rotate(File file) throws Exception {
        File old = new File(file.getParentFile(), FILE_NAME + ".old");
        if (old.exists())
            old.delete();
        if (!file.renameTo(old))
            new FileOutputStream(file, false).close();
    }
}

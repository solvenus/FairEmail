package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import javax.mail.Address;
import javax.mail.internet.InternetAddress;

/** Converts a locally stored FairEmail message into spam intelligence features. */
public final class SpamFamilyMessageAdapter {
    // Enough to preserve long newsletter/template structure while bounding work
    // on hostile or pathological HTML bodies.
    private static final int MAX_HTML_CHARS = 1_500_000;

    private SpamFamilyMessageAdapter() {
    }

    public static SpamFamilyIdentity.Identity identityFromMessage(EntityMessage message) {
        if (message == null)
            return null;
        String senderName = null;
        Address[] from = message.from;
        if (from != null && from.length > 0 && from[0] instanceof InternetAddress)
            senderName = ((InternetAddress) from[0]).getPersonal();
        return SpamFamilyIdentity.fromRaw(senderName, message.subject);
    }

    public static SpamFamilyFingerprint fromMessage(Context context, EntityMessage message) {
        if (context == null || message == null)
            return null;

        String senderAddress = null;
        String senderName = null;
        Address[] from = message.from;
        if (from != null && from.length > 0 && from[0] instanceof InternetAddress) {
            InternetAddress internet = (InternetAddress) from[0];
            senderAddress = internet.getAddress();
            senderName = internet.getPersonal();
        }

        String html = null;
        String plain = null;
        try {
            File file = message.getFile(context);
            if (file.exists()) {
                html = readPrefix(file, MAX_HTML_CHARS);
                if (html != null && !html.isEmpty())
                    plain = HtmlHelper.getFullText(context, html);
            }
        } catch (Throwable ex) {
            // Header/subject features are still useful. Body parsing must never
            // make an explicit spam action fail.
            Log.w(ex);
        }

        SpamFamilyFingerprint fingerprint = SpamFamilyFingerprint.fromRaw(
                senderAddress,
                senderName,
                message.subject,
                plain,
                html);
        return fingerprint.evidenceCount() == 0 ? null : fingerprint;
    }

    static String readPrefix(File file, int maxChars) throws IOException {
        if (file == null || maxChars <= 0)
            return null;

        StringBuilder text = new StringBuilder(Math.min(maxChars, 64 * 1024));
        char[] buffer = new char[8192];
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            while (text.length() < maxChars) {
                int wanted = Math.min(buffer.length, maxChars - text.length());
                int count = reader.read(buffer, 0, wanted);
                if (count < 0)
                    break;
                text.append(buffer, 0, count);
            }
        }
        return text.toString();
    }
}

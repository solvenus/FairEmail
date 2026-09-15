package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

/** Central persisted policy for the human-facing Spam Control surface. */
public final class SpamControlPolicy {
    public static final String PREF_AUTO_ADVANCE = "spam_control_auto_advance";
    public static final String PREF_HIDE_REVIEWED = "spam_control_hide_reviewed";
    public static final String PREF_SHOW_TECHNICAL = "spam_control_show_technical";
    public static final String PREF_EXACT_FAMILY = "spam_control_exact_family";
    public static final String PREF_AUTO_LABEL_EXACT = "spam_control_auto_label_exact";
    public static final String PREF_MARK_ALIAS_COMPROMISED = "spam_control_mark_alias_compromised";
    public static final String PREF_FOREIGN_SENDER_EVIDENCE = "spam_control_foreign_sender_evidence";
    public static final String PREF_SMTP_BURN_ENABLED = "spam_control_smtp_burn_enabled";

    public static final String PREF_CPANEL_BASE_URL = "spam_control_cpanel_base_url";
    public static final String PREF_CPANEL_USERNAME = "spam_control_cpanel_username";
    public static final String PREF_CPANEL_TOKEN = "spam_control_cpanel_token";

    private SpamControlPolicy() {
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public static boolean autoAdvance(Context context) {
        return prefs(context).getBoolean(PREF_AUTO_ADVANCE, true);
    }

    public static boolean hideReviewed(Context context) {
        return prefs(context).getBoolean(PREF_HIDE_REVIEWED, true);
    }

    public static boolean showTechnical(Context context) {
        return prefs(context).getBoolean(PREF_SHOW_TECHNICAL, false);
    }

    public static boolean exactFamilyDetection(Context context) {
        return prefs(context).getBoolean(PREF_EXACT_FAMILY, true);
    }

    public static boolean autoLabelExact(Context context) {
        return prefs(context).getBoolean(PREF_AUTO_LABEL_EXACT, false);
    }

    public static boolean markAliasCompromised(Context context) {
        return prefs(context).getBoolean(PREF_MARK_ALIAS_COMPROMISED, true);
    }

    public static boolean foreignSenderEvidence(Context context) {
        return prefs(context).getBoolean(PREF_FOREIGN_SENDER_EVIDENCE, true);
    }

    public static boolean smtpBurnEnabled(Context context) {
        return prefs(context).getBoolean(PREF_SMTP_BURN_ENABLED, true);
    }

    public static void setBoolean(Context context, String key, boolean value) {
        prefs(context).edit().putBoolean(key, value).apply();
    }

    public static String cpanelBaseUrl(Context context) {
        return prefs(context).getString(PREF_CPANEL_BASE_URL, "");
    }

    public static String cpanelUsername(Context context) {
        return prefs(context).getString(PREF_CPANEL_USERNAME, "");
    }

    public static String cpanelToken(Context context) {
        return prefs(context).getString(PREF_CPANEL_TOKEN, "");
    }

    public static void setCpanelConfig(Context context, String baseUrl, String username, String token) {
        prefs(context).edit()
                .putString(PREF_CPANEL_BASE_URL, clean(baseUrl))
                .putString(PREF_CPANEL_USERNAME, clean(username))
                .putString(PREF_CPANEL_TOKEN, token == null ? "" : token)
                .apply();
    }

    public static boolean hasCpanelConfig(Context context) {
        return !cpanelBaseUrl(context).isEmpty() &&
                !cpanelUsername(context).isEmpty() &&
                !cpanelToken(context).isEmpty();
    }

    public static CpanelAliasActuator.Config cpanelConfig(Context context) {
        if (!hasCpanelConfig(context))
            return null;
        try {
            return new CpanelAliasActuator.Config(
                    cpanelBaseUrl(context), cpanelUsername(context), cpanelToken(context));
        } catch (Throwable ex) {
            Log.w(ex);
            return null;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}

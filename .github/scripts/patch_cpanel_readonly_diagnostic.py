from pathlib import Path

ACT = Path('app/src/main/java/eu/faircode/email/CpanelAliasActuator.java')
UI = Path('app/src/main/java/eu/faircode/email/ActivitySpamControl.java')


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one match, found {count}')
    return text.replace(old, new, 1)

act = ACT.read_text()
needle = '''    private String homeDirectory() {
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
'''
replacement = '''    /**
     * Read-only connectivity/authentication diagnostic for the Spam Control UI.
     * It never calls add_forwarder/delete_forwarder or any other mutation API.
     */
    public Diagnostic diagnose(String sampleAddress) {
        String sample = normalizeAddress(sampleAddress);
        try {
            JSONObject root = call("Variables", "get_user_information",
                    Collections.<String, String>emptyMap());
            JSONObject result = root.optJSONObject("result");
            JSONObject data = result == null ? null : result.optJSONObject("data");
            if (data == null)
                return Diagnostic.failed("cPanel response missing user information");

            String home = first(data, "HOMEDIR", "homedir", "HOME", "home");
            if (TextUtils.isEmpty(home))
                home = null;

            if (sample == null)
                return Diagnostic.ok(home != null, null, -1, false);

            Probe probe = probe(sample);
            return Diagnostic.ok(home != null, sample, probe.routes.size(), true);
        } catch (Throwable ex) {
            return Diagnostic.failed(diagnosticError(ex));
        }
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
'''
act = replace_once(act, needle, replacement, 'diagnose-insertion')

needle = '''    private static String isFail''' if False else '''    private static boolean isFail(String destination) {
'''
# Insert a credential-safe diagnostic error formatter immediately before isFail.
old = '''    private static boolean isFail(String destination) {
'''
new = '''    private static String diagnosticError(Throwable ex) {
        String value = ex == null
                ? "unknown-error"
                : ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage());
        value = value.replaceAll("(?i)(authorization|token|password)\\\\s*[:=]\\\\s*[^\\\\s,;]+", "$1=<redacted>");
        value = value.replace('\\n', ' ').replace('\\r', ' ').trim();
        return value.length() <= 300 ? value : value.substring(0, 300);
    }

    private static boolean isFail(String destination) {
'''
act = replace_once(act, old, new, 'diagnostic-error')

old = '''    public static final class Config {
'''
new = '''    public static final class Diagnostic {
        public final boolean success;
        public final boolean homeDirectoryKnown;
        public final String sampleAddress;
        public final int exactRouteCount;
        public final boolean forwarderReadTested;
        public final String error;

        private Diagnostic(boolean success, boolean homeDirectoryKnown,
                           String sampleAddress, int exactRouteCount,
                           boolean forwarderReadTested, String error) {
            this.success = success;
            this.homeDirectoryKnown = homeDirectoryKnown;
            this.sampleAddress = sampleAddress;
            this.exactRouteCount = exactRouteCount;
            this.forwarderReadTested = forwarderReadTested;
            this.error = error;
        }

        static Diagnostic ok(boolean homeDirectoryKnown, String sampleAddress,
                             int exactRouteCount, boolean forwarderReadTested) {
            return new Diagnostic(true, homeDirectoryKnown, sampleAddress,
                    exactRouteCount, forwarderReadTested, null);
        }

        static Diagnostic failed(String error) {
            return new Diagnostic(false, false, null, -1, false, error);
        }
    }

    public static final class Config {
'''
act = replace_once(act, old, new, 'diagnostic-type')
ACT.write_text(act)

ui = UI.read_text()
old = '''        Button configure = secondaryButton("Konfigurer cPanel");
        configure.setOnClickListener(v -> showCpanelDialog());
        cb.addView(configure, matchWrapWithMargin(0, 7, 0, 0));
        cpanel.addView(cb, matchWrap());
'''
new = '''        Button configure = secondaryButton("Konfigurer cPanel");
        configure.setOnClickListener(v -> showCpanelDialog());
        cb.addView(configure, matchWrapWithMargin(0, 7, 0, 0));
        Button testCpanel = secondaryButton("Test cPanel (kun lesing)");
        testCpanel.setEnabled(SpamControlPolicy.hasCpanelConfig(this));
        testCpanel.setOnClickListener(v -> testCpanelConnection());
        cb.addView(testCpanel, matchWrapWithMargin(0, 5, 0, 0));
        cpanel.addView(cb, matchWrap());
'''
ui = replace_once(ui, old, new, 'settings-test-button')

marker = '''    private void showCpanelDialog() {
'''
method = '''    private void testCpanelConnection() {
        CpanelAliasActuator.Config config = SpamControlPolicy.cpanelConfig(this);
        if (config == null) {
            tvStatus.setText("Konfigurer cPanel først.");
            showCpanelDialog();
            return;
        }

        String sample = null;
        for (EntityAlias alias : aliases)
            if (alias != null && !TextUtils.isEmpty(alias.address)) {
                sample = alias.address;
                break;
            }
        final String sampleAddress = sample;

        tvStatus.setText("Tester cPanel med leseoperasjoner …");
        executor.execute(() -> {
            CpanelAliasActuator.Diagnostic diagnostic =
                    new CpanelAliasActuator(config).diagnose(sampleAddress);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed())
                    return;
                if (!diagnostic.success) {
                    tvStatus.setText("cPanel-test feilet: " + diagnostic.error);
                    new AlertDialog.Builder(this)
                            .setTitle("cPanel-test feilet")
                            .setMessage(diagnostic.error)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }

                StringBuilder report = new StringBuilder();
                report.append("Autentisering / UAPI: OK")
                        .append("\\nHome directory tilgjengelig: ")
                        .append(diagnostic.homeDirectoryKnown ? "ja" : "nei");
                if (diagnostic.forwarderReadTested)
                    report.append("\\nEmail/list_forwarders: OK")
                            .append("\\nTestalias: ").append(diagnostic.sampleAddress)
                            .append("\\nEksakte eksplisitte routes: ")
                            .append(diagnostic.exactRouteCount);
                else
                    report.append("\\nEmail/list_forwarders: ikke testet — ingen aliaser er observert ennå.");
                report.append("\\n\\nIngen serverdata ble endret.");

                tvStatus.setText("cPanel-test OK. Ingen serverdata ble endret.");
                new AlertDialog.Builder(this)
                        .setTitle("cPanel-test OK")
                        .setMessage(report.toString())
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

'''
if ui.count(marker) != 1:
    raise SystemExit(f'cpanel-dialog-marker: expected one match, found {ui.count(marker)}')
ui = ui.replace(marker, method + marker, 1)
UI.write_text(ui)

print('cPanel read-only diagnostic patch applied')

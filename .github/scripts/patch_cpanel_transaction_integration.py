from pathlib import Path

ACT = Path('app/src/main/java/eu/faircode/email/CpanelAliasActuator.java')
UI = Path('app/src/main/java/eu/faircode/email/ActivitySpamControl.java')


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one match, found {count}')
    return text.replace(old, new, 1)

act = ACT.read_text()

old = '''/**
 * cPanel UAPI implementation of AliasServerActuator.
 *
 * V1 only mutates aliases that have no explicit non-fail forwarders. This is
 * deliberately conservative: an alias handled by catch-all/default routing can
 * safely gain an exact :fail: route without destroying a pre-existing route.
 * Explicit route replacement/restore is deferred until exercised against the
 * user's real cPanel server and version.
 */
public final class CpanelAliasActuator implements AliasServerActuator {
    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 20_000;

    private final Config config;

    public CpanelAliasActuator(Config config) {
        if (config == null)
            throw new IllegalArgumentException("config");
        this.config = config;
    }
'''
new = '''/**
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
'''
act = replace_once(act, old, new, 'actuator-header')

start = act.index('    @Override\n    public Result burn(String address, String failureMessage) throws Exception {')
end = act.index('    /** Read-only remote inspection used by burn/restore and later UI diagnostics. */', start)
old_methods = act[start:end]
new_methods = '''    @Override
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

'''
act = act[:start] + new_methods + act[end:]
ACT.write_text(act)

ui = UI.read_text()
old_ui = '''    private void burnAlias(EntityAlias alias) {
        EntityAccount account = selectedAccount;
        CpanelAliasActuator.Config config = SpamControlPolicy.cpanelConfig(this);
        if (account == null || config == null)
            return;
        tvStatus.setText("Oppretter og verifiserer SMTP hard-fail …");
        executor.execute(() -> {
            AliasBurnManager.Outcome outcome = AliasBurnManager.burn(
                    getApplicationContext(), account.uuid, alias.address,
                    new CpanelAliasActuator(config), AliasBurnManager.DEFAULT_FAILURE_MESSAGE);
            runOnUiThread(() -> tvStatus.setText(outcome.success
                    ? "SMTP hard-fail er verifisert for " + alias.address
                    : "SMTP-burn feilet: " + outcome.error));
        });
    }
'''
new_ui = '''    private void burnAlias(EntityAlias alias) {
        burnAlias(alias, false);
    }

    private void burnAlias(EntityAlias alias, boolean allowUnsafe) {
        EntityAccount account = selectedAccount;
        CpanelAliasActuator.Config config = SpamControlPolicy.cpanelConfig(this);
        if (account == null || config == null)
            return;
        tvStatus.setText(allowUnsafe
                ? "Overstyrer rollback-kravet og oppretter SMTP hard-fail …"
                : "Oppretter og verifiserer SMTP hard-fail …");
        executor.execute(() -> {
            AliasBurnManager.Outcome outcome = AliasBurnManager.burn(
                    getApplicationContext(), account.uuid, alias.address,
                    new CpanelAliasActuator(config, allowUnsafe),
                    AliasBurnManager.DEFAULT_FAILURE_MESSAGE);
            runOnUiThread(() -> {
                if (!isSelected(account))
                    return;
                if (!outcome.success && !allowUnsafe &&
                        outcome.error != null && outcome.error.startsWith("unsafe-required:")) {
                    tvStatus.setText("Eksisterende cPanel-routing kan ikke garanteres gjenopprettet automatisk.");
                    showUnsafeBurnDialog(alias, outcome.error.substring("unsafe-required:".length()));
                } else
                    tvStatus.setText(outcome.success
                            ? "SMTP hard-fail er verifisert for " + alias.address
                            : "SMTP-burn feilet: " + outcome.error);
                loadDashboardExtras(account);
                renderCurrent();
            });
        });
    }

    private void showUnsafeBurnDialog(EntityAlias alias, String reason) {
        new AlertDialog.Builder(this)
                .setTitle("Burn uten automatisk rollback?")
                .setMessage(alias.address +
                        "\\n\\nDen eksisterende cPanel-ruten kan slettes, men Spamkontroll kan ikke bevise at samme rute kan rekonstrueres automatisk etterpå." +
                        "\\n\\nÅrsak: " + empty(reason, "ukjent route") +
                        "\\n\\nHvis du fortsetter, forsøker Spamkontroll fortsatt read-back av hard-fail. Ved en feil etter at gammel routing er slettet kan automatisk rollback være umulig.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("BURN UTEN ROLLBACK", (d, w) -> burnAlias(alias, true))
                .show();
    }
'''
ui = replace_once(ui, old_ui, new_ui, 'burn-ui')

old_restore = '''            runOnUiThread(() -> tvStatus.setText(outcome.success
                    ? "SMTP-mottak er verifisert gjenopprettet."
                    : "Gjenoppretting feilet: " + outcome.error));
'''
new_restore = '''            runOnUiThread(() -> {
                if (!isSelected(account))
                    return;
                tvStatus.setText(outcome.success
                        ? "SMTP-mottak er verifisert gjenopprettet."
                        : "Gjenoppretting feilet: " + outcome.error);
                loadDashboardExtras(account);
                renderCurrent();
            });
'''
ui = replace_once(ui, old_restore, new_restore, 'restore-ui-refresh')
UI.write_text(ui)

print('cPanel transaction integration patch applied')

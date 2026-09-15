from pathlib import Path

P = Path('app/src/main/java/eu/faircode/email/CpanelAliasActuator.java')
text = P.read_text()

old = '''        Map<String, String> args = new TreeMap<>();
        args.put("domain", domain);
        // Filtering is an optimization only; exact matching is still performed
        // locally because the remote filter is not a security boundary.
        args.put("regex", localPart(normalized));
        JSONObject root = call("Email", "list_forwarders", args);
'''
new = '''        Map<String, String> args = new TreeMap<>();
        args.put("domain", domain);
        // cPanel defines regex as PCRE. An alias local-part is data, not a
        // regular expression: '+', '.', '(' and friends must never change
        // which routes are returned. Fetch the domain routes and enforce the
        // exact normalized source-address match locally below.
        JSONObject root = call("Email", "list_forwarders", args);
'''
count = text.count(old)
if count != 1:
    raise SystemExit(f'probe-regex: expected one exact block, found {count}')
text = text.replace(old, new, 1)

# localPart is no longer used anywhere in this actuator. Remove it fail-closed.
old_helper = '''    private static String localPart(String address) {
        int at = address == null ? -1 : address.lastIndexOf('@');
        return at <= 0 ? address : address.substring(0, at);
    }

'''
count = text.count(old_helper)
if count != 1:
    raise SystemExit(f'localPart-helper: expected one exact block, found {count}')
text = text.replace(old_helper, '', 1)

P.write_text(text)
print('cPanel exact probe patch applied')

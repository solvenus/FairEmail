from pathlib import Path

path = Path('app/src/main/java/eu/faircode/email/ActivitySpamControl.java')
text = path.read_text(encoding='utf-8')

old_desc = '''        if (bucket == 1)\n            return "Aktive aliaser med legitim historikk og uten spamtrafikk.";\n        return "Aliaser som ennå ikke har nok menneskelig læring til å være legitime eller spamrammede.";'''
new_desc = '''        if (bucket == 1)\n            return "Aktive aliaser med Innboks-trafikk eller eksplisitt Ikke spam, uten spam/problemstate.";\n        return "Aliaser som foreløpig mangler nok sunn trafikk eller spam-evidens til å plasseres sikkert.";'''
if text.count(old_desc) != 1:
    raise SystemExit('alias bucket description signature mismatch')
text = text.replace(old_desc, new_desc, 1)

old_bucket = '''        if (alias.state == EntityAlias.STATE_ACTIVE && ham > 0)\n            return 1;\n\n        return 2;\n    }'''
new_bucket = '''        if (alias.state == EntityAlias.STATE_ACTIVE &&\n                (ham > 0 || aliasHasInboxTraffic(alias)))\n            return 1;\n\n        return 2;\n    }\n\n    private boolean aliasHasInboxTraffic(EntityAlias alias) {\n        if (alias == null || TextUtils.isEmpty(alias.folder_counts))\n            return false;\n        try {\n            org.json.JSONObject counts = new org.json.JSONObject(alias.folder_counts);\n            return counts.optInt(EntityFolder.INBOX, 0) > 0;\n        } catch (Throwable ex) {\n            Log.w(ex);\n            return false;\n        }\n    }'''
if text.count(old_bucket) != 1:
    raise SystemExit('alias bucket classification signature mismatch')
text = text.replace(old_bucket, new_bucket, 1)

path.write_text(text, encoding='utf-8')
print('alias bucket inbox evidence patch complete')

from pathlib import Path

TX = Path('app/src/main/java/eu/faircode/email/CpanelForwarderTransaction.java')
LAB = Path('tools/spam-family-lab/CpanelForwarderTransactionLab.java')


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one match, found {count}')
    return text.replace(old, new, 1)

tx = TX.read_text()
tx = replace_once(
    tx,
    '        if (containsFail(before))\n            return new Result(true, false, false, false, false, null, before, before);\n',
    '        // A mixed fail + forwarding route set is not a proven hard reject.\n'
    '        // Only a pure fail route is already SMTP-dead; mixed routes must be\n'
    '        // transactionally replaced and read back as pure fail.\n'
    '        if (isPureFail(before))\n'
    '            return new Result(true, false, false, false, false, null, before, before);\n',
    'pure-fail-check')
TX.write_text(tx)

lab = LAB.read_text()
needle = '''        FakeBackend alreadyDead = new FakeBackend(":fail: Existing reject");
        CpanelForwarderTransaction.Result noChange = CpanelForwarderTransaction.burn(
                alreadyDead, address, "dead", home, false);
        require(noChange.success && !noChange.changed && alreadyDead.deleteCalls == 0,
                "already-dead alias must be no-op");

'''
replacement = needle + '''        FakeBackend mixedFail = new FakeBackend(
                ":fail: Existing reject", "still-forwarded@example.net");
        CpanelForwarderTransaction.Result mixed = CpanelForwarderTransaction.burn(
                mixedFail, address, "dead", home, false);
        require(mixed.success && mixed.changed,
                "mixed fail+forward must be replaced, not accepted as already dead");
        require(mixedFail.routes.size() == 1 && mixedFail.routes.get(0).startsWith(":fail:"),
                "mixed fail+forward must end as pure fail route");
        require(mixedFail.deleteCalls == 2,
                "mixed fail+forward must delete every pre-existing route");

'''
lab = replace_once(lab, needle, replacement, 'mixed-fail-regression')
lab = replace_once(
    lab,
    '        System.out.println("PASS cpanel-transaction safeBurn+restore+rollback+unsafeVeto+pipe");\n',
    '        System.out.println("PASS cpanel-transaction safeBurn+restore+rollback+unsafeVeto+pipe+pureFail");\n',
    'lab-summary')
LAB.write_text(lab)
print('cPanel pure-fail semantics patch applied')

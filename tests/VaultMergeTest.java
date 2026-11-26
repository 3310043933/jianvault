package cn.local.simpleauth;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.crypto.KeyGenerator;

public final class VaultMergeTest {
    private static int count;
    private static void check(boolean ok, String message) { count++; if (!ok) throw new AssertionError(message); }
    private static VaultState state(List<Otp.Account> accounts, PasswordGroup... groups) { return new VaultState(accounts, Arrays.asList(groups)); }
    private static Otp.Account copy(Otp.Account a, String issuer, String name, String secret, String algorithm, int digits, int period,
        List<RecoveryCodes.Entry> codes, LoginDetails login, String group) {
        return new Otp.Account(issuer, name, secret, algorithm, digits, period, codes, login, a.passwordOnly, group);
    }
    private static Otp.Account variant(Otp.Account a, String field) {
        String issuer = a.issuer, name = a.name, secret = a.secret, algorithm = a.algorithm, group = a.groupId;
        int digits = a.digits, period = a.period; List<RecoveryCodes.Entry> codes = a.recoveryCodes; LoginDetails l = a.login;
        switch (field) {
            case "title": issuer += "-changed"; break;
            case "name": name += "-changed"; break;
            case "secret": secret = "JBSWY3DPEHPK3PXP"; break;
            case "algorithm": algorithm = "SHA256"; break;
            case "digits": digits = 8; break;
            case "period": period = 60; break;
            case "group": group = "personal-1"; break;
            case "website": l = new LoginDetails(l.website + "/", l.username, l.password, l.notes); break;
            case "username": l = new LoginDetails(l.website, l.username + "-changed", l.password, l.notes); break;
            case "password": l = new LoginDetails(l.website, l.username, l.password + "-changed", l.notes); break;
            case "password-case": l = new LoginDetails(l.website, l.username, l.password.toLowerCase(java.util.Locale.ROOT), l.notes); break;
            case "password-space": l = new LoginDetails(l.website, l.username, l.password + " ", l.notes); break;
            case "notes": l = new LoginDetails(l.website, l.username, l.password, l.notes + "changed"); break;
            case "notes-newline": l = new LoginDetails(l.website, l.username, l.password, l.notes + "\n"); break;
            case "recovery-value": codes = RecoveryCodes.parse("Demo-3333\nDemo-2222"); break;
            case "recovery-used": codes = RecoveryCodes.setUsed(codes, 0, true); break;
            case "recovery-order": codes = Arrays.asList(codes.get(1), codes.get(0)); break;
            case "recovery-count": codes = Collections.singletonList(codes.get(0)); break;
            default: throw new AssertionError("unknown test field");
        }
        return copy(a, issuer, name, secret, algorithm, digits, period, codes, l, group);
    }
    public static void main(String[] args) throws Exception {
        PasswordGroup work = new PasswordGroup("work-1", "工作"), personal = new PasswordGroup("personal-1", "个人");
        LoginDetails login = new LoginDetails("https://example.invalid", "synthetic-user", "Synthetic-Password!", "合成备注\nsecond line");
        Otp.Account base = new Otp.Account("Example", "display-user", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "SHA1", 6, 30,
            RecoveryCodes.parse("Demo-1111\nDemo-2222"), login, false, work.id);
        VaultState local = state(Collections.singletonList(base), work, personal);
        Otp.Account cloned = copy(base, base.issuer, base.name, base.secret, base.algorithm, base.digits, base.period, base.recoveryCodes, base.login, base.groupId);
        VaultMerge.Result identical = VaultMerge.merge(local, state(Collections.singletonList(cloned), work, personal));
        check(identical.added == 0 && identical.skipped == 1 && identical.groupsAdded == 0, "whole-record match skipped");
        check(identical.state.accounts.get(0) == base, "existing record retained exactly");
        for (String field : new String[] {"title", "name", "secret", "algorithm", "digits", "period", "group", "website", "username", "password", "password-case", "password-space", "notes", "notes-newline", "recovery-value", "recovery-used", "recovery-order", "recovery-count"}) {
            VaultMerge.Result result = VaultMerge.merge(local, state(Collections.singletonList(variant(base, field)), work, personal));
            check(result.added == 1 && result.skipped == 0 && result.state.accounts.size() == 2 && result.state.accounts.get(0) == base, "one changed field imported: " + field);
        }
        Otp.Account password = Otp.Account.passwordEntry("Example", login).withGroup(work.id);
        VaultMerge.Result otherType = VaultMerge.merge(local, state(Collections.singletonList(password), work));
        check(otherType.added == 1 && otherType.state.accounts.get(1).passwordOnly, "password and TOTP entries remain distinct");
        VaultMerge.Result withinBackup = VaultMerge.merge(state(Collections.emptyList()), state(Arrays.asList(password, password), work));
        check(withinBackup.added == 1 && withinBackup.skipped == 1, "duplicates within the same import skipped");
        VaultMerge.Result again = VaultMerge.merge(withinBackup.state, state(Arrays.asList(password, password), work));
        check(again.added == 0 && again.skipped == 2 && again.groupsAdded == 0, "repeat import idempotent");
        VaultMerge.Result keepExistingDuplicates = VaultMerge.merge(state(Arrays.asList(password, password), work), state(Collections.singletonList(password), work));
        check(keepExistingDuplicates.state.accounts.size() == 2 && keepExistingDuplicates.skipped == 1, "local duplicates never deleted");
        Otp.Account sameNamedGroup = password.withGroup("remote-1");
        VaultMerge.Result nameMatch = VaultMerge.merge(state(Collections.singletonList(password), work), state(Collections.singletonList(sameNamedGroup), new PasswordGroup("remote-1", work.name)));
        check(nameMatch.skipped == 1 && nameMatch.groupsAdded == 0, "same visible group with different internal ID resolves before comparison");
        PasswordGroup renamed = new PasswordGroup(work.id, "新工作");
        VaultMerge.Result idConflict = VaultMerge.merge(state(Collections.singletonList(password), work), state(Collections.singletonList(password), renamed));
        check(idConflict.added == 1 && idConflict.groupsAdded == 1 && idConflict.state.groups.get(0).name.equals("工作"), "same ID different group name preserved separately");
        check(!idConflict.state.accounts.get(1).groupId.equals(work.id), "conflicting internal group ID remapped");
        VaultMerge.Result idRepeat = VaultMerge.merge(idConflict.state, state(Collections.singletonList(password), renamed));
        check(idRepeat.added == 0 && idRepeat.skipped == 1 && idRepeat.groupsAdded == 0, "ID conflict repeat import stable");
        PasswordGroup upper = new PasswordGroup("upper-1", "Work"), lower = new PasswordGroup("lower-1", "work");
        VaultState uppercase = state(Collections.singletonList(password.withGroup(upper.id)), upper);
        VaultState lowercase = state(Collections.singletonList(password.withGroup(lower.id)), lower);
        VaultMerge.Result caseConflict = VaultMerge.merge(uppercase, lowercase);
        check(caseConflict.added == 1 && caseConflict.groupsAdded == 1 && caseConflict.groupsRenamed == 1, "group name case difference kept separately");
        VaultMerge.Result caseRepeat = VaultMerge.merge(caseConflict.state, lowercase);
        check(caseRepeat.added == 0 && caseRepeat.skipped == 1 && caseRepeat.groupsAdded == 0, "renamed group repeat import idempotent");
        PasswordGroup occupied = new PasswordGroup("occupied-1", "work（导入）");
        VaultMerge.Result suffixConflict = VaultMerge.merge(state(uppercase.accounts, upper, occupied), lowercase);
        check(suffixConflict.groupsAdded == 1 && suffixConflict.state.groups.size() == 3, "generated name does not overwrite another local group");
        check(VaultMerge.merge(suffixConflict.state, lowercase).skipped == 1, "generated name collision stable on repeat");
        PasswordGroup empty = new PasswordGroup("empty-1", "空组");
        VaultMerge.Result emptyGroup = VaultMerge.merge(local, state(Collections.emptyList(), empty));
        check(emptyGroup.added == 0 && emptyGroup.groupsAdded == 1 && emptyGroup.state.accounts.size() == 1, "empty groups merge without losing local accounts");
        check(VaultMerge.merge(emptyGroup.state, state(Collections.emptyList(), empty)).groupsAdded == 0, "empty group repeat skipped");
        Otp.Account ungrouped = password.withGroup("");
        check(VaultMerge.merge(state(Collections.singletonList(password), work), state(Collections.singletonList(ungrouped))).added == 1, "ungrouped is different membership");
        Otp.Account hashA = Otp.Account.passwordEntry("Collision", new LoginDetails("", "user", "FB"));
        Otp.Account hashB = Otp.Account.passwordEntry("Collision", new LoginDetails("", "user", "Ea"));
        check("FB".hashCode() == "Ea".hashCode(), "synthetic Java hash-collision fixture");
        check(VaultMerge.merge(state(Collections.singletonList(hashA)), state(Collections.singletonList(hashB))).added == 1, "hash collision never counts as equality");
        VaultMerge.Result combined = VaultMerge.merge(local, state(Arrays.asList(cloned, variant(base, "notes"), password), work, personal));
        check(combined.added == 2 && combined.skipped == 1 && combined.state.accounts.size() == 3, "mixed duplicates and changes counted correctly");
        check(local.accounts.size() == 1 && local.accounts.get(0).login.notes.equals(login.notes), "source snapshot unchanged");
        check(VaultCodec.decode(VaultCodec.encode(combined.state)).accounts.size() == 3, "merged data persists through serialization");
        KeyGenerator keys = KeyGenerator.getInstance("AES"); keys.init(256); javax.crypto.SecretKey key = keys.generateKey();
        byte[] encrypted = VaultCipher.encrypt(VaultCodec.encode(combined.state).getBytes(StandardCharsets.UTF_8), key);
        VaultState read = VaultCodec.decode(new String(VaultCipher.decrypt(encrypted, key), StandardCharsets.UTF_8));
        check(read.accounts.get(0).recoveryCodes.size() == 2 && read.accounts.get(1).login.notes.equals(variant(base, "notes").login.notes), "merged recovery and changed notes survive encryption");
        List<PasswordGroup> fullGroups = new ArrayList<>(); for (int i = 0; i < 1000; i++) fullGroups.add(new PasswordGroup("g-" + i, "组-" + i));
        VaultState full = new VaultState(Collections.emptyList(), fullGroups); boolean failed = false;
        try { VaultMerge.merge(full, state(Collections.emptyList(), empty)); } catch (IllegalArgumentException expected) { failed = true; }
        check(failed && full.groups.size() == 1000, "group limit failure leaves local state intact");
        System.out.println("PASS: " + count + " exact-match merge checks.");
    }
}

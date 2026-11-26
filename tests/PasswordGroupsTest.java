package cn.local.simpleauth;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PasswordGroupsTest {
    private static int count;
    private static void check(boolean pass, String name) { count++; if (!pass) throw new AssertionError(name); }
    private interface Action { void run() throws Exception; }
    private static void invalid(Action action) {
        boolean failed = false; try { action.run(); } catch (Exception expected) { failed = true; }
        check(failed, "invalid group or vault rejected");
    }
    public static void main(String[] args) throws Exception {
        PasswordGroup work = new PasswordGroup("work-1", "工作"), personal = new PasswordGroup("personal-1", "个人");
        String password = "  Synthetic-UniqueNeedle!密码\"\\\n ";
        Otp.Account site = Otp.Account.passwordEntry("Example", new LoginDetails("https://example.invalid", "alice", password))
            .withRecoveryCodes(RecoveryCodes.setUsed(RecoveryCodes.parse("Demo-1234"), 0, true)).withGroup(work.id);
        Otp.Account other = Otp.Account.passwordEntry("Other", new LoginDetails("https://other.invalid", "bob", "synthetic-password"));
        Otp.Account otp = new Otp.Account("OTP", "user", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "SHA1", 6, 30);
        VaultState state = new VaultState(Arrays.asList(site, other, otp), Arrays.asList(work, personal));
        check(state.groups.size() == 2 && state.accounts.size() == 3, "empty groups and ungrouped entries coexist");
        check(site.withLogin(site.login).withTitle("Renamed").groupId.equals(work.id), "edit preserves group");
        check(site.withRecoveryCodes(site.recoveryCodes).groupId.equals(work.id), "recovery update preserves group");
        check(site.withGroup("").login.password.equals(password) && site.groupId.equals(work.id), "moving preserves data and original snapshot");
        VaultState renamed = state.renameGroup(work.id, "新工作");
        check(renamed.groups.get(0).id.equals(work.id) && renamed.accounts.get(0).groupId.equals(work.id), "rename uses stable ID");
        check(state.groups.get(0).name.equals("工作"), "rename leaves prior snapshot unchanged");
        VaultState deleted = state.deleteGroup(work.id);
        check(deleted.accounts.size() == 3 && deleted.groups.size() == 1, "deleting group keeps every account");
        check(deleted.accounts.get(0).groupId.isEmpty() && deleted.accounts.get(0).login.password.equals(password)
            && deleted.accounts.get(0).recoveryCodes.get(0).used, "deletion moves credentials and recovery status intact");
        check(deleted.accounts.get(1) == other && deleted.accounts.get(2) == otp, "unrelated entries unchanged");
        check(PasswordPresentation.select(state.accounts, null, "").size() == 2, "password view excludes TOTP-only entries");
        check(PasswordPresentation.select(state.accounts, work.id, "").size() == 1, "group filter");
        check(PasswordPresentation.select(state.accounts, "", "").get(0) == other, "ungrouped filter");
        check(PasswordPresentation.select(state.accounts, personal.id, "").isEmpty(), "empty group");
        check(PasswordPresentation.select(state.accounts, null, "ALICE").get(0) == site, "case-insensitive username search");
        check(PasswordPresentation.select(state.accounts, null, "example.invalid").get(0) == site, "address search");
        check(PasswordPresentation.select(state.accounts, null, "UniqueNeedle").isEmpty(), "passwords excluded from search");
        check(PasswordPresentation.select(Arrays.asList(other, site), null, "").get(0) == site, "alphabetic list order");
        String expected = "名称：Example\n地址：https://example.invalid\n账号：alice\n密码：" + password;
        check(PasswordPresentation.copyAll(site).equals(expected), "copy all preserves exact four fields and password whitespace");
        check(!PasswordPresentation.copyAll(site).contains("Demo-1234"), "copy all does not include recovery codes");
        invalid(() -> state.addGroup(new PasswordGroup("different-id", "工作")));
        invalid(() -> state.addGroup(new PasswordGroup(work.id, "不同名称")));
        invalid(() -> state.renameGroup(work.id, "个人")); invalid(() -> state.deleteGroup("missing"));
        invalid(() -> new PasswordGroup("", "name")); invalid(() -> PasswordGroup.create("  "));
        invalid(() -> PasswordGroup.create("x".repeat(41)));
        invalid(() -> PasswordGroup.create("未分组")); invalid(() -> PasswordGroup.create("不分组")); invalid(() -> PasswordGroup.create("全部网站"));
        invalid(() -> new VaultState(Collections.singletonList(site), Collections.emptyList()));
        String encoded = VaultCodec.encode(state); VaultState loaded = VaultCodec.decode(encoded);
        check(loaded.groups.size() == 2 && loaded.groups.get(1).name.equals("个人"), "empty groups persist through serialization");
        check(loaded.accounts.get(0).groupId.equals(work.id) && loaded.accounts.get(0).login.password.equals(password)
            && loaded.accounts.get(0).recoveryCodes.get(0).used, "account group, Unicode, quotes, whitespace and used state roundtrip");
        check(Otp.code(loaded.accounts.get(2), 59).equals(Otp.code(otp, 59)), "TOTP preserved by new format");
        JSONObject oldOtp = new JSONObject().put("issuer", "Legacy").put("name", "user").put("secret", otp.secret)
            .put("algorithm", "SHA1").put("digits", 6).put("period", 30);
        VaultState v10 = VaultCodec.decode(new JSONArray().put(oldOtp).toString());
        check(v10.groups.isEmpty() && v10.accounts.get(0).groupId.isEmpty() && v10.accounts.get(0).login.isEmpty(), "v1.0 migration defaults");
        JSONObject v11Entry = new JSONObject(oldOtp.toString()).put("recoveryCodes", new JSONArray().put(new JSONObject().put("code", "Demo-1234").put("used", true)));
        check(VaultCodec.decode(new JSONArray().put(v11Entry).toString()).accounts.get(0).recoveryCodes.get(0).used, "v1.1 recovery migration");
        JSONObject v12Entry = new JSONObject(encoded).getJSONArray("accounts").getJSONObject(0); v12Entry.remove("groupId");
        VaultState v12 = VaultCodec.decode(new JSONArray().put(v12Entry).toString());
        check(v12.accounts.get(0).login.password.equals(password) && v12.accounts.get(0).groupId.isEmpty(), "v1.2 password migration to ungrouped");
        check(VaultCodec.decode(VaultCodec.encode(v12)).accounts.get(0).recoveryCodes.get(0).used, "migrated recovery survives first save");
        KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256); SecretKey key = generator.generateKey();
        byte[] blob = VaultCipher.encrypt(encoded.getBytes(StandardCharsets.UTF_8), key);
        VaultState decrypted = VaultCodec.decode(new String(VaultCipher.decrypt(blob, key), StandardCharsets.UTF_8));
        check(decrypted.groups.get(0).name.equals("工作") && decrypted.accounts.get(0).login.password.equals(password), "full encrypted snapshot roundtrip");
        check(!new String(blob, StandardCharsets.ISO_8859_1).contains("example.invalid"), "new snapshot encrypted at rest");
        invalid(() -> VaultCodec.decode(encoded + "garbage"));
        invalid(() -> VaultCodec.decode(new JSONObject(encoded).put("format", 99).toString()));
        JSONObject orphan = new JSONObject(encoded); orphan.getJSONArray("accounts").getJSONObject(0).put("groupId", "missing");
        invalid(() -> VaultCodec.decode(orphan.toString()));
        invalid(() -> VaultCodec.decode(new JSONObject(encoded).put("groups", new JSONArray().put(new JSONObject().put("id", work.id).put("name", "工作")).put(new JSONObject().put("id", personal.id).put("name", "工作"))).toString()));
        System.out.println("PASS: " + count + " grouping, search, copy and migration checks.");
    }
}

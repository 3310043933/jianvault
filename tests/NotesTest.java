package cn.local.simpleauth;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import javax.crypto.KeyGenerator;
import org.json.JSONObject;

public final class NotesTest {
    private static int count;
    private static void check(boolean ok, String name) { count++; if (!ok) throw new AssertionError(name); }
    public static void main(String[] args) throws Exception {
        String note = "  合成备注 SyntheticNoteOnly\n第二行 \"引用\" \\ 链接：https://example.invalid\n🔐\t  ";
        LoginDetails details = new LoginDetails("https://example.invalid", "synthetic-user", "synthetic-password", note);
        check(details.notes.equals(note), "multiline Unicode and whitespace preserved exactly");
        check(new LoginDetails("", "user", "pass").notes.isEmpty(), "old constructor defaults to empty notes");
        check(LoginDetails.EMPTY.notes.isEmpty() && LoginDetails.EMPTY.isEmpty(), "empty model remains empty");
        PasswordGroup group = new PasswordGroup("group-1", "测试组");
        Otp.Account account = Otp.Account.passwordEntry("Example", details).withGroup(group.id).withRecoveryCodes(RecoveryCodes.parse("Demo-1234"));
        check(account.withTitle("Renamed").withGroup("").login.notes.equals(note), "rename and move keep notes");
        check(account.withRecoveryCodes(Collections.emptyList()).login.notes.equals(note), "recovery changes keep notes");
        VaultState state = new VaultState(Collections.singletonList(account), Collections.singletonList(group));
        String encoded = VaultCodec.encode(state); VaultState decoded = VaultCodec.decode(encoded);
        check(decoded.accounts.get(0).login.notes.equals(note), "JSON roundtrip preserves notes");
        check(decoded.accounts.get(0).recoveryCodes.size() == 1, "legacy password recovery data retained");
        JSONObject legacy = new JSONObject(encoded); legacy.getJSONArray("accounts").getJSONObject(0).getJSONObject("login").remove("notes");
        VaultState migrated = VaultCodec.decode(legacy.toString());
        check(migrated.accounts.get(0).login.notes.isEmpty() && migrated.accounts.get(0).groupId.equals(group.id), "v1.3 migration keeps credentials and group");
        check(migrated.accounts.get(0).login.password.equals("synthetic-password") && migrated.accounts.get(0).recoveryCodes.size() == 1, "old password and recovery survive migration");
        check(state.deleteGroup(group.id).accounts.get(0).login.notes.equals(note), "delete group preserves notes");
        String copy = PasswordPresentation.copyAll(account);
        check(copy.endsWith("\n备注：" + note) && copy.contains("密码：synthetic-password"), "copy all includes complete notes and password");
        check(!copy.contains("Demo-1234"), "copy excludes old recovery data");
        check(!PasswordPresentation.copyAll(migrated.accounts.get(0)).contains("备注："), "empty notes retain four-field copy format");
        KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256); javax.crypto.SecretKey key = generator.generateKey();
        byte[] blob = VaultCipher.encrypt(encoded.getBytes(StandardCharsets.UTF_8), key);
        check(!new String(blob, StandardCharsets.ISO_8859_1).contains("SyntheticNoteOnly"), "notes encrypted at rest");
        check(VaultCodec.decode(new String(VaultCipher.decrypt(blob, key), StandardCharsets.UTF_8)).accounts.get(0).login.notes.equals(note), "encrypted notes roundtrip");
        boolean rejected = false; try { new LoginDetails("", "user", "pass", "x".repeat(32769)); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "oversized notes rejected without silent truncation");
        check(new LoginDetails("", "user", "pass", "x".repeat(32768)).notes.length() == 32768, "notes limit accepted");
        Otp.Account cleared = account.withLogin(new LoginDetails(details.website, details.username, details.password, ""));
        check(cleared.login.notes.isEmpty() && cleared.groupId.equals(group.id) && cleared.recoveryCodes.size() == 1, "clear notes preserves other data");
        System.out.println("PASS: " + count + " notes and upgrade checks.");
    }
}

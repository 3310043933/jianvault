package cn.local.simpleauth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class BackupCodecTest {
    private static int count;
    private interface Action { void run() throws Exception; }
    private static void check(boolean ok, String name) { count++; if (!ok) throw new AssertionError(name); }
    private static void rejected(Action action) {
        boolean failed = false; try { action.run(); } catch (Exception expected) { failed = true; }
        check(failed, "bad backup rejected");
    }
    private static byte[] envelope(String text) { return Base64.getDecoder().decode(text.substring(BackupCodec.PREFIX.length()).trim()); }
    private static String wrap(byte[] bytes) { return BackupCodec.PREFIX + "\n" + Base64.getEncoder().encodeToString(bytes); }
    private static String fixture(String plain, char[] key) throws Exception {
        byte[] salt = new byte[16], iv = new byte[12]; Arrays.fill(salt, (byte)13); Arrays.fill(iv, (byte)27);
        byte[] header = ByteBuffer.allocate(37).put(new byte[] {'S', 'A', 'B', 'K', 1}).putInt(600000).put(salt).put(iv).array();
        PBEKeySpec spec = new PBEKeySpec(key, salt, 600000, 256);
        byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); spec.clearPassword();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derived, "AES"), new GCMParameterSpec(128, iv)); cipher.updateAAD(header);
        byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)), result = Arrays.copyOf(header, header.length + encrypted.length);
        System.arraycopy(encrypted, 0, result, header.length, encrypted.length); Arrays.fill(derived, (byte)0); return wrap(result);
    }
    public static void main(String[] args) throws Exception {
        char[] key = "Synthetic-Key-2026!混合".toCharArray();
        PasswordGroup group = new PasswordGroup("work-1", "工作");
        Otp.Account password = Otp.Account.passwordEntry("Example", new LoginDetails("https://example.invalid", "synthetic-user", "  Synthetic-Password!  ", "备注\nSynthetic-Note\"\\"))
            .withGroup(group.id).withRecoveryCodes(RecoveryCodes.setUsed(RecoveryCodes.parse("Demo-1234"), 0, true));
        Otp.Account otp = new Otp.Account("OTP", "user", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "SHA1", 6, 30);
        VaultState state = new VaultState(Arrays.asList(password, otp), Arrays.asList(group, new PasswordGroup("empty-1", "空组")));
        String exported = BackupCodec.encrypt(state, key), again = BackupCodec.encrypt(state, key);
        check(exported.startsWith(BackupCodec.PREFIX) && exported.indexOf('\n') > 0, "identifiable text format");
        check(!exported.equals(again), "fresh randomness on every export");
        byte[] a = envelope(exported), b = envelope(again);
        check(!Arrays.equals(Arrays.copyOfRange(a, 9, 25), Arrays.copyOfRange(b, 9, 25)), "fresh KDF salt");
        check(!Arrays.equals(Arrays.copyOfRange(a, 25, 37), Arrays.copyOfRange(b, 25, 37)), "fresh GCM nonce");
        check(!exported.contains("synthetic-user") && !exported.contains("Synthetic-Password") && !exported.contains("Synthetic-Key") && !exported.contains("Demo-1234"), "no plaintext fields or Key in backup text");
        VaultState restored = BackupCodec.decrypt(exported, key);
        check(restored.accounts.size() == 2 && restored.groups.size() == 2, "all accounts and empty groups restored");
        check(restored.accounts.get(0).login.password.equals(password.login.password) && restored.accounts.get(0).login.notes.equals(password.login.notes), "password whitespace and multiline notes preserved");
        check(restored.accounts.get(0).groupId.equals(group.id) && restored.accounts.get(0).recoveryCodes.get(0).used, "group assignment and recovery status preserved");
        check(Otp.code(restored.accounts.get(1), 59).equals(Otp.code(otp, 59)), "TOTP secret and settings preserved");
        check(new String(key).equals("Synthetic-Key-2026!混合"), "caller retains Key ownership for wiping");
        String wrapped = "\uFEFF\r\n" + BackupCodec.PREFIX + "\r\n " + Base64.getMimeEncoder(76, new byte[] {'\r','\n'}).encodeToString(a) + "\r\n";
        check(BackupCodec.decrypt(wrapped, key).accounts.size() == 2, "TXT BOM and wrapped CRLF supported");
        KeyGenerator keys = KeyGenerator.getInstance("AES"); keys.init(256); javax.crypto.SecretKey deviceOne = keys.generateKey(), deviceTwo = keys.generateKey();
        byte[] localOne = VaultCipher.encrypt(VaultCodec.encode(state).getBytes(StandardCharsets.UTF_8), deviceOne);
        VaultState source = VaultCodec.decode(new String(VaultCipher.decrypt(localOne, deviceOne), StandardCharsets.UTF_8));
        VaultState transferred = BackupCodec.decrypt(BackupCodec.encrypt(source, key), key);
        byte[] localTwo = VaultCipher.encrypt(VaultCodec.encode(transferred).getBytes(StandardCharsets.UTF_8), deviceTwo);
        check(VaultCodec.decode(new String(VaultCipher.decrypt(localTwo, deviceTwo), StandardCharsets.UTF_8)).accounts.get(0).login.password.equals(password.login.password), "portable backup reencrypted with new device key");
        rejected(() -> VaultCipher.decrypt(localTwo, deviceOne));
        rejected(() -> BackupCodec.decrypt(exported, "Wrong-Synthetic-Key-2026".toCharArray()));
        check(state.accounts.get(0).login.password.equals(password.login.password), "failed import leaves source snapshot unchanged");
        for (int index : new int[] {9, 25, 37, a.length - 1}) {
            byte[] tampered = a.clone(); tampered[index] ^= 1;
            rejected(() -> BackupCodec.decrypt(wrap(tampered), key));
        }
        byte[] version = a.clone(); version[4] = 99; rejected(() -> BackupCodec.decrypt(wrap(version), key));
        byte[] magic = a.clone(); magic[0] ^= 1; rejected(() -> BackupCodec.decrypt(wrap(magic), key));
        byte[] cost = a.clone(); ByteBuffer.wrap(cost).putInt(5, Integer.MAX_VALUE); rejected(() -> BackupCodec.decrypt(wrap(cost), key));
        rejected(() -> BackupCodec.decrypt(wrap(Arrays.copyOf(a, a.length - 1)), key));
        rejected(() -> BackupCodec.decrypt(wrap(new byte[20]), key));
        rejected(() -> BackupCodec.decrypt("not-a-backup", key));
        rejected(() -> BackupCodec.decrypt(BackupCodec.PREFIX + "\n!!!", key));
        rejected(() -> BackupCodec.decrypt(exported + "!", key));
        rejected(() -> BackupCodec.validateKey("short".toCharArray()));
        rejected(() -> BackupCodec.validateKey("            ".toCharArray()));
        rejected(() -> BackupCodec.validateKey(new char[1025]));
        rejected(() -> BackupCodec.decrypt("x".repeat(BackupCodec.MAX_TEXT_BYTES + 1), key));
        String independent = fixture(VaultCodec.encode(state), key);
        check(BackupCodec.decrypt(independent, key).accounts.get(0).login.notes.equals(password.login.notes), "independently formed envelope import");
        String badSchema = fixture("{\"format\":99,\"accounts\":[],\"groups\":[]}", key);
        rejected(() -> BackupCodec.decrypt(badSchema, key));
        String empty = BackupCodec.encrypt(new VaultState(Collections.emptyList(), Collections.emptyList()), key);
        check(BackupCodec.decrypt(empty, key).accounts.isEmpty(), "empty snapshot supported");
        Arrays.fill(key, '\0');
        System.out.println("PASS: " + count + " encrypted-backup checks.");
    }
}

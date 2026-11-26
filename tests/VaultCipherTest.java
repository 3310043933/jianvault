package cn.local.simpleauth;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class VaultCipherTest {
    private static int count;
    private static void check(boolean pass, String name) { count++; if (!pass) throw new AssertionError(name); }
    private static void rejected(byte[] blob, SecretKey key) {
        boolean failed = false;
        try { VaultCipher.decrypt(blob, key); } catch (java.security.GeneralSecurityException expected) { failed = true; }
        check(failed, "invalid or unauthenticated ciphertext rejected");
    }
    public static void main(String[] args) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256); SecretKey key = generator.generateKey();
        byte[] plain = "{\"website\":\"https://example.invalid\",\"username\":\"synthetic-user\",\"password\":\"Synthetic-Secret-123!\",\"recovery\":\"Demo-1234\"}".getBytes(StandardCharsets.UTF_8);
        byte[] blob = VaultCipher.encrypt(plain, key);
        check(Arrays.equals(plain, VaultCipher.decrypt(blob, key)), "encrypted payload roundtrip");
        check(blob[0] == 1 && blob.length == plain.length + 29, "existing envelope format retained");
        String stored = new String(blob, StandardCharsets.ISO_8859_1);
        check(!stored.contains("synthetic-user") && !stored.contains("Synthetic-Secret-123!") && !stored.contains("Demo-1234") && !stored.contains("example.invalid"), "all fields encrypted");
        byte[] again = VaultCipher.encrypt(plain, key);
        check(!Arrays.equals(blob, again), "fresh nonce for each save");
        check(!Arrays.equals(Arrays.copyOfRange(blob, 1, 13), Arrays.copyOfRange(again, 1, 13)), "different IVs");
        byte[] altered = blob.clone(); altered[13] ^= 1; rejected(altered, key);
        altered = blob.clone(); altered[1] ^= 1; rejected(altered, key);
        altered = blob.clone(); altered[altered.length - 1] ^= 1; rejected(altered, key);
        altered = blob.clone(); altered[0] = 2; rejected(altered, key);
        rejected(Arrays.copyOf(blob, blob.length - 1), key); rejected(new byte[28], key);
        rejected(blob, generator.generateKey());
        // Synthetic old-format envelope using the original v1.0/v1.1 layout.
        Cipher old = Cipher.getInstance("AES/GCM/NoPadding"); byte[] iv = new byte[12]; iv[0] = 42;
        old.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv)); byte[] encrypted = old.doFinal(plain);
        byte[] legacy = new byte[13 + encrypted.length]; legacy[0] = 1;
        System.arraycopy(iv, 0, legacy, 1, 12); System.arraycopy(encrypted, 0, legacy, 13, encrypted.length);
        check(Arrays.equals(plain, VaultCipher.decrypt(legacy, key)), "old version encrypted envelope compatibility");
        System.out.println("PASS: " + count + " encryption-envelope checks.");
    }
}

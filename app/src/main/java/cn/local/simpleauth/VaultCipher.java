package cn.local.simpleauth;

import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Version 1 envelope: version byte | 12-byte random IV | GCM ciphertext and tag. */
final class VaultCipher {
    private VaultCipher() {}
    static byte[] encrypt(byte[] plain, SecretKey key) throws java.security.GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        if (iv.length != 12) throw new java.security.GeneralSecurityException("Unexpected GCM nonce length");
        byte[] encrypted = cipher.doFinal(plain);
        byte[] blob = new byte[13 + encrypted.length]; blob[0] = 1;
        System.arraycopy(iv, 0, blob, 1, 12); System.arraycopy(encrypted, 0, blob, 13, encrypted.length);
        return blob;
    }
    static byte[] decrypt(byte[] blob, SecretKey key) throws java.security.GeneralSecurityException {
        if (blob.length < 29 || blob[0] != 1) throw new java.security.GeneralSecurityException("Invalid vault envelope");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOfRange(blob, 1, 13)));
        return cipher.doFinal(blob, 13, blob.length - 13);
    }
}

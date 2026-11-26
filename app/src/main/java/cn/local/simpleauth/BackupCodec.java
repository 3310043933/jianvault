package cn.local.simpleauth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Portable encrypted backup, independent of each device's Android Keystore. */
final class BackupCodec {
    static final String PREFIX = "SIMPLEAUTH-BACKUP-1:";
    static final int ITERATIONS = 600000;
    static final int MAX_PLAIN_BYTES = 8 * 1024 * 1024;
    static final int MAX_TEXT_BYTES = 12 * 1024 * 1024;
    static final int CLIPBOARD_LIMIT = 250000;
    private static final int HEADER_SIZE = 37;
    private static final SecureRandom RANDOM = new SecureRandom();
    private BackupCodec() {}
    static void validateKey(char[] key) {
        if (key == null || key.length < 12 || key.length > 1024)
            throw new IllegalArgumentException("Key 需为 12～1024 个字符，建议使用较长的随机口令");
        boolean nonSpace = false;
        for (char c : key) if (!Character.isWhitespace(c)) nonSpace = true;
        if (!nonSpace) throw new IllegalArgumentException("Key 不能全部为空格");
    }
    private static byte[] derive(char[] key, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(key, salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    static String encrypt(VaultState state, char[] key) throws Exception {
        validateKey(key);
        byte[] plain = VaultCodec.encode(state).getBytes(StandardCharsets.UTF_8);
        byte[] derived = null;
        try {
            if (plain.length > MAX_PLAIN_BYTES) throw new IllegalArgumentException("数据超过备份大小上限（8 MB），未导出任何内容");
            byte[] salt = new byte[16], iv = new byte[12]; RANDOM.nextBytes(salt); RANDOM.nextBytes(iv);
            byte[] header = ByteBuffer.allocate(HEADER_SIZE).put(new byte[] {'S', 'A', 'B', 'K'}).put((byte)1)
                .putInt(ITERATIONS).put(salt).put(iv).array();
            derived = derive(key, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derived, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(header);
            byte[] encrypted = cipher.doFinal(plain), envelope = new byte[header.length + encrypted.length];
            System.arraycopy(header, 0, envelope, 0, header.length); System.arraycopy(encrypted, 0, envelope, header.length, encrypted.length);
            return PREFIX + "\n" + Base64.getEncoder().encodeToString(envelope);
        } finally { Arrays.fill(plain, (byte)0); if (derived != null) Arrays.fill(derived, (byte)0); }
    }
    static VaultState decrypt(String text, char[] key) throws Exception {
        validateKey(key);
        if (text == null || text.length() > MAX_TEXT_BYTES) throw new IllegalArgumentException("备份文本为空或超过 12 MB 上限");
        String value = text.trim();
        if (value.startsWith("\uFEFF")) value = value.substring(1).trim();
        if (!value.startsWith(PREFIX)) throw new IllegalArgumentException("不是简验证支持的加密备份文本");
        String encoded = value.substring(PREFIX.length()).replaceAll("[ \\t\\r\\n]", "");
        int maxEnvelope = MAX_PLAIN_BYTES + HEADER_SIZE + 16;
        if (encoded.length() > ((maxEnvelope + 2L) / 3L) * 4L) throw new IllegalArgumentException("备份内容过大");
        byte[] envelope;
        try { envelope = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("备份文本不完整或格式损坏"); }
        if (envelope.length < HEADER_SIZE + 16 || envelope.length > maxEnvelope) throw new IllegalArgumentException("备份长度无效");
        ByteBuffer header = ByteBuffer.wrap(envelope);
        if (header.get() != 'S' || header.get() != 'A' || header.get() != 'B' || header.get() != 'K' || header.get() != 1)
            throw new IllegalArgumentException("备份版本不受支持");
        if (header.getInt() != ITERATIONS) throw new IllegalArgumentException("备份加密参数不受支持");
        byte[] salt = new byte[16], iv = new byte[12]; header.get(salt); header.get(iv);
        byte[] derived = derive(key, salt), plain = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derived, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(envelope, 0, HEADER_SIZE);
            plain = cipher.doFinal(envelope, HEADER_SIZE, envelope.length - HEADER_SIZE);
            return VaultCodec.decode(new String(plain, StandardCharsets.UTF_8));
        } finally { Arrays.fill(derived, (byte)0); if (plain != null) Arrays.fill(plain, (byte)0); }
    }
}

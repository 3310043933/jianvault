package cn.local.simpleauth;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

final class Vault {
    private static final String ALIAS = "simpleauth.vault.v1";
    private final AtomicFile file;
    Vault(Context context) { file = new AtomicFile(new File(context.getNoBackupFilesDir(), "vault.bin")); }
    private SecretKey key(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        if (!create) throw new IllegalStateException("加密密钥不可用，原数据未被覆盖");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }
    VaultState load() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return new VaultState(new ArrayList<>(), new ArrayList<>());
        byte[] blob = file.readFully();
        if (blob.length < 29 || blob[0] != 1) throw new IllegalStateException("账号文件损坏，原数据未被覆盖");
        byte[] plain = VaultCipher.decrypt(blob, key(false));
        try {
            return VaultCodec.decode(new String(plain, StandardCharsets.UTF_8));
        } finally { Arrays.fill(plain, (byte) 0); }
    }
    void save(VaultState state) throws Exception {
        byte[] plain = VaultCodec.encode(state).getBytes(StandardCharsets.UTF_8);
        byte[] blob;
        try { blob = VaultCipher.encrypt(plain, key(true)); } finally { Arrays.fill(plain, (byte) 0); }
        FileOutputStream stream = null;
        try {
            stream = file.startWrite(); stream.write(blob); file.finishWrite(stream);
        } catch (Exception e) { if (stream != null) file.failWrite(stream); throw e; }
    }
}

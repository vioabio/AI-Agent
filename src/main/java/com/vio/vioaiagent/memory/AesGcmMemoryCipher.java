package com.vio.vioaiagent.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 本地/演示用 AES-GCM 适配器。生产应把同一接口替换为 KMS envelope
 * encryption；密钥只在构造器内存中存在，不进入实体或日志。
 */
public final class AesGcmMemoryCipher implements MemoryCipher {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final String keyVersion;
    private final SecureRandom random = new SecureRandom();

    public AesGcmMemoryCipher(byte[] keyBytes, String keyVersion) {
        if (keyBytes == null || !(keyBytes.length == 16 || keyBytes.length == 24 || keyBytes.length == 32)) {
            throw new IllegalArgumentException("记忆加密密钥必须为 128/192/256 bit");
        }
        if (keyVersion == null || keyVersion.isBlank() || keyVersion.length() > 64) {
            throw new IllegalArgumentException("keyVersion 无效");
        }
        this.key = new SecretKeySpec(keyBytes.clone(), "AES");
        this.keyVersion = keyVersion;
    }

    public static AesGcmMemoryCipher fromConfigured(String value, String keyVersion) {
        if (value == null || value.isBlank() || value.matches("(?i)^(your|replace|change|pending).*$")) {
            throw new IllegalArgumentException("VIO_MEMORY_ENCRYPTION_KEY 未配置");
        }
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(value.trim()); }
        catch (IllegalArgumentException ex) { bytes = value.getBytes(StandardCharsets.UTF_8); }
        return new AesGcmMemoryCipher(bytes, keyVersion == null || keyVersion.isBlank() ? "v1" : keyVersion);
    }

    @Override
    public Encrypted encrypt(String userId, String plaintext) {
        require(userId, plaintext);
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(userId));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return new Encrypted(Base64.getEncoder().encodeToString(packed), digest(plaintext), keyVersion);
        } catch (Exception ex) { throw new IllegalStateException("记忆加密失败", ex); }
    }

    @Override
    public String decrypt(String userId, String ciphertext, String storedKeyVersion) {
        require(userId, ciphertext);
        if (!keyVersion.equals(storedKeyVersion)) throw new IllegalStateException("记忆密钥版本不匹配");
        try {
            byte[] packed = Base64.getDecoder().decode(ciphertext);
            if (packed.length <= IV_BYTES) throw new IllegalArgumentException("ciphertext 无效");
            byte[] iv = java.util.Arrays.copyOfRange(packed, 0, IV_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(packed, IV_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(userId));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new IllegalStateException("记忆解密失败", ex); }
    }

    private byte[] aad(String userId) { return (userId + ':' + keyVersion).getBytes(StandardCharsets.UTF_8); }
    private String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("JRE 不支持 SHA-256", ex); }
    }
    private static void require(String userId, String value) {
        if (userId == null || userId.isBlank() || value == null || value.isBlank() || value.length() > 100_000) {
            throw new IllegalArgumentException("记忆主体或内容无效");
        }
    }
}

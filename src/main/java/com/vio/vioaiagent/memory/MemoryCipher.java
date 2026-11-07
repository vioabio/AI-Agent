package com.vio.vioaiagent.memory;

/** 受控密钥/KMS 的最小抽象；业务层不接触明文密钥。 */
public interface MemoryCipher {
    Encrypted encrypt(String userId, String plaintext);

    String decrypt(String userId, String ciphertext, String keyVersion);

    record Encrypted(String ciphertext, String contentDigest, String keyVersion) { }
}

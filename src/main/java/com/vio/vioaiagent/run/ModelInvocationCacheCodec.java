package com.vio.vioaiagent.run;

/**
 * 模型响应的受控编解码边界。
 *
 * <p>生产实现应接入 KMS/envelope encryption，并在 {@link #keyVersion()} 返回
 * 当前密钥版本。项目不提供把 Base64 当作加密的默认实现；没有 codec 时，
 * 显式开启缓存会安全失败。</p>
 */
public interface ModelInvocationCacheCodec {

    /** 将响应编码为可持久化的密文或受控 artifact 引用。 */
    String encode(String response);

    /** 从持久化值恢复响应明文。 */
    String decode(String encodedResponse);

    /** 当前 envelope key 版本，供轮换和审计使用。 */
    default String keyVersion() {
        return "managed";
    }
}

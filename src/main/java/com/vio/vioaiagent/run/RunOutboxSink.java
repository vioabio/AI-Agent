package com.vio.vioaiagent.run;

/**
 * 可插拔的 Run 事件投递端。实现可以接入 Kafka、Redis Streams 等 broker，
 * 但必须以 outboxId 做幂等去重；本仓库不内置任何真实 broker 客户端。
 */
@FunctionalInterface
public interface RunOutboxSink {
    void publish(RunOutboxMessage message) throws Exception;
}

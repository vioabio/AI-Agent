package com.vio.vioaiagent.feedback;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册反馈模块配置属性；仓储实现通过 prod/非 prod profile 自动切换。 */
@Configuration
@EnableConfigurationProperties(FeedbackProperties.class)
public class FeedbackConfiguration {
}

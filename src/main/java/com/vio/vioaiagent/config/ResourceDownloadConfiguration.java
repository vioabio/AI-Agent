package com.vio.vioaiagent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册资源 artifact 下载的非敏感配置。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ResourceDownloadProperties.class)
public class ResourceDownloadConfiguration {
}

package com.vio.vioaiagent.tools.governance;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ToolExecutionProperties.class)
public class ToolExecutionConfiguration { }

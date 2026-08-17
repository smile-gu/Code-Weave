package com.codingreview.controlplane.configuration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 为 Run 提供有界执行资源，防止请求无限堆积拖垮本机。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CodeWeaveProperties.class)
public class RunExecutorConfiguration {

    @Bean
    @Qualifier("m0RunExecutor")
    TaskExecutor m0RunExecutor(CodeWeaveProperties properties) {
        CodeWeaveProperties.Run run = properties.getRun();
        run.validate();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(run.getConcurrency());
        executor.setMaxPoolSize(run.getConcurrency());
        executor.setQueueCapacity(run.getQueueCapacity());
        executor.setThreadNamePrefix("m0-run-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}

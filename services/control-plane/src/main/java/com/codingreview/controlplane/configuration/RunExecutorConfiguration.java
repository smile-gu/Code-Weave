package com.codingreview.controlplane.configuration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 为正式 Coding Turn 提供有界执行资源，防止请求无限堆积拖垮本机。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CodeWeaveProperties.class)
public class RunExecutorConfiguration {

    /** 创建持续 Coding Turn 使用的有界执行器。 */
    @Bean
    @Qualifier("codingTurnExecutor")
    TaskExecutor codingTurnExecutor(CodeWeaveProperties properties) {
        return createExecutor(properties, "coding-turn-");
    }

    /** 使用统一容量规则创建一个有界业务执行器。 */
    private TaskExecutor createExecutor(
            CodeWeaveProperties properties,
            String threadNamePrefix
    ) {
        CodeWeaveProperties.Run run = properties.getRun();
        run.validate();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(run.getConcurrency());
        executor.setMaxPoolSize(run.getConcurrency());
        executor.setQueueCapacity(run.getQueueCapacity());
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}

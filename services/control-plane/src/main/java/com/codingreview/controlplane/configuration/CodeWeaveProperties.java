package com.codingreview.controlplane.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/** 控制平面的本机运行配置，不允许由单次 HTTP 请求覆盖。 */
@ConfigurationProperties(prefix = "code-weave")
public class CodeWeaveProperties {

    private Path runtimeRoot;
    private final Claude claude = new Claude();
    private final Run run = new Run();

    /** 返回平台运行数据根目录。 */
    public Path getRuntimeRoot() {
        return runtimeRoot;
    }

    /** 绑定平台运行数据根目录。 */
    public void setRuntimeRoot(Path runtimeRoot) {
        this.runtimeRoot = runtimeRoot;
    }

    /** 返回 Claude Code 相关部署配置。 */
    public Claude getClaude() {
        return claude;
    }

    /** 返回 Run 执行资源配置。 */
    public Run getRun() {
        return run;
    }

    /** 获取并校验平台运行数据根目录。 */
    public Path requireRuntimeRoot() {
        return requireAbsolute(runtimeRoot, "code-weave.runtime-root");
    }

    /** 获取并校验 Claude Code 可执行文件路径。 */
    public Path requireClaudeExecutable() {
        return requireAbsolute(claude.executable, "code-weave.claude.executable");
    }

    /** 校验配置值是否为绝对路径并返回规范化结果。 */
    private static Path requireAbsolute(Path value, String property) {
        if (value == null || !value.isAbsolute()) {
            throw new IllegalStateException(property + " 必须配置为绝对路径");
        }
        return value.normalize();
    }

    public static class Claude {
        private Path executable;

        /** 返回 Claude Code 可执行文件路径。 */
        public Path getExecutable() {
            return executable;
        }

        /** 绑定 Claude Code 可执行文件路径。 */
        public void setExecutable(Path executable) {
            this.executable = executable;
        }
    }

    public static class Run {
        private int concurrency = 1;
        private int queueCapacity = 10;

        /** 返回允许同时执行的 Run 数量。 */
        public int getConcurrency() {
            return concurrency;
        }

        /** 设置允许同时执行的 Run 数量。 */
        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        /** 返回等待队列容量。 */
        public int getQueueCapacity() {
            return queueCapacity;
        }

        /** 设置等待队列容量。 */
        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        /** 校验 Run 并发数和队列容量是否合法。 */
        public void validate() {
            if (concurrency < 1) {
                throw new IllegalStateException("code-weave.run.concurrency 必须大于 0");
            }
            if (queueCapacity < 0) {
                throw new IllegalStateException("code-weave.run.queue-capacity 不能小于 0");
            }
        }
    }
}

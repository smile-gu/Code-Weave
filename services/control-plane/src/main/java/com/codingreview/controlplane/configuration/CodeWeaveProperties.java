package com.codingreview.controlplane.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/** 控制平面的本机运行配置，不允许由单次 HTTP 请求覆盖。 */
@ConfigurationProperties(prefix = "code-weave")
public class CodeWeaveProperties {

    private Path runtimeRoot;
    private final Claude claude = new Claude();
    private final Run run = new Run();

    public Path getRuntimeRoot() {
        return runtimeRoot;
    }

    public void setRuntimeRoot(Path runtimeRoot) {
        this.runtimeRoot = runtimeRoot;
    }

    public Claude getClaude() {
        return claude;
    }

    public Run getRun() {
        return run;
    }

    public Path requireRuntimeRoot() {
        return requireAbsolute(runtimeRoot, "code-weave.runtime-root");
    }

    public Path requireClaudeExecutable() {
        return requireAbsolute(claude.executable, "code-weave.claude.executable");
    }

    private static Path requireAbsolute(Path value, String property) {
        if (value == null || !value.isAbsolute()) {
            throw new IllegalStateException(property + " 必须配置为绝对路径");
        }
        return value.normalize();
    }

    public static class Claude {
        private Path executable;

        public Path getExecutable() {
            return executable;
        }

        public void setExecutable(Path executable) {
            this.executable = executable;
        }
    }

    public static class Run {
        private int concurrency = 1;
        private int queueCapacity = 10;

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

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

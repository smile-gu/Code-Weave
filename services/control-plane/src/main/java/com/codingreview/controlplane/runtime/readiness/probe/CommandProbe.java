package com.codingreview.controlplane.runtime.readiness.probe;

import java.time.Duration;
import java.util.List;

/** 以固定参数安全执行只读环境探测命令。 */
@FunctionalInterface
public interface CommandProbe {

    /** 执行命令并在给定超时内收集有界输出。 */
    CommandProbeResult execute(List<String> command, Duration timeout);
}

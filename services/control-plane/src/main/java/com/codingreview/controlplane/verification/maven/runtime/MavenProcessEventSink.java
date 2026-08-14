package com.codingreview.controlplane.verification.maven.runtime;

import com.codingreview.controlplane.observability.run.RunEventType;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 接收 Maven 进程内部生命周期事件，发布失败必须中止进程。 */
@FunctionalInterface
public interface MavenProcessEventSink {

    void onEvent(RunEventType type, ObjectNode payload);
}

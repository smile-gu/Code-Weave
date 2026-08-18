package com.codingreview.controlplane.runtime.readiness;

import com.codingreview.controlplane.runtime.readiness.model.RuntimeReadiness;

/** 提供不调用大模型的本机运行环境实时检查。 */
public interface RuntimeReadinessService {

    /** 检查所有必要组件并返回结构化结论。 */
    RuntimeReadiness check();
}

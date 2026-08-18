package com.codingreview.controlplane.api.system;

import com.codingreview.controlplane.api.system.dto.RuntimeReadinessResponse;
import com.codingreview.controlplane.runtime.readiness.RuntimeReadinessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供不产生模型调用的本机运行环境检查入口。 */
@RestController
@RequestMapping("/api/v1/system/readiness")
public class RuntimeReadinessController {

    private final RuntimeReadinessService readinessService;

    /** 注入实时运行环境检查服务。 */
    public RuntimeReadinessController(RuntimeReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    /** 实时检查并返回当前本机运行环境状态。 */
    @GetMapping
    public RuntimeReadinessResponse get() {
        return RuntimeReadinessResponse.from(readinessService.check());
    }
}

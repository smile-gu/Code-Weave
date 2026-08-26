package com.codingreview.controlplane.api.session;

import com.codingreview.controlplane.api.session.dto.CodingSessionListResponse;
import com.codingreview.controlplane.api.session.dto.CodingSessionResponse;
import com.codingreview.controlplane.api.session.dto.CreateCodingSessionRequest;
import com.codingreview.controlplane.api.session.dto.MergeReadinessResponse;
import com.codingreview.controlplane.api.session.dto.CodingSessionIntegrationResponse;
import com.codingreview.controlplane.application.session.CodingSessionIntegrationService;
import com.codingreview.controlplane.application.session.MergeCodingSessionRequest;
import com.codingreview.controlplane.application.session.CodingSessionApplicationService;
import com.codingreview.controlplane.application.session.CreateCodingSessionCommand;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;

/** 创建和查询持久 Coding Session 的 HTTP 入口。 */
@Validated
@RestController
@RequestMapping("/api/v1/coding-sessions")
public class CodingSessionController {

    private final CodingSessionApplicationService applicationService;
    private final CodingSessionIntegrationService integrationService;

    /** 装配 Coding Session 应用服务和合并服务。 */
    public CodingSessionController(
            CodingSessionApplicationService applicationService,
            CodingSessionIntegrationService integrationService
    ) {
        this.applicationService = applicationService;
        this.integrationService = integrationService;
    }

    /** 创建一个直接绑定真实 Git 工作区的持久 Coding Session。 */
    @PostMapping
    public ResponseEntity<CodingSessionResponse> create(
            @Valid @RequestBody CreateCodingSessionRequest request
    ) {
        CodingSession session = applicationService.create(new CreateCodingSessionCommand(
                request.title(), Path.of(request.repository()), request.baseRef(),
                new ChangePolicy(request.allowedPaths(), request.deniedPaths()),
                request.shouldIncludeExistingChanges()
        ));
        URI location = URI.create("/api/v1/coding-sessions/" + session.sessionId());
        return ResponseEntity.created(location).body(CodingSessionResponse.from(session));
    }

    /** 查询最近更新的 Coding Session。 */
    @GetMapping
    public CodingSessionListResponse recent(
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "limit 不能小于 1")
            @Max(value = 100, message = "limit 不能大于 100") int limit
    ) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit 必须在 1 到 100 之间");
        }
        return CodingSessionListResponse.from(applicationService.recent(limit));
    }

    /** 查询指定 Coding Session 的持久详情。 */
    @GetMapping("/{sessionId}")
    public CodingSessionResponse get(@PathVariable UUID sessionId) {
        return CodingSessionResponse.from(applicationService.get(sessionId));
    }

    /** 获取当前 Worktree 是否仍可安全合并到原始源码仓库。 */
    @GetMapping("/{sessionId}/merge-readiness")
    public MergeReadinessResponse mergeReadiness(@PathVariable UUID sessionId) {
        return MergeReadinessResponse.from(integrationService.readiness(sessionId));
    }

    /** 二次确认后创建正式提交并通过 fast-forward 写回源码仓库。 */
    @PostMapping("/{sessionId}/merge")
    public CodingSessionResponse merge(@PathVariable UUID sessionId,
                                       @Valid @RequestBody com.codingreview.controlplane.api.session.dto.MergeCodingSessionRequest request) {
        CodingSession merged = integrationService.merge(sessionId, new MergeCodingSessionRequest(
                request.commitMessage(), request.expectedSourceHead(), request.expectedTargetBranch(), request.workspaceFingerprint()));
        return CodingSessionResponse.from(merged);
    }

    /** 查询 Session 最近一次写回原仓库的持久状态。 */
    @GetMapping("/{sessionId}/integration")
    public ResponseEntity<CodingSessionIntegrationResponse> integration(
            @PathVariable UUID sessionId
    ) {
        return integrationService.integration(sessionId)
                .map(CodingSessionIntegrationResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}

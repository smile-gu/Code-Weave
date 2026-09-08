package com.codingreview.controlplane.api.session;

import com.codingreview.controlplane.api.session.dto.CodingSessionListResponse;
import com.codingreview.controlplane.api.session.dto.CodingSessionResponse;
import com.codingreview.controlplane.api.session.dto.CreateCodingSessionRequest;
import com.codingreview.controlplane.api.session.dto.ReviewBaselineReadinessResponse;
import com.codingreview.controlplane.api.session.dto.WorkspaceChangesResponse;
import com.codingreview.controlplane.application.session.CodingSessionApplicationService;
import com.codingreview.controlplane.application.session.CodingTurnApplicationService;
import com.codingreview.controlplane.application.session.CreateCodingSessionCommand;
import com.codingreview.controlplane.application.session.ReviewBaselineApplicationService;
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
    private final ReviewBaselineApplicationService baselineService;
    private final CodingTurnApplicationService turnService;

    /** 装配 Session 生命周期与活动基线服务。 */
    public CodingSessionController(
            CodingSessionApplicationService applicationService,
            ReviewBaselineApplicationService baselineService,
            CodingTurnApplicationService turnService
    ) {
        this.applicationService = applicationService;
        this.baselineService = baselineService;
        this.turnService = turnService;
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

    /** 读取当前 Session 相对活动审查基线的累计代码变更。 */
    @GetMapping("/{sessionId}/changes")
    public WorkspaceChangesResponse changes(@PathVariable UUID sessionId) {
        return WorkspaceChangesResponse.from(turnService.changes(sessionId));
    }

    /** 检查当前 Git 提交是否可作为下一审查周期的活动基线。 */
    @GetMapping("/{sessionId}/review-baseline-readiness")
    public ReviewBaselineReadinessResponse reviewBaselineReadiness(
            @PathVariable UUID sessionId
    ) {
        return ReviewBaselineReadinessResponse.from(baselineService.readiness(sessionId));
    }

    /** 二次确认 Git 事实后推进活动审查基线。 */
    @PostMapping("/{sessionId}/review-baseline/advance")
    public CodingSessionResponse advanceReviewBaseline(
            @PathVariable UUID sessionId,
            @Valid @RequestBody com.codingreview.controlplane.api.session.dto.AdvanceReviewBaselineRequest request
    ) {
        CodingSession advanced = baselineService.advance(
                sessionId,
                new com.codingreview.controlplane.application.session.AdvanceReviewBaselineRequest(
                        request.expectedActiveBaselineCommit(), request.expectedHead(),
                        request.workspaceFingerprint()
                )
        );
        return CodingSessionResponse.from(advanced);
    }
}

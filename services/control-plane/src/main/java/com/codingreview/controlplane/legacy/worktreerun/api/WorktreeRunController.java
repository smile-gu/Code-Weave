package com.codingreview.controlplane.legacy.worktreerun.api;

import com.codingreview.controlplane.legacy.worktreerun.api.dto.CreateWorktreeRunRequest;
import com.codingreview.controlplane.legacy.worktreerun.api.dto.CreateRunResponse;
import com.codingreview.controlplane.legacy.worktreerun.api.dto.RunEventsResponse;
import com.codingreview.controlplane.legacy.worktreerun.api.dto.RunStatusResponse;
import com.codingreview.controlplane.api.common.dto.RunChangesResponse;
import com.codingreview.controlplane.legacy.worktreerun.api.dto.result.RunResultResponse;
import com.codingreview.controlplane.legacy.worktreerun.application.WorktreeRunApplicationService;
import com.codingreview.controlplane.legacy.worktreerun.application.RunExecutionSnapshot;
import com.codingreview.controlplane.legacy.worktreerun.application.SubmitWorktreeRunCommand;
import com.codingreview.controlplane.observability.run.RunEvent;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** 旧版 Worktree Run 的异步提交与只读查询入口。 */
@Validated
@RestController
@Profile("legacy-worktree-run")
@RequestMapping("/api/v1/legacy/worktree-runs")
public class WorktreeRunController {

    private final WorktreeRunApplicationService applicationService;

    public WorktreeRunController(WorktreeRunApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    /** 接收并异步提交一次 旧版 Worktree Run。 */
    @PostMapping
    public ResponseEntity<CreateRunResponse> create(
            @Valid @RequestBody CreateWorktreeRunRequest request
    ) {
        RunExecutionSnapshot snapshot = applicationService.submit(new SubmitWorktreeRunCommand(
                Path.of(request.repository()),
                request.baseRef(),
                request.taskPrompt(),
                request.acceptanceCriteria(),
                new ChangePolicy(request.allowedPaths(), request.deniedPaths())
        ));
        return ResponseEntity.accepted().body(CreateRunResponse.queued(snapshot.runId()));
    }

    /** 查询指定 Run 的当前执行状态。 */
    @GetMapping("/{runId}")
    public RunStatusResponse status(@PathVariable UUID runId) {
        return RunStatusResponse.from(applicationService.get(runId));
    }

    /** 查询已结束 Run 的结构化业务结果。 */
    @GetMapping("/{runId}/result")
    public RunResultResponse result(@PathVariable UUID runId) {
        return RunResultResponse.from(applicationService.result(runId));
    }

    /** 查询指定 Run 已采集的文件列表和 Unified Diff。 */
    @GetMapping("/{runId}/changes")
    public RunChangesResponse changes(@PathVariable UUID runId) {
        return RunChangesResponse.from(runId, applicationService.changes(runId));
    }

    /** 按事件序号增量查询指定 Run 的运行事件。 */
    @GetMapping("/{runId}/events")
    public RunEventsResponse events(
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "afterSequence 不能小于 0") long afterSequence
    ) {
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence 不能小于 0");
        }
        List<RunEvent> events = applicationService.events(runId, afterSequence);
        return RunEventsResponse.from(runId, afterSequence, events);
    }
}

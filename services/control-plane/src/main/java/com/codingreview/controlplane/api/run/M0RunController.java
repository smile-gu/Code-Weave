package com.codingreview.controlplane.api.run;

import com.codingreview.controlplane.api.run.dto.CreateM0RunRequest;
import com.codingreview.controlplane.api.run.dto.CreateRunResponse;
import com.codingreview.controlplane.api.run.dto.RunEventsResponse;
import com.codingreview.controlplane.api.run.dto.RunStatusResponse;
import com.codingreview.controlplane.application.run.M0RunApplicationService;
import com.codingreview.controlplane.application.run.RunExecutionSnapshot;
import com.codingreview.controlplane.application.run.SubmitM0RunCommand;
import com.codingreview.controlplane.observability.run.RunEvent;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import jakarta.validation.Valid;
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

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** M0 Run 的异步提交与只读查询入口。 */
@Validated
@RestController
@RequestMapping("/api/v1/m0/runs")
public class M0RunController {

    private final M0RunApplicationService applicationService;

    public M0RunController(M0RunApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping
    public ResponseEntity<CreateRunResponse> create(
            @Valid @RequestBody CreateM0RunRequest request
    ) {
        RunExecutionSnapshot snapshot = applicationService.submit(new SubmitM0RunCommand(
                Path.of(request.repository()),
                request.baseRef(),
                request.taskPrompt(),
                request.acceptanceCriteria(),
                new ChangePolicy(request.allowedPaths(), request.deniedPaths())
        ));
        return ResponseEntity.accepted().body(CreateRunResponse.queued(snapshot.runId()));
    }

    @GetMapping("/{runId}")
    public RunStatusResponse status(@PathVariable UUID runId) {
        return RunStatusResponse.from(applicationService.get(runId));
    }

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

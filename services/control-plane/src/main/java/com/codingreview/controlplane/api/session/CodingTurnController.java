package com.codingreview.controlplane.api.session;

import com.codingreview.controlplane.api.run.dto.change.RunChangesResponse;
import com.codingreview.controlplane.api.session.dto.CodingTurnListResponse;
import com.codingreview.controlplane.api.session.dto.CodingTurnResponse;
import com.codingreview.controlplane.api.session.dto.CreateCodingTurnRequest;
import com.codingreview.controlplane.application.session.CodingTurnApplicationService;
import com.codingreview.controlplane.session.model.CodingTurn;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.util.UUID;

/** 提供多轮 Coding 的创建、查询、事件、Diff 和停止接口。 */
@Validated
@RestController
@RequestMapping("/api/v1/coding-sessions/{sessionId}/turns")
public class CodingTurnController {
    private final CodingTurnApplicationService service;

    /** 装配 Turn 应用服务。 */
    public CodingTurnController(CodingTurnApplicationService service) { this.service = service; }

    /** 提交一条用户消息并异步开始新 Turn。 */
    @PostMapping
    public ResponseEntity<CodingTurnResponse> submit(@PathVariable UUID sessionId, @Valid @RequestBody CreateCodingTurnRequest request) {
        CodingTurn turn = service.submit(sessionId, request.prompt());
        return ResponseEntity.created(URI.create("/api/v1/coding-sessions/" + sessionId + "/turns/" + turn.turnId()))
                .body(CodingTurnResponse.from(turn));
    }

    /** 查询 Session 的多轮历史。 */
    @GetMapping
    public CodingTurnListResponse list(@PathVariable UUID sessionId) { return CodingTurnListResponse.from(service.list(sessionId)); }

    /** 查询指定 Turn 当前状态。 */
    @GetMapping("/{turnId}")
    public CodingTurnResponse get(@PathVariable UUID sessionId, @PathVariable UUID turnId) { return CodingTurnResponse.from(service.get(sessionId, turnId)); }

    /** 读取本轮相对 Session 基线的累计代码变更。 */
    @GetMapping("/{turnId}/changes")
    public RunChangesResponse changes(@PathVariable UUID sessionId, @PathVariable UUID turnId) { return RunChangesResponse.from(turnId, service.changes(sessionId, turnId)); }

    /** 请求停止当前 Turn。 */
    @PostMapping("/{turnId}/cancel")
    public CodingTurnResponse cancel(@PathVariable UUID sessionId, @PathVariable UUID turnId) { return CodingTurnResponse.from(service.cancel(sessionId, turnId)); }
}

package com.codingreview.controlplane.api.session;

import com.codingreview.controlplane.application.session.CodingTurnApplicationService;
import com.codingreview.controlplane.observability.run.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 历史一次性查询即使缺少终态事件，也能完整返回已有事实。 */
class CodingTurnEventsControllerTest {
    /** 验证有限事件查询的协议及作用域。 */
    @Test void readsHistoryWithoutTerminalNotification() throws Exception {
        var service = mock(CodingTurnApplicationService.class);
        UUID sessionId = UUID.randomUUID(), turnId = UUID.randomUUID();
        var event = new RunEvent(1, UUID.randomUUID(), turnId.toString(), "workspace", 1, 1,
                Instant.parse("2026-09-08T00:00:00Z"), RunEventType.STAGE_STARTED,
                JsonNodeFactory.instance.objectNode().put("stageProtocolVersion", 1).put("message", "检查工作区"));
        when(service.events(sessionId, turnId, 0)).thenReturn(List.of(event));
        MockMvcBuilders.standaloneSetup(new CodingTurnController(service)).build()
                .perform(get("/api/v1/coding-sessions/" + sessionId + "/turns/" + turnId + "/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("STAGE_STARTED"))
                .andExpect(jsonPath("$[0].payload.stageProtocolVersion").value(1))
                .andExpect(jsonPath("$.length()").value(1));
        verify(service).events(sessionId, turnId, 0);
    }
}

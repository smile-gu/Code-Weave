package com.codingreview.controlplane.api.system;

import com.codingreview.controlplane.runtime.readiness.RuntimeReadinessService;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeCheck;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeCheckStatus;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeComponent;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeReadiness;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RuntimeReadinessControllerTest {

    @Test
    void returnsNotReadyDetailsWithHttpOk() throws Exception {
        RuntimeReadiness readiness = RuntimeReadiness.from(
                Instant.parse("2026-08-18T02:00:00Z"),
                List.of(new RuntimeCheck(
                        RuntimeComponent.CLAUDE_CODE,
                        RuntimeCheckStatus.FAILED,
                        true,
                        null,
                        "D:/develop/nodejs/claude.ps1",
                        "当前启动入口不受支持。",
                        "请配置 claude.cmd。"
                ))
        );
        RuntimeReadinessService service = () -> readiness;
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new RuntimeReadinessController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        JsonMapper.builder()
                                .findAndAddModules()
                                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()
                ))
                .build();

        mockMvc.perform(get("/api/v1/system/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_READY"))
                .andExpect(jsonPath("$.checkedAt").value("2026-08-18T02:00:00Z"))
                .andExpect(jsonPath("$.checks[0].component").value("CLAUDE_CODE"))
                .andExpect(jsonPath("$.checks[0].status").value("FAILED"))
                .andExpect(jsonPath("$.checks[0].remediation").value("请配置 claude.cmd。"));
    }
}

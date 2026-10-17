package com.codingreview.controlplane.api.session.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 提交一轮 Coding 的用户输入。 */
public record CreateCodingTurnRequest(
        @NotBlank(message = "本轮 Coding 指令不能为空")
        @Size(max = 20_000, message = "本轮 Coding 指令不能超过 20000 个字符")
        String prompt
) { }

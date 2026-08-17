package com.codingreview.controlplane.api.run.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 创建 M0 Run 的 HTTP 请求。平台运行参数不属于该边界。 */
public record CreateM0RunRequest(
        @NotBlank(message = "请选择本地 Git 仓库")
        @Size(max = 2048, message = "仓库路径不能超过 2048 个字符")
        String repository,

        @Size(max = 255, message = "基准版本不能超过 255 个字符")
        String baseRef,

        @NotBlank(message = "请填写 Coding 任务说明")
        @Size(max = 20000, message = "任务说明不能超过 20000 个字符")
        String taskPrompt,

        @NotNull(message = "验收标准不能为空")
        @Size(max = 100, message = "验收标准不能超过 100 条")
        List<@NotBlank(message = "验收标准不能包含空白项")
                @Size(max = 1000, message = "单条验收标准不能超过 1000 个字符") String>
                acceptanceCriteria,

        @NotEmpty(message = "请至少配置一条允许修改的路径规则")
        @Size(max = 100, message = "允许路径规则不能超过 100 条")
        List<@NotBlank(message = "允许路径不能包含空白项")
                @Size(max = 512, message = "单条允许路径不能超过 512 个字符") String>
                allowedPaths,

        @NotNull(message = "禁止修改的路径规则不能为空")
        @Size(max = 100, message = "禁止路径规则不能超过 100 条")
        List<@NotBlank(message = "禁止路径不能包含空白项")
                @Size(max = 512, message = "单条禁止路径不能超过 512 个字符") String>
                deniedPaths
) {
}

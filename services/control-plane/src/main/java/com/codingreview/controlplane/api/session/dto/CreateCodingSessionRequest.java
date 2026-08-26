package com.codingreview.controlplane.api.session.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 创建 Coding Session 的 HTTP 请求。 */
public record CreateCodingSessionRequest(
        @Size(max = 120, message = "会话名称不能超过 120 个字符")
        String title,

        @NotBlank(message = "本地 Git 仓库不能为空")
        @Size(max = 2048, message = "仓库路径不能超过 2048 个字符")
        String repository,

        @Size(max = 255, message = "基准版本不能超过 255 个字符")
        String baseRef,

        @NotEmpty(message = "请至少配置一条允许修改的路径规则")
        @Size(max = 100, message = "允许路径规则不能超过 100 条")
        List<@NotBlank(message = "允许修改路径不能包含空规则")
                @Size(max = 512, message = "单条允许路径不能超过 512 个字符") String>
                allowedPaths,

        @NotNull(message = "禁止修改路径不能为空")
        @Size(max = 100, message = "禁止路径规则不能超过 100 条")
        List<@NotBlank(message = "禁止修改路径不能包含空规则")
                @Size(max = 512, message = "单条禁止路径不能超过 512 个字符") String>
                deniedPaths,

        Boolean includeExistingChanges
) {
    /** 将路径规则复制为不可变列表。 */
    public CreateCodingSessionRequest {
        allowedPaths = allowedPaths == null ? null : List.copyOf(allowedPaths);
        deniedPaths = deniedPaths == null ? null : List.copyOf(deniedPaths);
    }

    /** 默认不把创建 Session 前的未提交修改纳入累计审查。 */
    public boolean shouldIncludeExistingChanges() {
        return Boolean.TRUE.equals(includeExistingChanges);
    }
}

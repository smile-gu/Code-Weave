package com.codingreview.controlplane.api.session.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 用户确认将 Session Worktree 写回源码仓库的请求。 */
public record MergeCodingSessionRequest(
        @NotBlank(message = "提交说明不能为空") @Size(max = 500, message = "提交说明不能超过 500 个字符") String commitMessage,
        @NotBlank(message = "原仓库提交基线不能为空") String expectedSourceHead,
        @NotBlank(message = "目标分支不能为空") String expectedTargetBranch,
        @NotBlank(message = "Worktree 指纹不能为空") String workspaceFingerprint
) { }

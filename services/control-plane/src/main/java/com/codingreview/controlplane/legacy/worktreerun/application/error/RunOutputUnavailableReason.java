package com.codingreview.controlplane.legacy.worktreerun.application.error;

/** Run 输出当前不可读取的业务原因。 */
public enum RunOutputUnavailableReason {
    RUN_NOT_FINISHED(
            "RUN_NOT_FINISHED",
            "任务尚未执行结束，请等待任务进入终态后再读取结果。"
    ),
    RESULT_UNAVAILABLE(
            "RESULT_UNAVAILABLE",
            "任务因运行基础设施故障结束，没有可读取的业务结果。"
    ),
    CHANGES_UNAVAILABLE(
            "CHANGES_UNAVAILABLE",
            "任务在代码变更采集前结束，没有可读取的代码变更。"
    );

    private final String code;
    private final String message;

    RunOutputUnavailableReason(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /** 返回提供给前端判断的稳定错误码。 */
    public String code() {
        return code;
    }

    /** 返回面向用户的错误说明。 */
    public String message() {
        return message;
    }
}

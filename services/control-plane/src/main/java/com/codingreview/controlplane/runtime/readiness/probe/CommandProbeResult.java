package com.codingreview.controlplane.runtime.readiness.probe;

/** 记录一次只读命令探测的进程级结果。 */
public record CommandProbeResult(
        boolean started,
        boolean timedOut,
        Integer exitCode,
        String output,
        boolean outputTruncated,
        String diagnostic
) {
    /** 判断命令是否正常启动并以零退出码结束。 */
    public boolean successful() {
        return started && !timedOut && Integer.valueOf(0).equals(exitCode);
    }

    /** 创建进程启动失败结果。 */
    public static CommandProbeResult startFailed(String diagnostic) {
        return new CommandProbeResult(false, false, null, "", false, diagnostic);
    }
}

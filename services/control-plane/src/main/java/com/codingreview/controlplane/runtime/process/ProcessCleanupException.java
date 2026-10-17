package com.codingreview.controlplane.runtime.process;

/** 表示已知父子进程在有界清理后仍未全部退出。 */
public final class ProcessCleanupException extends RuntimeException {
    /** 使用底层清理或事件故障创建稳定的进程清理异常。 */
    public ProcessCleanupException(String message, Throwable cause) {
        super(message, cause);
    }
}

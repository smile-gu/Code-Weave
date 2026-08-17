package com.codingreview.controlplane.adapter.claude.protocol;

/** Claude Code 输出不符合约定 NDJSON 协议时抛出的异常。 */
public class ClaudeStreamProtocolException extends RuntimeException {

    public ClaudeStreamProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}

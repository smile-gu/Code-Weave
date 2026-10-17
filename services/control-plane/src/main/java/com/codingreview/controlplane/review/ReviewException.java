package com.codingreview.controlplane.review;

/** 稳定且不回显宿主路径、内部响应或凭证的集成失败。 */
public final class ReviewException extends RuntimeException {
    private final String code;

    public ReviewException(String code) {
        super("Review 基础设施状态未能确认：" + code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

package com.codingreview.controlplane.session.persistence;

/** 区分 Turn 历史的数据损坏、版本不兼容和暂时性读取故障。 */
public enum CodingTurnHistoryFailureKind {
    DATA_CORRUPTED,
    VERSION_UNSUPPORTED,
    IO_FAILURE
}

package com.codingreview.controlplane.workspace.change;

import com.codingreview.controlplane.workspace.change.model.GitChangeSet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 为一份确定的 Git 变更快照计算稳定 SHA-256 指纹。 */
public final class WorkspaceFingerprintCalculator {

    /** 对基准提交、文件元数据和完整 Unified Diff 计算指纹。 */
    public String calculate(GitChangeSet changes) {
        Objects.requireNonNull(changes, "changes");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, changes.baseCommit());
            for (var file : changes.files()) {
                update(digest, file.path());
                update(digest, file.oldPath());
                update(digest, file.changeType().name());
                update(digest, Boolean.toString(file.binary()));
            }
            update(digest, changes.unifiedDiff());
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256。", exception);
        }
    }

    /** 使用长度前缀写入一个可空字段，避免不同字段组合产生歧义。 */
    private void update(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}

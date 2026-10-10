package com.codingreview.controlplane.workspace.change;

import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.change.model.GitChangedFile;
import com.codingreview.controlplane.workspace.change.model.GitChangeType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** 为一份确定的 Git 变更快照计算稳定 SHA-256 指纹。 */
public final class WorkspaceFingerprintCalculator {

    /** 对基准提交、规范化文件身份和最终内容摘要计算指纹。 */
    public String calculate(GitChangeSet changes) {
        return calculate(changes, false);
    }

    /** 仅在调用方已确认 core.symlinks=false 时允许 Git 120000 的普通文本表示。 */
    public String calculate(GitChangeSet changes, boolean allowSymlinkText) {
        Objects.requireNonNull(changes, "changes");
        Path workspaceRoot = requireReadableWorkspace(changes.workspaceRoot());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "workspace-fingerprint-v3");
            update(digest, changes.baseCommit());
            TreeMap<String, GitChangedFile> presentFiles = new TreeMap<>();
            Set<String> deletedPaths = new TreeSet<>();
            for (GitChangedFile file : changes.files()) {
                if (file.changeType() == GitChangeType.DELETED) {
                    deletedPaths.add(file.path());
                    continue;
                }
                presentFiles.put(file.path(), file);
                if (file.changeType() == GitChangeType.RENAMED) {
                    deletedPaths.add(file.oldPath());
                }
            }
            deletedPaths.removeAll(presentFiles.keySet());
            Set<String> finalPaths = new TreeSet<>(deletedPaths);
            finalPaths.addAll(presentFiles.keySet());
            for (String path : finalPaths) {
                update(digest, "path");
                update(digest, path);
                GitChangedFile file = presentFiles.get(path);
                if (file == null) {
                    update(digest, "deleted");
                } else {
                    update(digest, "present");
                    update(digest, file.gitMode());
                    update(digest, contentDigest(workspaceRoot, file, allowSymlinkText));
                }
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256。", exception);
        }
    }

    /** 读取最终工作区文件或符号链接内容并计算摘要。 */
    private String contentDigest(Path workspaceRoot, GitChangedFile file, boolean allowSymlinkText) {
        Path path = resolvePath(workspaceRoot, file.path());
        if ("120000".equals(file.gitMode()) && (Files.isSymbolicLink(path) || !allowSymlinkText)) {
            return symlinkDigest(path, file.path());
        }
        if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("无法读取变更文件内容：" + file.path());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取变更文件内容：" + file.path(), exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256。", exception);
        }
    }

    /** 读取符号链接在 Git 中保存的目标文本并计算摘要。 */
    private String symlinkDigest(Path path, String relativePath) {
        if (!Files.isSymbolicLink(path)) {
            throw new IllegalStateException("Git mode 与工作区符号链接状态不一致：" + relativePath);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readSymbolicLink(path).toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取变更符号链接：" + relativePath, exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256。", exception);
        }
    }

    /** 确认指纹输入对应真实且可读的工作区目录。 */
    private Path requireReadableWorkspace(Path workspaceRoot) {
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)
                || !Files.isReadable(workspaceRoot)) {
            throw new IllegalStateException("工作区不存在或不可读，无法计算代码指纹：" + workspaceRoot);
        }
        try {
            return workspaceRoot.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("无法解析工作区真实路径：" + workspaceRoot, exception);
        }
    }

    /** 安全解析仓库相对路径，避免指纹读取越出工作区。 */
    private Path resolvePath(Path workspaceRoot, String relativePath) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalStateException("变更文件路径越出工作区：" + relativePath);
        }
        return resolved;
    }

    /** 使用长度前缀写入一个可空字段，避免不同字段组合产生歧义。 */
    private void update(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}

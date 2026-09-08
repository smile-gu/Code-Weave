package com.codingreview.controlplane.workspace.change;

import com.codingreview.controlplane.workspace.change.error.GitChangeException;
import com.codingreview.controlplane.workspace.change.error.GitChangeFailureType;
import com.codingreview.controlplane.workspace.change.model.GitChangeType;
import com.codingreview.controlplane.workspace.change.model.GitChangedFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 解析 Git 使用 NUL 分隔的 name-status 和 numstat 输出。 */
final class GitChangeParser {

    /** 解析已跟踪文件的 name-status 元数据。 */
    List<GitChangedFile> parseTrackedFiles(
            byte[] output,
            Set<String> binaryPaths,
            Map<String, String> finalModes
    ) {
        List<String> fields = splitNullTerminated(output);
        List<GitChangedFile> files = new ArrayList<>();
        for (int index = 0; index < fields.size();) {
            String status = fields.get(index++);
            if (status.isEmpty() || index >= fields.size()) {
                throw malformedMetadata("name-status", fields);
            }
            char type = status.charAt(0);
            if (type == 'R') {
                if (index + 1 >= fields.size()) {
                    throw malformedMetadata("rename", fields);
                }
                String oldPath = normalizePath(fields.get(index++));
                String newPath = normalizePath(fields.get(index++));
                files.add(new GitChangedFile(newPath, oldPath, GitChangeType.RENAMED,
                        binaryPaths.contains(newPath), requireFinalMode(finalModes, newPath)));
                continue;
            }
            String path = normalizePath(fields.get(index++));
            GitChangeType changeType = switch (type) {
                case 'A' -> GitChangeType.ADDED;
                case 'D' -> GitChangeType.DELETED;
                default -> GitChangeType.MODIFIED;
            };
            files.add(new GitChangedFile(path, null, changeType, binaryPaths.contains(path),
                    changeType == GitChangeType.DELETED
                            ? "000000" : requireFinalMode(finalModes, path)));
        }
        return files;
    }

    /** 解析 raw diff 中每个最终路径对应的 Git mode。 */
    Map<String, String> parseFinalModes(byte[] output) {
        List<String> fields = splitNullTerminated(output);
        Map<String, String> modes = new HashMap<>();
        for (int index = 0; index < fields.size();) {
            String header = fields.get(index++);
            String[] parts = header.split(" +");
            if (parts.length != 5 || !parts[0].startsWith(":")) {
                throw malformedMetadata("raw diff", fields);
            }
            String finalMode = parts[1];
            String status = parts[4];
            if (index >= fields.size()) {
                throw malformedMetadata("raw diff path", fields);
            }
            String finalPath = normalizePath(fields.get(index++));
            if (status.startsWith("R") || status.startsWith("C")) {
                if (index >= fields.size()) {
                    throw malformedMetadata("raw diff destination", fields);
                }
                finalPath = normalizePath(fields.get(index++));
            }
            modes.put(finalPath, finalMode);
        }
        return modes;
    }

    /** 解析 numstat 输出中的二进制文件路径。 */
    Set<String> parseBinaryPaths(byte[] output) {
        List<String> records = splitNullTerminated(output);
        Set<String> binaryPaths = new HashSet<>();
        for (int index = 0; index < records.size(); index++) {
            String record = records.get(index);
            int firstTab = record.indexOf('\t');
            int secondTab = firstTab < 0 ? -1 : record.indexOf('\t', firstTab + 1);
            if (firstTab < 0 || secondTab < 0) {
                throw malformedMetadata("numstat", records);
            }
            String added = record.substring(0, firstTab);
            String deleted = record.substring(firstTab + 1, secondTab);
            String path = record.substring(secondTab + 1);
            if (path.isEmpty()) {
                if (index + 2 >= records.size()) {
                    throw malformedMetadata("rename numstat", records);
                }
                index++;
                path = records.get(++index);
            }
            if ("-".equals(added) || "-".equals(deleted)) {
                binaryPaths.add(normalizePath(path));
            }
        }
        return binaryPaths;
    }

    /** 将 Git 的 NUL 分隔字节流拆分为 UTF-8 字段。 */
    List<String> splitNullTerminated(byte[] output) {
        List<String> fields = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < output.length; index++) {
            if (output[index] == 0) {
                fields.add(new String(output, start, index - start, StandardCharsets.UTF_8));
                start = index + 1;
            }
        }
        if (start < output.length) {
            fields.add(new String(output, start, output.length - start, StandardCharsets.UTF_8));
        }
        return fields;
    }

    /** 判断补丁内容是否包含 Git 二进制标记。 */
    boolean containsBinaryMarker(byte[] patch) {
        String text = new String(patch, StandardCharsets.ISO_8859_1);
        return text.contains("Binary files ");
    }

    /** 返回指定非删除路径的最终 Git mode，缺失时拒绝生成不可信快照。 */
    private String requireFinalMode(Map<String, String> finalModes, String path) {
        String mode = finalModes.get(path);
        if (mode == null || "000000".equals(mode)) {
            throw malformedMetadata("final mode for " + path, List.copyOf(finalModes.keySet()));
        }
        return mode;
    }

    /** 将 Git 路径统一转换为正斜杠形式。 */
    private String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    /** 创建表示 Git 元数据格式异常的采集错误。 */
    private GitChangeException malformedMetadata(String kind, List<String> fields) {
        return new GitChangeException(GitChangeFailureType.COLLECT_FAILED,
                "Git 返回的变更数据无法解析，已停止本次采集。", kind + ": " + fields);
    }
}

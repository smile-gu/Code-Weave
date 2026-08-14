package com.codingreview.controlplane.workspace.change;

import com.codingreview.controlplane.workspace.change.error.GitChangeException;
import com.codingreview.controlplane.workspace.change.error.GitChangeFailureType;
import com.codingreview.controlplane.workspace.change.model.GitChangeType;
import com.codingreview.controlplane.workspace.change.model.GitChangedFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 解析 Git 使用 NUL 分隔的 name-status 和 numstat 输出。 */
final class GitChangeParser {

    List<GitChangedFile> parseTrackedFiles(byte[] output, Set<String> binaryPaths) {
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
                        binaryPaths.contains(newPath)));
                continue;
            }
            String path = normalizePath(fields.get(index++));
            GitChangeType changeType = switch (type) {
                case 'A' -> GitChangeType.ADDED;
                case 'D' -> GitChangeType.DELETED;
                default -> GitChangeType.MODIFIED;
            };
            files.add(new GitChangedFile(path, null, changeType, binaryPaths.contains(path)));
        }
        return files;
    }

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

    boolean containsBinaryMarker(byte[] patch) {
        String text = new String(patch, StandardCharsets.ISO_8859_1);
        return text.contains("Binary files ");
    }

    private String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    private GitChangeException malformedMetadata(String kind, List<String> fields) {
        return new GitChangeException(GitChangeFailureType.COLLECT_FAILED,
                "Git 返回的变更数据无法解析，已停止本次采集。", kind + ": " + fields);
    }
}

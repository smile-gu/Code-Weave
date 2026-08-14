package com.codingreview.controlplane.workspace.change.policy.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 一次 Coding 任务允许和禁止修改的仓库相对路径规则。 */
public record ChangePolicy(
        List<String> allowedPaths,
        List<String> deniedPaths
) {
    public ChangePolicy {
        allowedPaths = normalizeRules(allowedPaths, "allowedPaths");
        deniedPaths = normalizeRules(deniedPaths, "deniedPaths");
    }

    private static List<String> normalizeRules(List<String> rules, String field) {
        Objects.requireNonNull(rules, field);
        List<String> normalizedRules = new ArrayList<>(rules.size());
        for (String rule : rules) {
            if (rule == null || rule.isBlank()) {
                throw new IllegalArgumentException(field + " must not contain blank rules");
            }
            String normalized = rule.trim().replace('\\', '/');
            while (normalized.startsWith("./")) {
                normalized = normalized.substring(2);
            }
            if (normalized.isBlank()
                    || normalized.startsWith("/")
                    || normalized.matches("^[A-Za-z]:/.*")
                    || List.of(normalized.split("/")).contains("..")) {
                throw new IllegalArgumentException(field + " must contain repository-relative rules");
            }
            normalizedRules.add(normalized);
        }
        return List.copyOf(normalizedRules);
    }
}

package com.codingreview.controlplane.workspace.git;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared process policy for repository inspection and Review's read-only collection. */
public final class ReadOnlyGit {
    private ReadOnlyGit() {}

    public static ProcessBuilder process(
            String executable, Path directory, List<String> arguments) {
        // These are the content-converting commands used by the read-only
        // callers. Metadata probes (rev-parse/config/ls-files/check-attr) do
        // not run clean/process filters and need no recursive policy probe.
        if (!arguments.isEmpty() && List.of("status", "diff").contains(arguments.getFirst()))
            rejectContentFilters(executable, directory);
        return uncheckedProcess(executable, directory, arguments);
    }

    /**
     * Attribute inspection does not convert content or execute filter programs. Reject active
     * filters rather than changing clean/LFS Diff semantics.
     */
    private static void rejectContentFilters(String executable, Path directory) {
        byte[] paths =
                probe(
                        executable,
                        directory,
                        List.of("ls-files", "-z", "--cached", "--others", "--exclude-standard"),
                        null);
        byte[] attributes =
                probe(
                        executable,
                        directory,
                        List.of("check-attr", "-z", "filter", "--stdin"),
                        paths);
        String[] fields =
                new String(attributes, java.nio.charset.StandardCharsets.UTF_8).split("\0", -1);
        if ((fields.length - 1) % 3 != 0) throw filterError("REPOSITORY_FILTER_CHECK_FAILED");
        // check-attr's textual sentinel values are also valid driver names.
        // Enumerate effective config (including includes/global config) without
        // executing it. A configured ambiguous driver is rejected even when
        // its attribute might actually be unset: safety cannot be proved.
        String config =
                new String(
                        probe(
                                executable,
                                directory,
                                List.of("config", "--null", "--name-only", "--list"),
                                null),
                        java.nio.charset.StandardCharsets.UTF_8);
        var ambiguousDrivers = new java.util.HashSet<String>();
        for (String key : config.split("\0", -1)) {
            for (String driver : List.of("unset", "unspecified")) {
                for (String conversion : List.of("clean", "process")) {
                    if (key.equals("filter." + driver + "." + conversion))
                        ambiguousDrivers.add(driver);
                }
            }
        }
        for (int index = 2; index < fields.length - 1; index += 3) {
            if ((!fields[index].equals("unspecified") && !fields[index].equals("unset"))
                    || ambiguousDrivers.contains(fields[index]))
                throw filterError("REPOSITORY_FILTER_UNSUPPORTED");
        }
    }

    private static GitRepositoryException filterError(String code) {
        return new GitRepositoryException(
                code, "当前仓库的内容过滤器无法安全用于只读采集，请先处理过滤配置。", "Content filter policy");
    }

    private static byte[] probe(
            String executable, Path directory, List<String> args, byte[] input) {
        Process child = null;
        try (var io = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            child = uncheckedProcess(executable, directory, args).start();
            Process running = child;
            var output =
                    io.submit(
                            () -> {
                                try (var stream = running.getInputStream()) {
                                    byte[] bytes = stream.readNBytes(16 * 1024 * 1024 + 1);
                                    if (bytes.length > 16 * 1024 * 1024)
                                        throw filterError("REPOSITORY_FILTER_CHECK_FAILED");
                                    return bytes;
                                }
                            });
            var error =
                    io.submit(
                            () -> {
                                try (var stream = running.getErrorStream()) {
                                    stream.transferTo(java.io.OutputStream.nullOutputStream());
                                }
                                return null;
                            });
            var writer =
                    io.submit(
                            () -> {
                                try (var stream = running.getOutputStream()) {
                                    if (input != null) stream.write(input);
                                } catch (java.io.IOException failure) {
                                    if (input != null) throw failure;
                                }
                                return null;
                            });
            try {
                if (!child.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
                    throw filterError("REPOSITORY_FILTER_CHECK_FAILED");
                writer.get(1, java.util.concurrent.TimeUnit.SECONDS);
                error.get(1, java.util.concurrent.TimeUnit.SECONDS);
                byte[] bytes = output.get(1, java.util.concurrent.TimeUnit.SECONDS);
                if (child.exitValue() != 0) throw filterError("REPOSITORY_FILTER_CHECK_FAILED");
                return bytes;
            } finally {
                child.destroyForcibly();
                try {
                    child.getInputStream().close();
                } catch (java.io.IOException ignored) {
                }
                try {
                    child.getErrorStream().close();
                } catch (java.io.IOException ignored) {
                }
                try {
                    child.getOutputStream().close();
                } catch (java.io.IOException ignored) {
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw filterError("REPOSITORY_FILTER_CHECK_FAILED");
        } catch (java.io.IOException
                | java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException error) {
            throw filterError("REPOSITORY_FILTER_CHECK_FAILED");
        }
    }

    private static ProcessBuilder uncheckedProcess(
            String executable, Path directory, List<String> arguments) {
        var command =
                new ArrayList<>(
                        List.of(
                                executable,
                                "-c",
                                "core.fsmonitor=false",
                                "-c",
                                "diff.external=",
                                "-c",
                                "diff.autoRefreshIndex=false",
                                "-c",
                                "core.quotepath=false",
                                "-C",
                                directory.toString()));
        if (!arguments.isEmpty() && arguments.getFirst().equals("diff")) {
            command.addAll(List.of("diff", "--no-ext-diff", "--no-textconv"));
            command.addAll(arguments.subList(1, arguments.size()));
        } else {
            command.addAll(arguments);
        }
        var builder = new ProcessBuilder(command);
        builder.environment()
                .keySet()
                .removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("GIT_"));
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        return builder;
    }
}

package com.codingreview.controlplane.runtime.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 用文件屏障控制真实父进程退出，覆盖仍存活子进程与继承输出管道。 */
public final class ChildProcessFixture {
    /** 父进程创建子进程并等待观察屏障，子进程保持存活直到测试清理。 */
    public static void main(String[] args) throws Exception {
        if (args[0].equals("child")) { Thread.sleep(30000); return; }
        Path gate = Path.of(args[1]);
        var builder = new ProcessBuilder(command("child", gate, false));
        if (Boolean.parseBoolean(args[2])) builder.inheritIO();
        else builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        Process child = builder.start();
        Files.writeString(gate.resolveSibling("child.pid"), Long.toString(child.pid()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(gate)) {
            if (System.nanoTime() > deadline) { child.destroyForcibly(); throw new IllegalStateException("观察屏障超时"); }
            Thread.sleep(5);
        }
        System.out.println("{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"test\"}");
    }

    /** 构造跨平台的测试 Java 子进程命令。 */
    public static List<String> command(String mode, Path gate, boolean inherit) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                new java.io.File(ChildProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getAbsolutePath()
                        + java.io.File.pathSeparator
                        + new java.io.File(ProcessTreeCleaner.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getAbsolutePath(),
                ChildProcessFixture.class.getName(),
                mode, gate.toString(), Boolean.toString(inherit));
    }

    /** 仅在真实子进程已被记录后释放父进程，避免依赖固定睡眠构造退出顺序。 */
    public static ProcessTreeCleaner cleaner(Path gate) {
        return new ProcessTreeCleaner() {
            @Override public AutoCloseable track(Process process) {
                AutoCloseable registration = super.track(process);
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (process.descendants().findAny().isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait();
                observe(process);
                try { Files.writeString(gate, "observed"); }
                catch (Exception exception) { throw new IllegalStateException(exception); }
                return registration;
            }
        };
    }

    /** 断言之外也确保测试不会留下真实子进程。 */
    public static void cleanup(Path gate) throws Exception {
        Path pid = gate.resolveSibling("child.pid");
        if (Files.exists(pid)) {
            var handle = ProcessHandle.of(Long.parseLong(Files.readString(pid)));
            if (handle.isPresent() && handle.get().isAlive()) {
                handle.get().destroyForcibly();
                handle.get().onExit().get(5, TimeUnit.SECONDS);
            }
        }
    }
}

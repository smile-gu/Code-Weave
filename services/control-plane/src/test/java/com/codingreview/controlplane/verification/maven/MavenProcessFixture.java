package com.codingreview.controlplane.verification.maven;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 供 MavenVerifier 进程级测试启动的可控子进程。 */
public final class MavenProcessFixture {

    private MavenProcessFixture() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        switch (mode) {
            case "success" -> {
                System.out.println("fixture stdout");
                System.err.println("fixture stderr");
                writeReport(false, false);
            }
            case "test-failure" -> {
                System.out.println("fixture test failed");
                writeReport(true, false);
                System.exit(1);
            }
            case "invalid-report" -> {
                writeReport(false, true);
                System.exit(1);
            }
            case "no-report" -> System.out.println("build without tests");
            case "large-log" -> {
                System.out.print("x".repeat(8 * 1024));
                System.out.flush();
                Thread.sleep(30_000);
            }
            case "timeout-parent" -> {
                Process child = new ProcessBuilder(
                        javaExecutable(),
                        "-cp",
                        classPath(),
                        MavenProcessFixture.class.getName(),
                        "child-sleep"
                ).start();
                Files.writeString(Path.of("child.pid"), Long.toString(child.pid()), StandardCharsets.US_ASCII);
                Thread.sleep(30_000);
            }
            case "child-sleep" -> Thread.sleep(30_000);
            default -> throw new IllegalArgumentException("Unknown fixture mode: " + mode);
        }
    }

    private static void writeReport(boolean failed, boolean includeDoctype) throws Exception {
        Path reports = Path.of("target", "surefire-reports");
        Files.createDirectories(reports);
        String failure = failed
                ? "<failure type=\"java.lang.AssertionError\" message=\"expected true\">stack</failure>"
                : "";
        String doctype = includeDoctype ? "<!DOCTYPE testsuite [<!ENTITY xxe SYSTEM \"file:///missing\">]>" : "";
        String message = includeDoctype ? "&xxe;" : "";
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + doctype
                + "<testsuite name=\"demo\" tests=\"1\" failures=\"" + (failed ? 1 : 0)
                + "\" errors=\"0\" skipped=\"0\" time=\"0.125\">"
                + "<testcase classname=\"DemoTest\" name=\"works\">" + failure + message + "</testcase>"
                + "</testsuite>";
        Files.writeString(reports.resolve("TEST-DemoTest.xml"), xml, StandardCharsets.UTF_8);
    }

    static String javaExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    static String classPath() throws Exception {
        return Path.of(MavenProcessFixture.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
    }
}

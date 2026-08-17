package com.codingreview.controlplane.verification.maven.report;

import com.codingreview.controlplane.verification.maven.model.MavenTestSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 Surefire XML 聚合、失败明细和外部实体防护。 */
class SurefireReportParserTest {

    private final SurefireReportParser parser = new SurefireReportParser();

    @TempDir
    Path tempDir;

    @Test
    void aggregatesReportsAcrossModules() throws Exception {
        writeReport("module-a", "TEST-A.xml", 2, 0, 0, 1, "0.25", "");
        writeReport("module-b", "TEST-B.xml", 1, 1, 0, 0, "0.75",
                "<testcase classname=\"BTest\" name=\"fails\">"
                        + "<failure type=\"AssertionError\" message=\"expected value\">stack</failure>"
                        + "</testcase>");

        MavenTestSummary summary = parser.parse(tempDir);

        assertThat(summary.reportFiles()).isEqualTo(2);
        assertThat(summary.tests()).isEqualTo(3);
        assertThat(summary.passed()).isEqualTo(1);
        assertThat(summary.failures()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.durationSeconds()).isEqualTo(1.0);
        assertThat(summary.failedTests()).singleElement().satisfies(failure -> {
            assertThat(failure.testClass()).isEqualTo("BTest");
            assertThat(failure.testName()).isEqualTo("fails");
            assertThat(failure.message()).isEqualTo("expected value");
        });
    }

    @Test
    void returnsExplicitEmptySummaryWhenNoReportsExist() {
        MavenTestSummary summary = parser.parse(tempDir);

        assertThat(summary.reportsFound()).isFalse();
        assertThat(summary.tests()).isZero();
    }

    @Test
    void rejectsDoctypeAndExternalEntities() throws Exception {
        Path report = reportPath("module", "TEST-Unsafe.xml");
        Files.writeString(report,
                "<?xml version=\"1.0\"?><!DOCTYPE testsuite ["
                        + "<!ENTITY xxe SYSTEM \"file:///missing\">]>"
                        + "<testsuite tests=\"1\"><testcase name=\"unsafe\">&xxe;</testcase></testsuite>",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> parser.parse(tempDir))
                .isInstanceOf(MavenReportException.class)
                .hasMessageContaining("Invalid Surefire report");
    }

    private void writeReport(
            String module,
            String file,
            long tests,
            long failures,
            long errors,
            long skipped,
            String time,
            String testCases
    ) throws Exception {
        String xml = "<testsuite tests=\"" + tests
                + "\" failures=\"" + failures
                + "\" errors=\"" + errors
                + "\" skipped=\"" + skipped
                + "\" time=\"" + time + "\">"
                + testCases
                + "</testsuite>";
        Files.writeString(reportPath(module, file), xml, StandardCharsets.UTF_8);
    }

    private Path reportPath(String module, String file) throws Exception {
        Path reports = tempDir.resolve(module).resolve("target/surefire-reports");
        Files.createDirectories(reports);
        return reports.resolve(file);
    }
}

package com.codingreview.controlplane.verification.maven.report;

import com.codingreview.controlplane.verification.maven.model.MavenTestFailure;
import com.codingreview.controlplane.verification.maven.model.MavenTestSummary;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 安全解析单模块或多模块项目生成的 Surefire XML。 */
public final class SurefireReportParser {

    private static final int MAX_FAILURE_DETAILS = 100;
    private static final int MAX_FAILURE_MESSAGE_CHARS = 4_000;

    public MavenTestSummary parse(Path worktreePath) {
        List<Path> reports = findReports(worktreePath);
        long tests = 0;
        long failures = 0;
        long errors = 0;
        long skipped = 0;
        double durationSeconds = 0;
        List<MavenTestFailure> failedTests = new ArrayList<>();
        boolean detailsTruncated = false;

        for (Path report : reports) {
            Element suite = parseSuite(worktreePath, report);
            tests += readLong(suite, "tests", true, report);
            failures += readLong(suite, "failures", false, report);
            errors += readLong(suite, "errors", false, report);
            skipped += readLong(suite, "skipped", false, report);
            durationSeconds += readDouble(suite, "time", report);

            for (MavenTestFailure failure : readFailures(suite)) {
                if (failedTests.size() < MAX_FAILURE_DETAILS) {
                    failedTests.add(failure);
                } else {
                    detailsTruncated = true;
                }
            }
        }

        try {
            return new MavenTestSummary(
                    reports.size(), tests, failures, errors, skipped, durationSeconds,
                    failedTests, detailsTruncated
            );
        } catch (IllegalArgumentException exception) {
            throw new MavenReportException("Surefire report counters are inconsistent", exception);
        }
    }

    private List<Path> findReports(Path worktreePath) {
        try (var paths = Files.walk(worktreePath)) {
            return paths.filter(Files::isRegularFile)
                    .filter(this::isSurefireReport)
                    .sorted(Comparator.comparing(path -> worktreePath.relativize(path).toString()))
                    .toList();
        } catch (IOException | UncheckedIOException exception) {
            throw new MavenReportException("Unable to scan Surefire reports: " + exception.getMessage(), exception);
        }
    }

    private boolean isSurefireReport(Path path) {
        String fileName = path.getFileName().toString();
        Path parent = path.getParent();
        Path grandparent = parent == null ? null : parent.getParent();
        return fileName.startsWith("TEST-")
                && fileName.endsWith(".xml")
                && parent != null
                && "surefire-reports".equals(parent.getFileName().toString())
                && grandparent != null
                && "target".equals(grandparent.getFileName().toString());
    }

    private Element parseSuite(Path worktreePath, Path report) {
        try {
            DocumentBuilder builder = secureDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            Document document = builder.parse(report.toFile());
            Element root = document.getDocumentElement();
            if (root == null || !"testsuite".equals(root.getTagName())) {
                throw new MavenReportException(
                        "Unsupported Surefire report root: " + worktreePath.relativize(report), null
                );
            }
            return root;
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw new MavenReportException(
                    "Invalid Surefire report " + worktreePath.relativize(report) + ": " + exception.getMessage(),
                    exception
            );
        }
    }

    private DocumentBuilder secureDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    private long readLong(Element element, String attribute, boolean required, Path report) {
        String value = element.getAttribute(attribute);
        if (value.isBlank()) {
            if (required) {
                throw new MavenReportException("Missing " + attribute + " in " + report.getFileName(), null);
            }
            return 0;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative value");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new MavenReportException(
                    "Invalid " + attribute + " in " + report.getFileName() + ": " + value,
                    exception
            );
        }
    }

    private double readDouble(Element element, String attribute, Path report) {
        String value = element.getAttribute(attribute);
        if (value.isBlank()) {
            return 0;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed < 0) {
                throw new NumberFormatException("not a finite non-negative value");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new MavenReportException(
                    "Invalid " + attribute + " in " + report.getFileName() + ": " + value,
                    exception
            );
        }
    }

    private List<MavenTestFailure> readFailures(Element suite) {
        List<MavenTestFailure> failures = new ArrayList<>();
        NodeList testCases = suite.getElementsByTagName("testcase");
        for (int index = 0; index < testCases.getLength(); index++) {
            Element testCase = (Element) testCases.item(index);
            NodeList children = testCase.getChildNodes();
            for (int childIndex = 0; childIndex < children.getLength(); childIndex++) {
                Node child = children.item(childIndex);
                if (child instanceof Element detail
                        && ("failure".equals(detail.getTagName()) || "error".equals(detail.getTagName()))) {
                    failures.add(new MavenTestFailure(
                            nullableAttribute(testCase, "classname"),
                            nullableAttribute(testCase, "name"),
                            nullableAttribute(detail, "type"),
                            failureMessage(detail)
                    ));
                    break;
                }
            }
        }
        return failures;
    }

    private String failureMessage(Element detail) {
        String message = detail.getAttribute("message");
        if (message.isBlank()) {
            message = detail.getTextContent().strip();
        }
        if (message.length() > MAX_FAILURE_MESSAGE_CHARS) {
            return message.substring(0, MAX_FAILURE_MESSAGE_CHARS);
        }
        return message.isBlank() ? null : message;
    }

    private String nullableAttribute(Element element, String name) {
        String value = element.getAttribute(name);
        return value.isBlank() ? null : value;
    }
}

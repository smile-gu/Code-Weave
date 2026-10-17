package com.codingreview.controlplane.review;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

class ReviewReportContractTest {
    static Stream<Class<?>> contracts() {
        return Stream.of(
                ReviewReport.View.class,
                ReviewReport.Head.class,
                ReviewReport.Revision.class,
                ReviewReport.CreateRequest.class,
                ReviewReport.EditRequest.class,
                ReviewReport.ApprovalRequest.class,
                ReviewReport.CloseRequest.class,
                ReviewReport.Approval.class);
    }

    @ParameterizedTest
    @MethodSource("contracts")
    void strictSchemasAndTypedDtoAgreeAndRejectUnknownFields(Class<?> type) throws Exception {
        String name = ReviewReport.schema(type);
        var fixture =
                ReviewJson.parse(
                                Files.readAllBytes(
                                        Path.of(
                                                "../../contracts/fixtures/review-report-v1/examples.json")))
                        .path(name);
        var schema =
                ReviewJson.parse(
                        Files.readAllBytes(
                                Path.of("../../contracts/schemas/" + name + ".schema.json")));
        var fields = new HashSet<String>();
        schema.path("properties").fieldNames().forEachRemaining(fields::add);
        assertThat(fields)
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(type.getRecordComponents())
                                .map(java.lang.reflect.RecordComponent::getName)
                                .filter(
                                        nameOfField ->
                                                type != ReviewReport.Head.class
                                                        || !nameOfField.equals("handoffs"))
                                .toList());
        var positive = fixture.path("positive");
        ReviewWireSchemas.validate(name, positive);
        Object value =
                ReviewReport.decode(ReviewJson.canonical(positive), type, "REPORT_REQUEST_INVALID");
        if (value instanceof ReviewReport.Revision revision) revision.verify();
        if (value instanceof ReviewReport.Head head) head.verify();
        assertThatThrownBy(
                        () ->
                                ReviewReport.decode(
                                        ReviewJson.canonical(fixture.path("negative")),
                                        type,
                                        "REPORT_REQUEST_INVALID"))
                .isInstanceOf(ReviewException.class)
                .hasMessageContaining("REPORT_REQUEST_INVALID");
    }
}

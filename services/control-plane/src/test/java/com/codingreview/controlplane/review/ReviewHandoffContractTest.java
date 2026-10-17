package com.codingreview.controlplane.review;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

class ReviewHandoffContractTest {
    static Stream<Class<?>> contracts() {
        return Stream.of(
                ReviewHandoff.Content.class,
                ReviewHandoff.View.class,
                ReviewHandoff.PrepareRequest.class,
                ReviewHandoff.StartRequest.class,
                ReviewHandoff.RevokeRequest.class,
                ReviewReport.Head.class);
    }

    @ParameterizedTest
    @MethodSource("contracts")
    void typedWireAndCanonicalHashAgreeAndUnknownFieldsAreRejected(Class<?> type) throws Exception {
        String name =
                type == ReviewReport.Head.class
                        ? "review-report-head-v2"
                        : ReviewReport.schema(type);
        var fixture =
                ReviewJson.parse(
                                Files.readAllBytes(
                                        Path.of(
                                                "../../contracts/fixtures/review-handoff-v1/examples.json")))
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
                                .toList());
        var value =
                ReviewReport.decode(
                        ReviewJson.canonical(fixture.path("positive")),
                        type,
                        "HANDOFF_INTEGRITY_ERROR");
        if (value instanceof ReviewHandoff.Content content) content.verify();
        if (value instanceof ReviewHandoff.View view) view.content().verify();
        if (value instanceof ReviewReport.Head head) head.verify();
        assertThatThrownBy(
                        () ->
                                ReviewReport.decode(
                                        ReviewJson.canonical(fixture.path("negative")),
                                        type,
                                        "HANDOFF_REQUEST_INVALID"))
                .hasMessageContaining("HANDOFF_REQUEST_INVALID");
    }
}

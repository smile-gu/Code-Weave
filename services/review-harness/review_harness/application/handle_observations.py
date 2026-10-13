"""Register only complete material actually delivered by trusted tools."""

from review_harness.domain.graph import graph_error
from review_harness.domain.handles import HandleRegistry, merge_ranges


def observe_page(registry, ref, coverage, redaction, coordinates, cursor, next_cursor):
    lower, upper = coordinates["startLine"], coordinates["endLine"]
    first = lower + int(coordinates["startsMidLine"])
    last = upper - int(coordinates["endsMidLine"])
    registry, handle = registry.register_source(
        ref, coverage, redaction, [[first, last]] if first <= last else []
    )
    binding = registry.source_binding(handle)
    ranges = list(binding.observedRanges)
    continued = (
        cursor is not None
        and cursor == binding.expectedCursor
        and binding.pendingLine == lower
    )
    if continued and (upper > lower or not coordinates["endsMidLine"]):
        ranges = merge_ranges([*ranges, [lower, lower]])
    pending = (
        upper
        if coordinates["endsMidLine"]
        and (upper > lower or not coordinates["startsMidLine"] or continued)
        else None
    )
    rows = [s.model_dump() for s in registry.sources]
    rows[int(handle[1:]) - 1].update(
        observedRanges=ranges, pendingLine=pending, expectedCursor=next_cursor
    )
    registry = HandleRegistry.model_validate({**registry.model_dump(), "sources": rows})
    return registry, handle


def observe_tool(state, call, envelope, progress):
    registry = state.handleRegistry
    data = envelope.data
    handles = []
    if envelope.status == "OK" and not envelope.artifactRef:
        for ref in data.get("evidenceReferences", []):
            redaction = data.get("redactionState", "NONE")
            ranges = [[ref["startLine"], ref["endLine"]]]
            coordinates = data.get("coordinates") or data.get("pageCoordinates")
            if call.name == "search_code":
                match = next(
                    (
                        m
                        for m in data.get("matches", [])
                        if m["path"] == ref["path"] and m["line"] == ref["startLine"]
                    ),
                    None,
                )
                ranges = (
                    [[match["line"], match["line"]]]
                    if match and not match["snippetTruncated"]
                    else []
                )
                redaction = match["redactionState"] if match else "NONE"
            if coordinates:
                # A Diff page can include several file intervals. Clip each to
                # its actual code lines, never grant hunk/header metadata.
                clipped = {
                    **coordinates,
                    "startLine": ref["startLine"],
                    "endLine": ref["endLine"],
                    "startsMidLine": coordinates["startsMidLine"]
                    and ref["startLine"] == coordinates["startLine"],
                    "endsMidLine": coordinates["endsMidLine"]
                    and ref["endLine"] == coordinates["endLine"],
                }
                registry, handle = observe_page(
                    registry,
                    ref,
                    envelope.coverage,
                    redaction,
                    clipped,
                    call.arguments.get("cursor"),
                    data.get("nextCursor"),
                )
            else:
                registry, handle = registry.register_source(
                    ref, envelope.coverage, redaction, ranges
                )
            handles.append(handle)
        if call.name == "read_artifact":
            item = progress.get(call.arguments["artifactId"])
            if item and item["completed"] and item["readable"]:
                original = next(
                    (
                        r
                        for r in [
                            *state.sourceRefs,
                            *[r for m in state.messages for r in m.sourceRefs],
                        ]
                        if r.path == item["path"]
                    ),
                    None,
                )
                if original is None:
                    raise graph_error("HANDLE_SOURCE_INTEGRITY_FAILED")
                registry, handle = registry.register_source(
                    {
                        "sourceType": "CODE",
                        "side": "final",
                        "path": item["path"],
                        "sha256": original.contentSha256,
                        "startLine": item["startLine"],
                        "endLine": item["endLine"],
                    },
                    "FULL",
                    "NONE",
                    [[item["startLine"], item["endLine"]]],
                )
                handles.append(handle)
    # Original tool contract and platform refs stay intact; only the model view
    # replaces copyable references with locally assigned names.
    view = envelope.model_dump()
    view.pop("sourceRefs", None)
    view["data"] = {
        k: v for k, v in data.items() if k not in {"evidenceReferences", "sha256"}
    }
    view["data"]["sources"] = [
        registry.source_binding(h).view() for h in dict.fromkeys(handles)
    ]
    return registry, view

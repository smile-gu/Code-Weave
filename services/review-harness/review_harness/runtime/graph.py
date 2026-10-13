"""Explicitly injected test-only LangGraph executor; never selected by default."""

from typing import TypedDict
from uuid import uuid4

from langgraph.graph import END, START, StateGraph

from review_harness.application.redaction import redact, redact_structure
from review_harness.domain.graph import (
    GRAPH_TOOL_OUTPUT_BYTES,
    GraphMessage,
    graph_error,
    pending_calls,
)
from review_harness.domain.model import canonical
from review_harness.domain.real_graph import parse_state
from review_harness.domain.snapshot import WorkCancelled
from review_harness.runtime.compaction import Compactor
from review_harness.runtime.model_adapter import TestWindowExceeded


class GraphChannels(TypedDict):
    state: dict
    done: bool
    compactions: int


class GraphProbeExecutor:
    def __init__(self, engine, gateway):
        self.engine, self.gateway = engine, gateway
        self.engine.gateway = gateway
        self.last_state = None

    async def execute(self, context, signal):
        engine = self.engine
        state = await engine.initial(context, signal)
        await engine.checkpoints.assert_fresh(state)
        await engine.persist(state, signal)
        tool_digest = state.toolSetDigest

        async def assemble(channels):
            current = parse_state(channels["state"])
            if current.graphStepCount >= engine.settings.graph_max_steps:
                raise graph_error("CONTEXT_GRAPH_STEP_LIMIT_EXCEEDED")
            if (
                current.toolSetDigest != tool_digest
                or engine.allowed != self.gateway.allowed
            ):
                raise graph_error("CONTEXT_TOOL_SET_CHANGED")
            before = current.compactionCount
            current = await Compactor(engine).fit(context, current, signal)
            return {
                "compactions": current.compactionCount - before,
                "state": current.updated(
                    graphStepCount=current.graphStepCount + 1
                ).model_dump(),
            }

        async def model(channels):
            current = parse_state(channels["state"])
            attempts = channels["compactions"]
            while True:
                try:
                    current, response = await engine.invoke(context, current, signal)
                    break
                except TestWindowExceeded:
                    if attempts >= engine.settings.compaction_attempts:
                        raise graph_error("CONTEXT_WINDOW_EXCEEDED") from None
                    current = await Compactor(engine).compact(context, current, signal)
                    attempts += 1
            for call in response.toolCalls:
                if redact_structure(call.arguments)[1] != "NONE":
                    raise graph_error("CONTEXT_RESPONSE_INVALID")
            text, _ = redact(response.content)
            message = GraphMessage(
                messageId=str(uuid4()),
                role="assistant",
                content=text,
                toolCalls=response.toolCalls,
            )
            messages = [*current.messages, message]
            current = current.updated(
                revision=current.revision + 1,
                messages=[m.model_dump() for m in messages],
                pendingToolCalls=pending_calls(messages),
            )
            await engine.persist(current, signal)
            self.last_state = current
            return {"state": current.model_dump(), "done": not response.toolCalls}

        async def tools(channels):
            current = parse_state(channels["state"])
            for call in current.messages[-1].toolCalls:
                await engine.active(context, signal)
                envelope = await self.gateway.call(
                    context,
                    call.name,
                    call.arguments,
                    signal,
                    output_budget=GRAPH_TOOL_OUTPUT_BYTES,
                )
                if envelope.status == "CANCELLED":
                    raise WorkCancelled()
                # Preserve the entire Gateway envelope, including data/Cursor.
                # A missing body cannot be concealed by a State-only summary.
                content = canonical(envelope.model_dump()).decode()
                if len(content.encode()) > GRAPH_TOOL_OUTPUT_BYTES:
                    raise graph_error("CONTEXT_TOOL_OUTPUT_LIMIT_EXCEEDED")
                message = GraphMessage(
                    messageId=str(uuid4()),
                    role="tool",
                    toolCallId=call.toolCallId,
                    content=content,
                    artifactRefs=[envelope.artifactRef] if envelope.artifactRef else [],
                    sourceRefs=envelope.sourceRefs[:32],
                )
                messages = [*current.messages, message]
                current = current.updated(
                    revision=current.revision + 1,
                    messages=[m.model_dump() for m in messages],
                    pendingToolCalls=pending_calls(messages),
                )
                await engine.persist(current, signal)
            self.last_state = current
            return {"state": current.model_dump()}

        graph = StateGraph(GraphChannels)
        graph.add_node("assemble", assemble)
        graph.add_node("model", model)
        graph.add_node("tools", tools)
        graph.add_edge(START, "assemble")
        graph.add_edge("assemble", "model")
        graph.add_conditional_edges(
            "model",
            lambda channels: "end" if channels["done"] else "tools",
            {"end": END, "tools": "tools"},
        )
        graph.add_edge("tools", "assemble")
        compiled = graph.compile()
        # Gate snapshots use the real saver explicitly, so speculative LangGraph
        # node writes cannot adopt a compaction before exact commit confirmation.
        result = await compiled.ainvoke(
            {"state": state.model_dump(), "done": False},
            config={"recursion_limit": engine.settings.graph_max_steps * 4 + 4},
        )
        self.last_state = parse_state(result["state"])

    async def cleanup(self, context):
        await self.engine.cleanup(context.run_id)

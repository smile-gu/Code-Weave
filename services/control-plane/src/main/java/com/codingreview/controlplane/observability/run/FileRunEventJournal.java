package com.codingreview.controlplane.observability.run;

import com.codingreview.controlplane.observability.run.error.RunEventReadException;
import com.codingreview.controlplane.observability.run.error.RunEventWriteException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 将每个 Run 的结构化事件持久化为追加式 NDJSON 文件。 */
public final class FileRunEventJournal implements RunEventJournal {

    private static final String EVENTS_FILE = "events.ndjson";
    private static final String SAFE_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";

    private final Path runtimeRoot;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, RunState> runStates = new ConcurrentHashMap<>();

    public FileRunEventJournal(Path runtimeRoot) {
        this(runtimeRoot, JsonMapper.builder().findAndAddModules().build());
    }

    public FileRunEventJournal(Path runtimeRoot, ObjectMapper objectMapper) {
        if (runtimeRoot == null || !runtimeRoot.isAbsolute()) {
            throw new IllegalArgumentException("runtimeRoot must be absolute");
        }
        this.runtimeRoot = runtimeRoot.normalize();
        this.objectMapper = objectMapper.copy();
    }

    @Override
    public RunEvent append(String runId, String stage, int attempt, RunEventType type, JsonNode payload) {
        validateId(runId, "runId");
        validateId(stage, "stage");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }

        RunState state = stateFor(runId);
        state.lock.lock();
        try {
            RunEvent event = new RunEvent(
                    RunEvent.CURRENT_SCHEMA_VERSION,
                    UUID.randomUUID(),
                    runId,
                    stage,
                    attempt,
                    state.nextSequence,
                    Instant.now(),
                    type,
                    payload
            );
            appendDurably(eventFile(runId), event);
            state.nextSequence++;
            return event;
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public List<RunEvent> readAfter(String runId, long afterSequence) {
        validateId(runId, "runId");
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must not be negative");
        }
        RunState state = stateFor(runId);
        state.lock.lock();
        try {
            return readAndValidate(runId).stream()
                    .filter(event -> event.sequence() > afterSequence)
                    .toList();
        } finally {
            state.lock.unlock();
        }
    }

    private RunState stateFor(String runId) {
        return runStates.computeIfAbsent(runId,
                key -> new RunState(nextSequenceAfterExistingEvents(key)));
    }

    private long nextSequenceAfterExistingEvents(String runId) {
        List<RunEvent> events = readAndValidate(runId);
        return events.isEmpty() ? 1 : events.getLast().sequence() + 1;
    }

    private void appendDurably(Path file, RunEvent event) {
        try {
            Files.createDirectories(file.getParent());
            byte[] json = objectMapper.writeValueAsBytes(event);
            ByteBuffer buffer = ByteBuffer.allocate(json.length + 1);
            buffer.put(json).put((byte) '\n').flip();
            try (FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
        } catch (IOException | RuntimeException exception) {
            throw new RunEventWriteException(
                    "运行事件无法可靠保存，当前任务必须停止。",
                    "runId=" + event.runId() + ", sequence=" + event.sequence() + ", " + exception.getMessage(),
                    exception
            );
        }
    }

    private List<RunEvent> readAndValidate(String runId) {
        Path file = eventFile(runId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<RunEvent> events = new ArrayList<>(lines.size());
            Set<UUID> eventIds = new HashSet<>();
            long expectedSequence = 1;
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank()) {
                    throw invalidJournal(runId, index + 1, "blank event line", null);
                }
                RunEvent event;
                try {
                    event = objectMapper.readValue(line, RunEvent.class);
                } catch (JsonProcessingException exception) {
                    throw invalidJournal(runId, index + 1, "invalid JSON", exception);
                }
                if (!runId.equals(event.runId())) {
                    throw invalidJournal(runId, index + 1, "event belongs to another run", null);
                }
                if (event.sequence() != expectedSequence) {
                    throw invalidJournal(runId, index + 1,
                            "expected sequence " + expectedSequence + " but got " + event.sequence(), null);
                }
                if (!eventIds.add(event.eventId())) {
                    throw invalidJournal(runId, index + 1, "duplicate eventId " + event.eventId(), null);
                }
                events.add(event);
                expectedSequence++;
            }
            return List.copyOf(events);
        } catch (RunEventReadException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new RunEventReadException(
                    "运行事件日志无法读取，无法确认任务历史是否完整。",
                    "runId=" + runId + ", " + exception.getMessage(),
                    exception
            );
        }
    }

    private RunEventReadException invalidJournal(String runId, int line, String reason, Throwable cause) {
        return new RunEventReadException(
                "运行事件日志完整性校验失败，已停止读取。",
                "runId=" + runId + ", line=" + line + ", reason=" + reason,
                cause
        );
    }

    private Path eventFile(String runId) {
        Path runsRoot = runtimeRoot.resolve("runs").normalize();
        Path file = runsRoot.resolve(runId).resolve(EVENTS_FILE).normalize();
        if (!file.startsWith(runsRoot)) {
            throw new IllegalArgumentException("runId resolves outside runtime runs directory");
        }
        return file;
    }

    private void validateId(String value, String field) {
        if (value == null || !value.matches(SAFE_ID_PATTERN)) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
    }

    private static final class RunState {
        private final ReentrantLock lock = new ReentrantLock();
        private long nextSequence;

        private RunState(long nextSequence) {
            this.nextSequence = nextSequence;
        }
    }
}

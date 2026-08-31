package com.codingreview.controlplane.session.persistence;

import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingSessionStatus;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 在平台运行目录中以原子 JSON 快照持久化 Coding Session。 */
public final class FileCodingSessionRepository implements CodingSessionRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileCodingSessionRepository.class);
    private static final int SCHEMA_VERSION = 4;
    private static final String SNAPSHOT_FILE = "session.json";

    private final Path sessionsRoot;
    private final ObjectMapper objectMapper;

    /** 使用平台运行根目录创建文件 Session 仓储。 */
    public FileCodingSessionRepository(Path runtimeRoot, ObjectMapper objectMapper) {
        if (runtimeRoot == null || !runtimeRoot.isAbsolute()) {
            throw new IllegalArgumentException("runtimeRoot must be absolute");
        }
        this.sessionsRoot = runtimeRoot.normalize().resolve("sessions");
        this.objectMapper = objectMapper.copy();
    }

    /** 使用同目录临时文件和原子替换保存最新 Session 快照。 */
    @Override
    public void save(CodingSession session) {
        Path directory = sessionDirectory(session.sessionId());
        Path target = directory.resolve(SNAPSHOT_FILE);
        Path temporary = directory.resolve("session-" + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(directory);
            byte[] json = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(SessionDocument.from(session));
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(json);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            replace(temporary, target);
        } catch (IOException exception) {
            throw new CodingSessionPersistenceException(
                    "无法保存 Coding Session 快照：" + session.sessionId(), exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException exception) {
                LOGGER.warn("无法清理 Session 临时文件，path={}", temporary, exception);
            }
        }
    }

    /** 读取指定 Session 的持久化快照。 */
    @Override
    public Optional<CodingSession> find(UUID sessionId) {
        Path file = sessionDirectory(sessionId).resolve(SNAPSHOT_FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(read(file));
    }

    /** 扫描平台 Session 目录并忽略单个损坏快照。 */
    @Override
    public List<CodingSession> findAll() {
        if (!Files.isDirectory(sessionsRoot)) {
            return List.of();
        }
        List<CodingSession> sessions = new ArrayList<>();
        try (var directories = Files.list(sessionsRoot)) {
            directories.filter(Files::isDirectory).forEach(directory -> {
                Path file = directory.resolve(SNAPSHOT_FILE);
                if (!Files.isRegularFile(file)) {
                    return;
                }
                try {
                    sessions.add(read(file));
                } catch (CodingSessionPersistenceException exception) {
                    LOGGER.error("跳过损坏的 Coding Session 快照，path={}", file, exception);
                }
            });
        } catch (IOException exception) {
            throw new CodingSessionPersistenceException("无法列举 Coding Session 快照。", exception);
        }
        sessions.sort(Comparator.comparing(CodingSession::updatedAt).reversed());
        return List.copyOf(sessions);
    }

    /** 将临时快照替换为正式文件，并兼容不支持原子移动的文件系统。 */
    private void replace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 读取、校验并转换一个版本化 Session 文档。 */
    private CodingSession read(Path file) {
        try {
            SessionDocument document = objectMapper.readValue(file.toFile(), SessionDocument.class);
            if (document.schemaVersion() < 1 || document.schemaVersion() > SCHEMA_VERSION) {
                throw new CodingSessionPersistenceException(
                        "不支持的 Coding Session 快照版本：" + document.schemaVersion());
            }
            return document.toDomain();
        } catch (IOException | IllegalArgumentException exception) {
            throw new CodingSessionPersistenceException("无法读取 Coding Session 快照：" + file, exception);
        }
    }

    /** 返回指定 Session 在运行目录内的安全目录。 */
    private Path sessionDirectory(UUID sessionId) {
        return sessionsRoot.resolve(sessionId.toString()).normalize();
    }

    /** 定义稳定、可版本演进的 Session JSON 文档。 */
    private record SessionDocument(
            int schemaVersion,
            UUID sessionId,
            String title,
            CodingSessionStatus status,
            String sourceRepository,
            String worktreePath,
            String baseRef,
            String resolvedCommit,
            String activeReviewBaselineCommit,
            boolean sourceRepositoryDirty,
            List<String> allowedPaths,
            List<String> deniedPaths,
            String claudeSessionId,
            Instant createdAt,
            Instant updatedAt,
            String failureCode,
            String failureMessage,
            String workspaceMode
    ) {
        /** 将领域对象转换为持久化文档。 */
        private static SessionDocument from(CodingSession session) {
            return new SessionDocument(
                    SCHEMA_VERSION, session.sessionId(), session.title(), session.status(),
                    session.sourceRepository().toString(),
                    session.worktreePath() == null ? null : session.worktreePath().toString(),
                    session.baseRef(), session.resolvedCommit(),
                    session.activeReviewBaselineCommit(), session.sourceRepositoryDirty(),
                    session.changePolicy().allowedPaths(), session.changePolicy().deniedPaths(),
                    session.claudeSessionId(),
                    session.createdAt(), session.updatedAt(),
                    session.failureCode(), session.failureMessage(), "DIRECT"
            );
        }

        /** 将持久化文档恢复为领域对象。 */
        private CodingSession toDomain() {
            CodingSessionStatus restoredStatus = schemaVersion <= 2
                    && (status == CodingSessionStatus.ACTIVE
                    || status == CodingSessionStatus.INITIALIZING)
                    ? CodingSessionStatus.LEGACY_READ_ONLY : status;
            return new CodingSession(
                    sessionId, title, restoredStatus, Path.of(sourceRepository),
                    worktreePath == null ? null : Path.of(worktreePath),
                    baseRef, resolvedCommit,
                    activeReviewBaselineCommit == null
                            ? resolvedCommit : activeReviewBaselineCommit,
                    sourceRepositoryDirty,
                    new ChangePolicy(allowedPaths, deniedPaths),
                    claudeSessionId,
                    createdAt, updatedAt, failureCode, failureMessage
            );
        }
    }
}

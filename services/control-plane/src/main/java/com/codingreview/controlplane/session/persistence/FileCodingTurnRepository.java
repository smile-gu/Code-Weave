package com.codingreview.controlplane.session.persistence;

import com.codingreview.controlplane.session.model.CodingTurn;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 在 Session 目录下以原子 JSON 文件保存每一轮 Coding 状态。 */
public final class FileCodingTurnRepository implements CodingTurnRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileCodingTurnRepository.class);
    private static final int SCHEMA_VERSION = 1;
    private final Path sessionsRoot;
    private final ObjectMapper mapper;

    /** 使用运行根目录创建 Turn 文件仓储。 */
    public FileCodingTurnRepository(Path runtimeRoot, ObjectMapper mapper) {
        if (runtimeRoot == null || !runtimeRoot.isAbsolute()) throw new IllegalArgumentException("runtimeRoot must be absolute");
        this.sessionsRoot = runtimeRoot.normalize().resolve("sessions");
        // 树读取必须保留小数时间戳精度，否则重读终态可能与刚保存的对象不相等。
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    /** 原子保存 Turn 快照，避免服务中断留下半份 JSON。 */
    @Override public void save(CodingTurn turn) {
        Path dir = sessionDir(turn.sessionId()).resolve("turns");
        Path target = dir.resolve(turn.sequence() + "-" + turn.turnId() + ".json");
        Path temp = dir.resolve(target.getFileName() + "-" + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(dir);
            mapper.writerWithDefaultPrettyPrinter().writeValue(
                    temp.toFile(), new TurnDocument(SCHEMA_VERSION, turn)
            );
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            try { Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException ex) { throw failure(target, ex); }
        } catch (IOException e) {
            throw failure(target, e);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException exception) {
                LOGGER.warn("无法清理 Turn 临时快照，path={}", temp, exception);
            }
        }
    }

    /** 查询单轮快照。 */
    @Override public Optional<CodingTurn> find(UUID sessionId, UUID turnId) {
        Path dir = sessionDir(sessionId).resolve("turns");
        if (!Files.isDirectory(dir)) return Optional.empty();
        String suffix = "-" + turnId + ".json";
        try (var files = Files.list(dir)) {
            List<Path> matches = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .toList();
            if (matches.size() > 1) {
                throw historyFailure(sessionId, dir,
                        CodingTurnHistoryFailureKind.DATA_CORRUPTED,
                        "同一个 Coding Turn 存在多个快照文件。", null);
            }
            return matches.isEmpty() ? Optional.empty()
                    : Optional.of(read(sessionId, matches.getFirst()));
        } catch (CodingTurnHistoryException exception) {
            throw exception;
        } catch (IOException exception) {
            throw historyFailure(sessionId, dir, CodingTurnHistoryFailureKind.IO_FAILURE,
                    "无法查询 Coding Turn 快照。", exception);
        }
    }

    /** 扫描 Session 下所有 Turn，并按序号排序。 */
    @Override public List<CodingTurn> findAll(UUID sessionId) {
        Path dir = sessionDir(sessionId).resolve("turns");
        if (!Files.isDirectory(dir)) return List.of();
        try (var files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".json"))
                    .map(path -> read(sessionId, path))
                    .sorted(Comparator.comparingInt(CodingTurn::sequence)).toList();
        } catch (IOException e) {
            throw historyFailure(sessionId, dir, CodingTurnHistoryFailureKind.IO_FAILURE,
                    "无法列举 Coding Turn 快照。", e);
        }
    }

    /** 从文件恢复 Turn 快照，并校验记录身份确实属于目标 Session。 */
    private CodingTurn read(UUID expectedSessionId, Path path) {
        try {
            var tree = mapper.readTree(path.toFile());
            int schemaVersion = tree.has("schemaVersion")
                    ? tree.path("schemaVersion").asInt(-1) : 0;
            if (schemaVersion < 0 || schemaVersion > SCHEMA_VERSION) {
                throw historyFailure(expectedSessionId, path,
                        CodingTurnHistoryFailureKind.VERSION_UNSUPPORTED,
                        "不支持的 Coding Turn 快照版本：" + schemaVersion, null);
            }
            CodingTurn turn = schemaVersion == 0
                    ? mapper.treeToValue(tree, CodingTurn.class)
                    : mapper.treeToValue(tree, TurnDocument.class).turn();
            String expectedFileName = turn == null ? null
                    : turn.sequence() + "-" + turn.turnId() + ".json";
            if (turn == null || !expectedSessionId.equals(turn.sessionId())
                    || !path.getFileName().toString().equals(expectedFileName)) {
                throw historyFailure(expectedSessionId, path,
                        CodingTurnHistoryFailureKind.DATA_CORRUPTED,
                        "Coding Turn 快照身份与所属 Session 或文件名不一致。", null);
            }
            return turn;
        } catch (CodingTurnHistoryException exception) {
            throw exception;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw historyFailure(expectedSessionId, path,
                    CodingTurnHistoryFailureKind.DATA_CORRUPTED,
                    "Coding Turn 快照内容损坏：" + path, exception);
        } catch (IOException exception) {
            throw historyFailure(expectedSessionId, path,
                    CodingTurnHistoryFailureKind.IO_FAILURE,
                    "暂时无法读取 Coding Turn 快照：" + path, exception);
        }
    }

    /** 构造保留 Session、文件和诊断类别的历史异常。 */
    private CodingTurnHistoryException historyFailure(
            UUID sessionId,
            Path path,
            CodingTurnHistoryFailureKind kind,
            String message,
            Throwable cause
    ) {
        return new CodingTurnHistoryException(sessionId, path, kind, message, cause);
    }

    /** 返回 Session 的受控目录。 */
    private Path sessionDir(UUID id) { return sessionsRoot.resolve(id.toString()).normalize(); }

    /** 统一包装持久化失败。 */
    private CodingSessionPersistenceException failure(Path path, Exception e) { return new CodingSessionPersistenceException("无法保存 Coding Turn 快照：" + path, e); }

    /** 定义可独立演进的 Turn 快照文档。 */
    private record TurnDocument(int schemaVersion, CodingTurn turn) { }
}

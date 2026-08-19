package com.codingreview.controlplane.session.persistence;

import com.codingreview.controlplane.session.model.CodingTurn;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        this.mapper = mapper.copy();
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
        return findAll(sessionId).stream().filter(turn -> turn.turnId().equals(turnId)).findFirst();
    }

    /** 扫描 Session 下所有 Turn，并按序号排序。 */
    @Override public List<CodingTurn> findAll(UUID sessionId) {
        Path dir = sessionDir(sessionId).resolve("turns");
        if (!Files.isDirectory(dir)) return List.of();
        try (var files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".json"))
                    .map(this::read).sorted(Comparator.comparingInt(CodingTurn::sequence)).toList();
        } catch (IOException e) { throw new CodingSessionPersistenceException("无法读取 Coding Turn 快照。", e); }
    }

    /** 从文件恢复 Turn 快照。 */
    private CodingTurn read(Path path) {
        try {
            var tree = mapper.readTree(path.toFile());
            if (!tree.has("schemaVersion")) {
                return mapper.treeToValue(tree, CodingTurn.class);
            }
            TurnDocument document = mapper.treeToValue(tree, TurnDocument.class);
            if (document.schemaVersion() != SCHEMA_VERSION) {
                throw new CodingSessionPersistenceException(
                        "不支持的 Coding Turn 快照版本：" + document.schemaVersion()
                );
            }
            return document.turn();
        }
        catch (IOException | IllegalArgumentException e) { throw new CodingSessionPersistenceException("无法读取 Coding Turn 快照：" + path, e); }
    }

    /** 返回 Session 的受控目录。 */
    private Path sessionDir(UUID id) { return sessionsRoot.resolve(id.toString()).normalize(); }

    /** 统一包装持久化失败。 */
    private CodingSessionPersistenceException failure(Path path, Exception e) { return new CodingSessionPersistenceException("无法保存 Coding Turn 快照：" + path, e); }

    /** 定义可独立演进的 Turn 快照文档。 */
    private record TurnDocument(int schemaVersion, CodingTurn turn) { }
}

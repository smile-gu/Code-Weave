package com.codingreview.controlplane.session.integration.persistence;

import com.codingreview.controlplane.session.integration.model.CodingSessionIntegration;
import com.codingreview.controlplane.session.persistence.CodingSessionPersistenceException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 在 Session 目录中以原子 JSON 快照保存合并事实。 */
public final class FileCodingSessionIntegrationRepository
        implements CodingSessionIntegrationRepository {
    private static final String FILE_NAME = "integration.json";

    private final Path sessionsRoot;
    private final ObjectMapper mapper;

    /** 使用平台运行根目录创建文件仓储。 */
    public FileCodingSessionIntegrationRepository(Path runtimeRoot, ObjectMapper mapper) {
        if (runtimeRoot == null || !runtimeRoot.isAbsolute()) {
            throw new IllegalArgumentException("runtimeRoot must be absolute");
        }
        this.sessionsRoot = runtimeRoot.normalize().resolve("sessions");
        this.mapper = mapper.copy();
    }

    /** 通过同目录临时文件和原子替换保存状态。 */
    @Override
    public void save(CodingSessionIntegration integration) {
        Path directory = sessionDirectory(integration.sessionId());
        Path target = directory.resolve(FILE_NAME);
        Path temporary = directory.resolve(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(directory);
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), integration);
            replace(temporary, target);
        } catch (IOException exception) {
            throw new CodingSessionPersistenceException(
                    "无法保存 Session 合并状态：" + integration.sessionId(), exception
            );
        }
    }

    /** 读取指定 Session 的合并状态。 */
    @Override
    public Optional<CodingSessionIntegration> find(UUID sessionId) {
        Path file = sessionDirectory(sessionId).resolve(FILE_NAME);
        return Files.isRegularFile(file) ? Optional.of(read(file)) : Optional.empty();
    }

    /** 扫描所有 Session 目录下的合并状态。 */
    @Override
    public List<CodingSessionIntegration> findAll() {
        if (!Files.isDirectory(sessionsRoot)) {
            return List.of();
        }
        try (var directories = Files.list(sessionsRoot)) {
            return directories.filter(Files::isDirectory)
                    .map(directory -> directory.resolve(FILE_NAME))
                    .filter(Files::isRegularFile)
                    .map(this::read)
                    .toList();
        } catch (IOException exception) {
            throw new CodingSessionPersistenceException(
                    "无法扫描 Session 合并状态。", exception
            );
        }
    }

    /** 读取单个合并快照。 */
    private CodingSessionIntegration read(Path file) {
        try {
            return mapper.readValue(file.toFile(), CodingSessionIntegration.class);
        } catch (IOException | IllegalArgumentException exception) {
            throw new CodingSessionPersistenceException(
                    "无法读取 Session 合并状态：" + file, exception
            );
        }
    }

    /** 优先原子替换，并兼容不支持原子移动的文件系统。 */
    private void replace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 返回指定 Session 的持久目录。 */
    private Path sessionDirectory(UUID sessionId) {
        return sessionsRoot.resolve(sessionId.toString()).normalize();
    }
}

package com.codingreview.controlplane.workspace.lock;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 使用运行目录中的锁文件防止同一仓库被多个 Coding Session 同时修改。 */
public final class FileRepositoryWriteLockManager implements RepositoryWriteLockManager {
    private final Path locksRoot;
    private final ConcurrentHashMap<UUID, RepositoryWriteLock> heldLocks = new ConcurrentHashMap<>();

    public FileRepositoryWriteLockManager(Path runtimeRoot) {
        Objects.requireNonNull(runtimeRoot, "runtimeRoot");
        if (!runtimeRoot.isAbsolute()) throw new IllegalArgumentException("runtimeRoot must be absolute");
        this.locksRoot = runtimeRoot.normalize().resolve("repository-locks");
    }

    /** 创建锁文件、写入持有者信息并尝试获取独占文件锁。 */
    @Override
    public synchronized void acquire(Path repository, UUID sessionId) {
        acquire(repository, sessionId, false);
    }

    /** 仅允许原 Session 在服务重启后重新接管持久锁。 */
    @Override
    public synchronized void recover(Path repository, UUID sessionId) {
        acquire(repository, sessionId, true);
    }

    /** 获取新锁或恢复同一 Session 的既有锁。 */
    private void acquire(Path repository, UUID sessionId, boolean recovery) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(sessionId, "sessionId");
        Path normalized = repository.toAbsolutePath().normalize();
        RepositoryWriteLock existing = heldLocks.get(sessionId);
        if (existing != null) {
            if (!existing.repository().equals(normalized)) throw conflict(normalized);
            return;
        }
        Path lockFile = locksRoot.resolve(key(normalized) + ".lock");
        try {
            Files.createDirectories(locksRoot);
            FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException exception) {
                channel.close();
                throw conflict(normalized);
            }
            if (lock == null) {
                channel.close();
                throw conflict(normalized);
            }
            String persistedOwner = readOwner(channel);
            boolean ownerMatches = persistedOwner.equals(sessionId.toString());
            if ((!persistedOwner.isBlank() && !recovery) || (recovery && !persistedOwner.isBlank() && !ownerMatches)) {
                lock.release();
                channel.close();
                throw conflict(normalized);
            }
            byte[] owner = ("sessionId=" + sessionId + "\nrepository=" + normalized + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            channel.truncate(0);
            channel.position(0);
            channel.write(java.nio.ByteBuffer.wrap(owner));
            channel.force(true);
            heldLocks.put(sessionId, new RepositoryWriteLock(normalized, channel, lock));
        } catch (IOException exception) {
            throw new RepositoryWriteLockException("REPOSITORY_LOCK_FAILED", "无法申请仓库写锁。", exception.getMessage(), exception);
        }
    }

    /** 通过当前独占通道读取持久化 Session 所有者。 */
    private String readOwner(FileChannel channel) throws IOException {
        channel.position(0);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(4096);
        channel.read(buffer);
        buffer.flip();
        String content = StandardCharsets.UTF_8.decode(buffer).toString();
        for (String line : content.split("\\R")) {
            if (line.startsWith("sessionId=")) return line.substring("sessionId=".length()).trim();
        }
        return "";
    }

    /** 清空持久所有者信息并释放当前进程持有的文件锁。 */
    @Override
    public synchronized void release(Path repository, UUID sessionId) {
        Path normalized = repository.toAbsolutePath().normalize();
        RepositoryWriteLock handle = heldLocks.remove(sessionId);
        if (handle == null) return;
        if (!handle.repository().equals(normalized)) {
            heldLocks.put(sessionId, handle);
            throw new IllegalArgumentException("repository does not belong to session lock");
        }
        Path lockFile = locksRoot.resolve(key(normalized) + ".lock");
        try {
            handle.clearOwnership();
        } catch (RuntimeException exception) {
            heldLocks.put(sessionId, handle);
            throw new RepositoryWriteLockException("REPOSITORY_UNLOCK_FAILED", "无法释放仓库写锁。", exception.getMessage(), exception);
        }
        handle.close();
        try {
            Files.deleteIfExists(lockFile);
        } catch (IOException ignored) {
            // 空锁文件不会保留 Session 所有权，可在后续维护时清理。
        }
    }

    /** 服务停止时释放操作系统文件锁，锁文件由启动恢复流程重新接管。 */
    @Override
    public synchronized void close() {
        RuntimeException failure = null;
        for (RepositoryWriteLock handle : heldLocks.values()) {
            try {
                handle.close();
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        heldLocks.clear();
        if (failure != null) throw failure;
    }

    /** 通过仓库绝对路径生成稳定且安全的锁文件名。 */
    private String key(Path repository) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(repository.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256。", exception);
        }
    }

    private RepositoryWriteLockException conflict(Path repository) {
        return new RepositoryWriteLockException("REPOSITORY_BUSY", "该 Git 仓库已有活跃 Coding Session，请先结束已有会话。", repository.toString());
    }

}

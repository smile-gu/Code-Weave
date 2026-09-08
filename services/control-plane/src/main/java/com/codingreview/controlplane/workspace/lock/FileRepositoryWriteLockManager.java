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
            boolean retained = false;
            try {
                String persistedOwner = readOwner(channel, normalized);
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
                java.nio.ByteBuffer ownerBuffer = java.nio.ByteBuffer.wrap(owner);
                while (ownerBuffer.hasRemaining()) channel.write(ownerBuffer);
                channel.force(true);
                heldLocks.put(sessionId, new RepositoryWriteLock(normalized, channel, lock));
                retained = true;
            } finally {
                if (!retained) channel.close();
            }
        } catch (IOException exception) {
            throw new RepositoryWriteLockException("REPOSITORY_LOCK_FAILED", "无法申请仓库写锁。", exception.getMessage(), exception);
        }
    }

    /** 通过当前独占通道读取持久化 Session 所有者。 */
    private String readOwner(FileChannel channel, Path repository) throws IOException {
        if (channel.size() == 0) return "";
        if (channel.size() > 4096) throw new IOException("仓库锁元数据过大");
        channel.position(0);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate((int) channel.size());
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw new IOException("仓库锁元数据读取不完整");
        }
        buffer.flip();
        String content = StandardCharsets.UTF_8.newDecoder().decode(buffer).toString();
        String[] lines = content.split("\\n", -1);
        if (lines.length != 3 || !lines[0].startsWith("sessionId=")
                || !lines[1].equals("repository=" + repository) || !lines[2].isEmpty())
            throw new IOException("仓库锁元数据损坏");
        String owner = lines[0].substring("sessionId=".length());
        try {
            if (!UUID.fromString(owner).toString().equals(owner)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new IOException("仓库锁所有者损坏", exception);
        }
        return owner;
    }

    /** 在独占保护下按预期所有者释放，保留稳定锁文件并允许重启补偿。 */
    @Override
    public synchronized void release(Path repository, UUID sessionId) {
        Path normalized = repository.toAbsolutePath().normalize();
        RepositoryWriteLock handle = heldLocks.get(sessionId);
        if (handle != null && !handle.repository().equals(normalized))
            throw new IllegalArgumentException("repository does not belong to session lock");
        try {
            if (handle != null) {
                if (handle.isOpen()) {
                    if (handle.isLocked()) {
                        String owner = readOwner(handle.channel(), normalized);
                        if (!owner.isEmpty() && !owner.equals(sessionId.toString()))
                            throw new IOException("本地句柄与持久所有者不一致，保留保护");
                        if (owner.equals(sessionId.toString())) handle.clearOwnership();
                        handle.close();
                        heldLocks.remove(sessionId, handle);
                        return;
                    }
                    handle.close();
                }
                heldLocks.remove(sessionId, handle);
            }
            // 本进程已可靠持有其他 Session 的同仓库独占锁，不触碰其所有权。
            for (var entry : heldLocks.entrySet()) {
                RepositoryWriteLock other = entry.getValue();
                if (other.repository().equals(normalized) && other.isLocked()) {
                    String owner = readOwner(other.channel(), normalized);
                    if (owner.equals(entry.getKey().toString())) return;
                    throw new IOException("其他本地句柄与持久所有者不一致，释放无法确认");
                }
            }
            Files.createDirectories(locksRoot);
            Path file = locksRoot.resolve(key(normalized) + ".lock");
            try (FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                FileLock lock;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException exception) {
                    throw new IOException("仓库锁仍被占用，无法确认释放", exception);
                }
                if (lock == null) throw new IOException("仓库锁仍被占用，无法确认释放");
                try (lock) {
                    if (readOwner(channel, normalized).equals(sessionId.toString())) {
                        channel.truncate(0);
                        channel.force(true);
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            throw new RepositoryWriteLockException("REPOSITORY_UNLOCK_FAILED",
                    "仓库所有权释放尚未确认。", exception.getMessage(), exception);
        }
    }

    /** 服务停止时释放操作系统文件锁，锁文件由启动恢复流程重新接管。 */
    @Override
    public synchronized void close() {
        RuntimeException failure = null;
        for (var entry : heldLocks.entrySet()) {
            try {
                entry.getValue().close();
                heldLocks.remove(entry.getKey(), entry.getValue());
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
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

package com.codingreview.controlplane.workspace.lock;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;

/** 一个真实 Git 仓库的进程级写锁句柄。 */
final class RepositoryWriteLock {
    private final Path repository;
    private final FileChannel channel;
    private final FileLock lock;

    RepositoryWriteLock(Path repository, FileChannel channel, FileLock lock) {
        this.repository = repository;
        this.channel = channel;
        this.lock = lock;
    }

    Path repository() { return repository; }

    /** 返回受本句柄独占锁保护的元数据通道。 */
    FileChannel channel() { return channel; }

    /** 判断持有通道是否仍需释放，以便关闭失败后安全重试。 */
    boolean isOpen() { return channel.isOpen(); }

    /** 判断句柄仍持有独占锁，避免失败重试在无锁状态下修改所有权。 */
    boolean isLocked() { return lock.isValid(); }

    /** 在仍持有独占锁时清空持久 Session 所有者。 */
    void clearOwnership() {
        try {
            if (!lock.isValid()) throw new IOException("仓库独占锁已失效");
            channel.truncate(0);
            channel.force(true);
        } catch (IOException exception) {
            throw new IllegalStateException("清空仓库写锁所有者失败。", exception);
        }
    }

    /** 释放文件锁并关闭锁文件。 */
    void close() {
        try {
            if (lock.isValid()) lock.release();
        } catch (IOException exception) {
            throw new IllegalStateException("释放仓库写锁失败。", exception);
        } finally {
            try {
                channel.close();
            } catch (IOException exception) {
                throw new IllegalStateException("关闭仓库写锁文件失败。", exception);
            }
        }
    }
}

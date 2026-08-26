package com.codingreview.controlplane.workspace.lock;

import java.nio.file.Path;
import java.util.UUID;

/** 为真实 Git 仓库提供跨 Control Plane 实例的持久文件锁。 */
public interface RepositoryWriteLockManager extends AutoCloseable {

    /** 尝试获取仓库写锁，已被其他 Session 占用时抛出冲突。 */
    void acquire(Path repository, UUID sessionId);

    /** 服务重启时由同一 Session 重新接管其持久仓库锁。 */
    void recover(Path repository, UUID sessionId);

    /** 仅由锁持有 Session 释放仓库所有权。 */
    void release(Path repository, UUID sessionId);

    /** 关闭当前进程持有的文件句柄，但保留持久 Session 所有权。 */
    @Override
    void close();
}

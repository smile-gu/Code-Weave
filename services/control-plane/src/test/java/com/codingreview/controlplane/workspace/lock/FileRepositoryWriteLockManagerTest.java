package com.codingreview.controlplane.workspace.lock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证同一真实仓库只能被一个 Coding Session 持有。 */
class FileRepositoryWriteLockManagerTest {
    @TempDir Path tempDir;

    @Test
    void rejectsSecondSessionAndAllowsAcquireAfterRelease() throws Exception {
        Path runtime = tempDir.resolve("runtime").toAbsolutePath();
        Path repository = Files.createDirectories(tempDir.resolve("repository")).toRealPath();
        UUID firstSession = UUID.randomUUID();
        UUID secondSession = UUID.randomUUID();
        FileRepositoryWriteLockManager first = new FileRepositoryWriteLockManager(runtime);
        FileRepositoryWriteLockManager second = new FileRepositoryWriteLockManager(runtime);

        first.acquire(repository, firstSession);
        assertThatThrownBy(() -> second.acquire(repository, secondSession))
                .isInstanceOf(RepositoryWriteLockException.class)
                .satisfies(error -> assertThat(((RepositoryWriteLockException) error).code())
                        .isEqualTo("REPOSITORY_BUSY"));

        first.close();
        assertThatThrownBy(() -> second.acquire(repository, secondSession))
                .isInstanceOf(RepositoryWriteLockException.class);

        FileRepositoryWriteLockManager recovered = new FileRepositoryWriteLockManager(runtime);
        recovered.recover(repository, firstSession);
        Path lockFile;
        try (var files = Files.list(runtime.resolve("repository-locks"))) {
            lockFile = files.findFirst().orElseThrow();
        }
        recovered.close();
        assertThat(Files.readString(lockFile)).contains("sessionId=" + firstSession);

        FileRepositoryWriteLockManager cleanup = new FileRepositoryWriteLockManager(runtime);
        cleanup.recover(repository, firstSession);
        cleanup.release(repository, firstSession);
        second.acquire(repository, secondSession);
        second.release(repository, secondSession);
    }

    @Test
    void compensatesWithoutLocalHandleAndPreservesStableFileAcrossTwoRestarts() throws Exception {
        Path runtime = tempDir.resolve("runtime");
        Path repository = tempDir.resolve("repository");
        UUID old = UUID.randomUUID();
        var first = new FileRepositoryWriteLockManager(runtime);
        first.acquire(repository, old);
        first.close();
        Path file;
        try (var files = Files.list(runtime.resolve("repository-locks"))) {
            file = files.findFirst().orElseThrow();
        }
        var second = new FileRepositoryWriteLockManager(runtime);
        second.release(repository, old);
        assertThat(file).exists();
        assertThat(Files.readString(file)).isEmpty();
        second.close();
        var third = new FileRepositoryWriteLockManager(runtime);
        third.release(repository, old);
        assertThat(Files.readString(file)).isEmpty();
        UUID next = UUID.randomUUID();
        third.acquire(repository, next);
        third.release(repository, old);
        assertThatThrownBy(() -> third.acquire(repository, UUID.randomUUID()))
                .isInstanceOf(RepositoryWriteLockException.class);
        third.close();
        assertThat(Files.readString(file)).contains(next.toString());
        third.release(repository, next);
    }

    @Test
    void neverClearsDifferentPersistedOwner() throws Exception {
        Path repository = tempDir.resolve("repo");
        UUID next = UUID.randomUUID();
        var first = new FileRepositoryWriteLockManager(tempDir);
        first.acquire(repository, next);
        first.close();
        var second = new FileRepositoryWriteLockManager(tempDir);
        second.release(repository, UUID.randomUUID());
        second.recover(repository, next);
        second.release(repository, next);
    }

    @Test
    void cannotCompensateWhileAnotherManagerHoldsTargetLock() {
        Path repository = tempDir.resolve("repo");
        UUID owner = UUID.randomUUID();
        var first = new FileRepositoryWriteLockManager(tempDir);
        var second = new FileRepositoryWriteLockManager(tempDir);
        first.acquire(repository, owner);
        assertThatThrownBy(() -> second.release(repository, owner))
                .isInstanceOf(RepositoryWriteLockException.class);
        first.release(repository, owner);
        second.release(repository, owner);
    }

    @Test
    void corruptMetadataIsPreservedAndDoesNotLeakFileLock() throws Exception {
        Path repository = tempDir.resolve("repo");
        UUID owner = UUID.randomUUID();
        var manager = new FileRepositoryWriteLockManager(tempDir);
        manager.acquire(repository, owner);
        manager.close();
        Path file;
        try (var files = Files.list(tempDir.resolve("repository-locks"))) {
            file = files.findFirst().orElseThrow();
        }
        Files.writeString(file, "broken");
        assertThatThrownBy(() -> manager.release(repository, owner)).isInstanceOf(RepositoryWriteLockException.class);
        assertThatThrownBy(() -> manager.acquire(repository, UUID.randomUUID())).isInstanceOf(RepositoryWriteLockException.class);
        assertThat(Files.readString(file)).isEqualTo("broken");
        try (var channel = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            assertThat(lock).isNotNull();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedHandleCloseRemainsTrackedUntilSuccessfulRetry() throws Exception {
        var manager = new FileRepositoryWriteLockManager(tempDir);
        var handle = org.mockito.Mockito.mock(RepositoryWriteLock.class);
        var channel = org.mockito.Mockito.mock(java.nio.channels.FileChannel.class);
        Path repository = tempDir.resolve("repo");
        UUID owner = UUID.randomUUID();
        org.mockito.Mockito.when(handle.repository()).thenReturn(repository);
        org.mockito.Mockito.when(handle.isOpen()).thenReturn(true);
        org.mockito.Mockito.when(handle.isLocked()).thenReturn(true);
        org.mockito.Mockito.when(handle.channel()).thenReturn(channel);
        org.mockito.Mockito.doThrow(new IllegalStateException("close failed")).doNothing().when(handle).close();
        var field = FileRepositoryWriteLockManager.class.getDeclaredField("heldLocks");
        field.setAccessible(true);
        var held = (java.util.Map<UUID, RepositoryWriteLock>) field.get(manager);
        held.put(owner, handle);
        assertThatThrownBy(() -> manager.release(repository, owner)).isInstanceOf(RepositoryWriteLockException.class);
        assertThat(held).containsKey(owner);
        manager.release(repository, owner);
        assertThat(held).doesNotContainKey(owner);
        org.mockito.Mockito.verify(handle, org.mockito.Mockito.times(2)).close();
    }
}

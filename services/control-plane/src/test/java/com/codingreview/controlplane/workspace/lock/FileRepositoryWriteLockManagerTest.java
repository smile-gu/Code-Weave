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
}

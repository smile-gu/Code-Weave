package com.codingreview.controlplane.review.snapshot;

import com.codingreview.controlplane.review.ReviewJson;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.*;

/** 可移植相对路径及 NOFOLLOW/junction 检查，不根据目录内容选择删除目标。 */
public final class ReviewPaths {
    private ReviewPaths() {}

    public static String portable(String value) {
        if (value == null || value.isEmpty()) throw new SnapshotException("SNAPSHOT_PATH_INVALID");
        ReviewJson.unicode(value);
        for (String part : value.split("/", -1)) {
            if (part.isEmpty()
                    || part.equals(".")
                    || part.equals("..")
                    || part.equalsIgnoreCase(".git")
                    || part.matches(".*[\\\\:\\x00-\\x1f\\x7f<>\"|?*].*")
                    || part.endsWith(" ")
                    || part.endsWith(".")
                    || part.toUpperCase(Locale.ROOT)
                            .matches("(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(\\..*)?"))
                throw new SnapshotException("SNAPSHOT_PATH_INVALID");
        }
        return value;
    }

    public static void distinct(Collection<String> paths) {
        Set<String> keys = normalizedKeys(paths);
        for (String key : keys) {
            for (int i = key.indexOf('/'); i >= 0; i = key.indexOf('/', i + 1))
                if (keys.contains(key.substring(0, i)))
                    throw new SnapshotException("SNAPSHOT_PATH_COLLISION");
        }
    }

    /** Historical changes must be unique but need not coexist on disk. */
    public static void unique(Collection<String> paths) {
        normalizedKeys(paths);
    }

    private static Set<String> normalizedKeys(Collection<String> paths) {
        Set<String> keys = new HashSet<>();
        for (String path : paths) {
            portable(path);
            String key = Normalizer.normalize(path, Normalizer.Form.NFC).toUpperCase(Locale.ROOT);
            if (!keys.add(key)) throw new SnapshotException("SNAPSHOT_PATH_COLLISION");
        }
        return keys;
    }

    public static Path directory(Path path) throws IOException {
        if (!path.isAbsolute()) throw new SnapshotException("SNAPSHOT_PATH_INVALID");
        Path current = path.toAbsolutePath().normalize().getRoot();
        for (Path part : path.toAbsolutePath().normalize()) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes a =
                        Files.readAttributes(
                                current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!a.isDirectory()
                        || a.isSymbolicLink()
                        || a.isOther()
                        || !current.toRealPath().equals(current))
                    throw new SnapshotException("SNAPSHOT_PATH_INVALID");
            }
        }
        return path.toAbsolutePath().normalize();
    }

    public static Path file(Path root, String relative, boolean allowLeafLink) throws IOException {
        Path path = directory(root).resolve(portable(relative));
        directory(path.getParent());
        var a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!(a.isRegularFile() || (allowLeafLink && a.isSymbolicLink())) || a.isOther())
            throw new SnapshotException("SNAPSHOT_SPECIAL_FILE_UNSUPPORTED");
        if (!allowLeafLink && a.isSymbolicLink())
            throw new SnapshotException("SNAPSHOT_PATH_INVALID");
        return path;
    }

    public static void separate(Path a, Path b) {
        if (a.startsWith(b) || b.startsWith(a))
            throw new SnapshotException("SNAPSHOT_PATH_INVALID");
    }

    public static void removeOwnedTree(Path root, Path path) throws IOException {
        removeOwnedTree(
                root,
                path,
                5_120_000,
                System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos());
    }

    public static void removeOwnedTree(Path root, Path path, int maxMembers, long deadline)
            throws IOException {
        root = directory(root);
        path = directory(path);
        if (path.equals(root) || !path.startsWith(root))
            throw new SnapshotException("SNAPSHOT_PATH_INVALID");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        List<Path> members = new ArrayList<>();
        try (var walk = Files.walk(path)) {
            var iterator = walk.iterator();
            while (iterator.hasNext()) {
                if (members.size() >= maxMembers || System.nanoTime() >= deadline)
                    throw new SnapshotException("SNAPSHOT_LIMIT_EXCEEDED", false);
                members.add(iterator.next());
            }
        }
        for (Path member : members) {
            var a =
                    Files.readAttributes(
                            member, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (a.isDirectory()) directory(member);
            else if (!a.isRegularFile() || a.isSymbolicLink() || a.isOther())
                throw new SnapshotException("SNAPSHOT_PATH_INVALID");
        }
        for (Path member : members.reversed()) {
            if (System.nanoTime() >= deadline)
                throw new SnapshotException("SNAPSHOT_LIMIT_EXCEEDED", false);
            var attributes =
                    Files.readAttributes(
                            member, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isDirectory()) directory(member);
            else
                file(
                        path,
                        path.relativize(member).toString().replace(java.io.File.separatorChar, '/'),
                        false);
            Files.delete(member);
        }
    }
}

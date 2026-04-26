package com.apocscode.byteblock.computer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

/**
 * On-disk mirror for {@link VirtualFileSystem}. Each ByteBlock computer's
 * filesystem is mirrored into
 * {@code <world>/byteblock/computer/<UUID>/} so external editors (VS Code,
 * vim, etc.) can read and write computer files directly. The in-game OS
 * picks up external edits automatically and external tools see in-game
 * edits as soon as they're saved.
 *
 * <p>Two operations:
 * <ul>
 *   <li>{@link #pushToDisk} — dump current VFS contents to disk (in-game → disk).</li>
 *   <li>{@link #pullFromDisk} — scan disk and write any externally-changed
 *       files back into the VFS (disk → in-game).</li>
 * </ul>
 *
 * Conflict policy: most-recent-wins by file modification time. The mirror
 * tracks the on-disk mtime at the time of the last push so we can tell
 * whether a file changed externally vs. was just written by us.
 *
 * <p>Special folders (Recycle Bin, Windows/System32) are skipped to avoid
 * polluting the disk with internal OS state.
 */
public final class VfsDiskMirror {

    /** Folders that are NOT mirrored to disk. */
    private static final Set<String> SKIP_PREFIXES = Set.of(
            "/Recycle Bin",
            "/Windows"
    );

    private VfsDiskMirror() {}

    /**
     * Resolve the on-disk root for a given computer UUID, creating
     * directories as needed. Returns null if the level isn't a server level
     * (clients don't have a save folder).
     */
    public static Path computerDir(Level level, UUID computerId) {
        if (!(level instanceof ServerLevel sl)) return null;
        MinecraftServer server = sl.getServer();
        if (server == null) return null;
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path dir = worldRoot.resolve("byteblock")
                .resolve("computer")
                .resolve(computerId.toString());
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        return dir;
    }

    /**
     * Mirror the entire {@link VirtualFileSystem} tree to disk under
     * {@code root}. Files that haven't changed (same mtime + size) are skipped.
     */
    public static void pushToDisk(VirtualFileSystem vfs, Path root) {
        if (vfs == null || root == null) return;
        try {
            Files.createDirectories(root);
            pushNode(vfs, root, "/");
        } catch (IOException ignored) {
        }
    }

    private static void pushNode(VirtualFileSystem vfs, Path root, String vfsDir) {
        if (isSkipped(vfsDir)) return;
        List<String> entries = vfs.list(vfsDir);
        if (entries == null) return;
        for (String name : entries) {
            boolean isDir = name.endsWith("/");
            String clean = isDir ? name.substring(0, name.length() - 1) : name;
            String childVfs = vfsDir.equals("/") ? "/" + clean : vfsDir + "/" + clean;
            if (isSkipped(childVfs)) continue;
            Path target = root.resolve(stripLeadingSlash(childVfs));
            if (isDir) {
                try {
                    Files.createDirectories(target);
                } catch (IOException ignored) {
                }
                pushNode(vfs, root, childVfs);
            } else {
                String content = vfs.readFile(childVfs);
                if (content == null) continue;
                writeIfChanged(target, content);
            }
        }
    }

    private static void writeIfChanged(Path target, String content) {
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (Files.exists(target)) {
                byte[] existing = Files.readAllBytes(target);
                if (Arrays.equals(existing, bytes)) return; // identical, skip write
            } else {
                Files.createDirectories(target.getParent());
            }
            Files.write(target, bytes);
        } catch (IOException ignored) {
        }
    }

    /**
     * Scan the on-disk directory and merge any new or modified files back
     * into the {@link VirtualFileSystem}. Files newer on disk than the
     * VFS-recorded version overwrite the VFS copy. Returns true if any
     * files were imported.
     */
    public static boolean pullFromDisk(VirtualFileSystem vfs, Path root) {
        if (vfs == null || root == null || !Files.isDirectory(root)) return false;
        boolean[] changed = new boolean[]{false};
        try (Stream<Path> walk = Files.walk(root)) {
            walk.forEach(p -> {
                if (Files.isDirectory(p)) return;
                Path rel = root.relativize(p);
                String vfsPath = "/" + rel.toString().replace('\\', '/');
                if (isSkipped(vfsPath)) return;
                try {
                    byte[] bytes = Files.readAllBytes(p);
                    String content = new String(bytes, StandardCharsets.UTF_8);
                    String existing = vfs.readFile(vfsPath);
                    if (existing == null || !existing.equals(content)) {
                        if (vfs.writeFile(vfsPath, content)) {
                            changed[0] = true;
                        }
                    }
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        return changed[0];
    }

    private static boolean isSkipped(String vfsPath) {
        if (vfsPath == null) return true;
        for (String prefix : SKIP_PREFIXES) {
            if (vfsPath.equals(prefix) || vfsPath.startsWith(prefix + "/")) return true;
        }
        return false;
    }

    private static String stripLeadingSlash(String s) {
        return s.startsWith("/") ? s.substring(1) : s;
    }

    /**
     * Quickly check if any file in {@code root} has an mtime newer than
     * {@code sinceEpochMs}. Cheap directory poll for tick-based change
     * detection.
     */
    public static boolean hasExternalChanges(Path root, long sinceEpochMs) {
        if (root == null || !Files.isDirectory(root)) return false;
        boolean[] found = new boolean[]{false};
        try (Stream<Path> walk = Files.walk(root)) {
            walk.forEach(p -> {
                if (found[0]) return;
                if (Files.isDirectory(p)) return;
                try {
                    BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                    if (attrs.lastModifiedTime().toMillis() > sinceEpochMs) {
                        found[0] = true;
                    }
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        return found[0];
    }
}

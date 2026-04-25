package com.apocscode.byteblock.computer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tree-based virtual file system for ByteBlock computers.
 * Every node is either a file (has content) or a directory (has children).
 * Serializes to/from NBT CompoundTag for persistence.
 */
public class VirtualFileSystem {

    private final FSNode root;
    private boolean dirty;

    // Windows-style path constants
    public static final String DESKTOP    = "/Users/User/Desktop";
    public static final String DOCUMENTS  = "/Users/User/Documents";
    public static final String DOWNLOADS  = "/Users/User/Downloads";
    public static final String PICTURES   = "/Users/User/Pictures";
    public static final String PROGRAMS   = "/Program Files";
    public static final String SYSTEM     = "/Windows";
    public static final String SYSTEM32   = "/Windows/System32";
    public static final String RECYCLE    = "/Recycle Bin";
    public static final String TEMP       = "/Windows/Temp";

    public VirtualFileSystem() {
        root = new FSNode("", true);
        // Windows-style directory structure
        mkdir(DESKTOP);
        mkdir(DOCUMENTS);
        mkdir(DOWNLOADS);
        mkdir(PICTURES);
        mkdir(PROGRAMS);
        mkdir(SYSTEM);
        mkdir(SYSTEM32);
        mkdir(RECYCLE);
        mkdir(TEMP);
    }

    /** Convert internal path to Windows-style display: /Users/User/Desktop → C:\Users\User\Desktop */
    public static String displayPath(String internalPath) {
        if (internalPath == null || internalPath.isEmpty() || internalPath.equals("/")) return "C:\\";
        String p = internalPath.startsWith("/") ? internalPath : "/" + internalPath;
        return "C:" + p.replace('/', '\\');
    }

    /** Convert display path back to internal: C:\Users\User\Desktop → /Users/User/Desktop */
    public static String internalPath(String displayPath) {
        if (displayPath == null) return "/";
        String p = displayPath.replace('\\', '/');
        if (p.startsWith("C:") || p.startsWith("c:")) p = p.substring(2);
        if (!p.startsWith("/")) p = "/" + p;
        return p;
    }

    /** Get a friendly name for special folders */
    public static String friendlyName(String path) {
        if (path == null) return "";
        return switch (path) {
            case "/" -> "This PC";
            case "/Users" -> "Users";
            case "/Users/User" -> "User";
            case "/Users/User/Desktop" -> "Desktop";
            case "/Users/User/Documents" -> "Documents";
            case "/Users/User/Downloads" -> "Downloads";
            case "/Users/User/Pictures" -> "Pictures";
            case "/Program Files" -> "Program Files";
            case "/Windows" -> "Windows";
            case "/Windows/System32" -> "System32";
            case "/Windows/Temp" -> "Temp";
            case "/Recycle Bin" -> "Recycle Bin";
            default -> {
                int lastSlash = path.lastIndexOf('/');
                yield lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            }
        };
    }

    /** Move a file/directory to the Recycle Bin (soft delete) */
    public boolean recycle(String path) {
        String norm = normalize(path);
        if (norm.isEmpty() || norm.startsWith("Recycle Bin") || norm.startsWith("Windows")) return false;
        FSNode node = resolve(norm);
        if (node == null) return false;
        // Store original path as metadata
        String name = node.name;
        String recycleName = name + ".del";
        // Avoid collisions
        int counter = 1;
        while (resolve(normalize(RECYCLE + "/" + recycleName)) != null) {
            recycleName = name + "_" + counter + ".del";
            counter++;
        }
        // Write metadata file with original path
        writeFile(RECYCLE + "/" + recycleName + ".meta", norm);
        // Move the actual item
        if (node.isDirectory) {
            // For directories, just move the FSNode
            FSNode recycleNode = resolve(normalize(RECYCLE));
            if (recycleNode == null) return false;
            recycleNode.children.put(recycleName, node);
            dirty = true;
            // Remove from original location
            return removeFromParent(norm);
        } else {
            // For files, copy content then delete original
            writeFile(RECYCLE + "/" + recycleName, node.content);
            return delete(path);
        }
    }

    /** Restore a file from the Recycle Bin to its original location */
    public boolean restore(String recycledName) {
        String metaPath = RECYCLE + "/" + recycledName + ".meta";
        String originalPath = readFile(metaPath);
        if (originalPath == null) return false;
        String itemPath = RECYCLE + "/" + recycledName;
        if (!exists(itemPath)) return false;
        // Move back to original location
        boolean moved = move(itemPath, "/" + originalPath);
        if (moved) {
            delete(metaPath);
        }
        return moved;
    }

    /** Empty the Recycle Bin */
    public void emptyRecycleBin() {
        FSNode bin = resolve(normalize(RECYCLE));
        if (bin != null && bin.isDirectory && !bin.children.isEmpty()) {
            bin.children.clear();
            dirty = true;
        }
    }

    /** Get the count of items in the Recycle Bin */
    public int recycleBinCount() {
        List<String> items = list(RECYCLE);
        if (items == null) return 0;
        int count = 0;
        for (String s : items) if (!s.endsWith(".meta")) count++;
        return count;
    }

    // --- Public API ---

    public boolean exists(String path) {
        return resolve(normalize(path)) != null;
    }

    public boolean isDirectory(String path) {
        FSNode node = resolve(normalize(path));
        return node != null && node.isDirectory;
    }

    public boolean isFile(String path) {
        FSNode node = resolve(normalize(path));
        return node != null && !node.isDirectory;
    }

    public String readFile(String path) {
        FSNode node = resolve(normalize(path));
        if (node == null || node.isDirectory) return null;
        return node.content;
    }

    /** Read raw bytes (uses ISO-8859-1 round-trip for binary safety). */
    public byte[] readBytes(String path) {
        String s = readFile(path);
        if (s == null) return null;
        return s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** Write raw bytes (stored as ISO-8859-1 string for binary safety). */
    public boolean writeBytes(String path, byte[] data) {
        if (data == null) return false;
        return writeFile(path, new String(data, java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    public boolean writeFile(String path, String content) {
        String norm = normalize(path);
        String[] parts = splitPath(norm);
        if (parts.length == 0) return false;

        // Ensure parent directories exist
        FSNode parent = root;
        for (int i = 0; i < parts.length - 1; i++) {
            FSNode child = parent.children.get(parts[i]);
            if (child == null) {
                child = new FSNode(parts[i], true);
                parent.children.put(parts[i], child);
            } else if (!child.isDirectory) {
                return false; // path component is a file, can't traverse
            }
            parent = child;
        }

        String fileName = parts[parts.length - 1];
        FSNode existing = parent.children.get(fileName);
        if (existing != null && existing.isDirectory) return false;

        FSNode file = new FSNode(fileName, false);
        file.content = content;
        parent.children.put(fileName, file);
        dirty = true;
        return true;
    }

    public boolean mkdir(String path) {
        String norm = normalize(path);
        String[] parts = splitPath(norm);
        if (parts.length == 0) return true;

        FSNode current = root;
        for (String part : parts) {
            FSNode child = current.children.get(part);
            if (child == null) {
                child = new FSNode(part, true);
                current.children.put(part, child);
                dirty = true;
            } else if (!child.isDirectory) {
                return false;
            }
            current = child;
        }
        return true;
    }

    public boolean delete(String path) {
        String norm = normalize(path);
        if (norm.isEmpty()) return false; // can't delete root

        String[] parts = splitPath(norm);
        if (parts.length == 0) return false;

        FSNode parent = root;
        for (int i = 0; i < parts.length - 1; i++) {
            FSNode child = parent.children.get(parts[i]);
            if (child == null || !child.isDirectory) return false;
            parent = child;
        }

        boolean removed = parent.children.remove(parts[parts.length - 1]) != null;
        if (removed) dirty = true;
        return removed;
    }

    public List<String> list(String path) {
        FSNode node = resolve(normalize(path));
        if (node == null || !node.isDirectory) return null;
        List<String> result = new ArrayList<>(node.children.keySet());
        // Append / to directories for clarity
        List<String> formatted = new ArrayList<>();
        for (String name : result) {
            FSNode child = node.children.get(name);
            formatted.add(child.isDirectory ? name + "/" : name);
        }
        return formatted;
    }

    public boolean copy(String src, String dest) {
        FSNode srcNode = resolve(normalize(src));
        if (srcNode == null) return false;
        if (srcNode.isDirectory) return false; // only file copy for now
        return writeFile(dest, srcNode.content);
    }

    public boolean move(String src, String dest) {
        if (copy(src, dest)) {
            return delete(src);
        }
        return false;
    }

    public long getSize(String path) {
        FSNode node = resolve(normalize(path));
        if (node == null) return -1;
        if (!node.isDirectory) return node.content != null ? node.content.length() : 0;
        return countBytes(node);
    }

    // --- Path helpers ---

    private String normalize(String path) {
        if (path == null) return "";
        path = path.replace('\\', '/');
        // Remove leading/trailing slashes
        while (path.startsWith("/")) path = path.substring(1);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        // Resolve . and ..
        String[] parts = path.split("/");
        List<String> resolved = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!resolved.isEmpty()) resolved.removeLast();
            } else {
                resolved.add(part);
            }
        }
        return String.join("/", resolved);
    }

    private String[] splitPath(String normalized) {
        if (normalized.isEmpty()) return new String[0];
        return normalized.split("/");
    }

    private FSNode resolve(String normalized) {
        if (normalized.isEmpty()) return root;
        String[] parts = splitPath(normalized);
        FSNode current = root;
        for (String part : parts) {
            if (!current.isDirectory) return null;
            current = current.children.get(part);
            if (current == null) return null;
        }
        return current;
    }

    private long countBytes(FSNode node) {
        if (!node.isDirectory) return node.content != null ? node.content.length() : 0;
        long total = 0;
        for (FSNode child : node.children.values()) {
            total += countBytes(child);
        }
        return total;
    }

    public boolean isDirty() { return dirty; }
    public void clearDirty() { dirty = false; }

    // --- NBT Serialization ---

    public CompoundTag save() {
        return saveNode(root);
    }

    public void load(CompoundTag tag) {
        root.children.clear();
        loadNode(root, tag);
    }

    private CompoundTag saveNode(FSNode node) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("dir", node.isDirectory);
        if (node.isDirectory) {
            CompoundTag childrenTag = new CompoundTag();
            for (Map.Entry<String, FSNode> entry : node.children.entrySet()) {
                childrenTag.put(entry.getKey(), saveNode(entry.getValue()));
            }
            tag.put("children", childrenTag);
        } else {
            tag.putString("content", node.content != null ? node.content : "");
        }
        return tag;
    }

    private void loadNode(FSNode node, CompoundTag tag) {
        node.isDirectory = tag.getBoolean("dir");
        if (node.isDirectory) {
            if (tag.contains("children", Tag.TAG_COMPOUND)) {
                CompoundTag childrenTag = tag.getCompound("children");
                for (String key : childrenTag.getAllKeys()) {
                    CompoundTag childTag = childrenTag.getCompound(key);
                    FSNode child = new FSNode(key, childTag.getBoolean("dir"));
                    loadNode(child, childTag);
                    node.children.put(key, child);
                }
            }
        } else {
            node.content = tag.getString("content");
        }
    }

    // Install a system file (used by OS to populate /Program Files)
    public void installSystemFile(String path, String content) {
        writeFile(path, content);
    }

    private boolean removeFromParent(String normalized) {
        String[] parts = splitPath(normalized);
        if (parts.length == 0) return false;
        FSNode parent = root;
        for (int i = 0; i < parts.length - 1; i++) {
            FSNode child = parent.children.get(parts[i]);
            if (child == null || !child.isDirectory) return false;
            parent = child;
        }
        boolean removed = parent.children.remove(parts[parts.length - 1]) != null;
        if (removed) dirty = true;
        return removed;
    }

    // --- Inner classes ---

    private static class FSNode {
        String name;
        boolean isDirectory;
        String content;
        final Map<String, FSNode> children = new LinkedHashMap<>();

        FSNode(String name, boolean isDirectory) {
            this.name = name;
            this.isDirectory = isDirectory;
        }
    }
}

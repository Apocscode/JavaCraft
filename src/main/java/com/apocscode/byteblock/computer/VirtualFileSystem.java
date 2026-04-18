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

    public VirtualFileSystem() {
        root = new FSNode("", true);
        // Default directory structure
        mkdir("/system");
        mkdir("/system/programs");
        mkdir("/programs");
        mkdir("/home");
        mkdir("/desktop");
        mkdir("/tmp");
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

        return parent.children.remove(parts[parts.length - 1]) != null;
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

    // Install a system file (used by OS to populate /system programs)
    public void installSystemFile(String path, String content) {
        writeFile(path, content);
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

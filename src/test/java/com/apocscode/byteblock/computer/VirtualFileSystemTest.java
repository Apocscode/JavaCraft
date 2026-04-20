package com.apocscode.byteblock.computer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VirtualFileSystem — tree-based filesystem.
 * All path operations are pure Java. NBT save/load uses MC classes and is excluded.
 */
class VirtualFileSystemTest {

    private VirtualFileSystem fs;

    @BeforeEach
    void setUp() {
        fs = new VirtualFileSystem();
    }

    // --- Default structure ---

    @Test
    void defaultDirectoriesExist() {
        assertTrue(fs.isDirectory("/system"));
        assertTrue(fs.isDirectory("/system/programs"));
        assertTrue(fs.isDirectory("/programs"));
        assertTrue(fs.isDirectory("/home"));
        assertTrue(fs.isDirectory("/desktop"));
        assertTrue(fs.isDirectory("/tmp"));
    }

    @Test
    void rootExists() {
        assertTrue(fs.exists("/"));
        assertTrue(fs.isDirectory("/"));
    }

    // --- File operations ---

    @Test
    void writeAndReadFile() {
        assertTrue(fs.writeFile("/home/test.txt", "Hello World"));
        assertEquals("Hello World", fs.readFile("/home/test.txt"));
    }

    @Test
    void writeCreatesParentDirectories() {
        assertTrue(fs.writeFile("/home/deep/nested/file.txt", "content"));
        assertTrue(fs.isDirectory("/home/deep"));
        assertTrue(fs.isDirectory("/home/deep/nested"));
        assertEquals("content", fs.readFile("/home/deep/nested/file.txt"));
    }

    @Test
    void overwriteExistingFile() {
        fs.writeFile("/home/x.txt", "old");
        fs.writeFile("/home/x.txt", "new");
        assertEquals("new", fs.readFile("/home/x.txt"));
    }

    @Test
    void readNonexistentReturnsNull() {
        assertNull(fs.readFile("/does/not/exist.txt"));
    }

    @Test
    void readDirectoryReturnsNull() {
        assertNull(fs.readFile("/home"));
    }

    @Test
    void cannotWriteFileOverDirectory() {
        // /home already exists as directory
        assertFalse(fs.writeFile("/home", "content"));
    }

    // --- Directory operations ---

    @Test
    void mkdir() {
        assertTrue(fs.mkdir("/myfolder"));
        assertTrue(fs.isDirectory("/myfolder"));
    }

    @Test
    void mkdirNested() {
        assertTrue(fs.mkdir("/a/b/c"));
        assertTrue(fs.isDirectory("/a"));
        assertTrue(fs.isDirectory("/a/b"));
        assertTrue(fs.isDirectory("/a/b/c"));
    }

    @Test
    void mkdirOverFileReturnsFalse() {
        fs.writeFile("/home/file.txt", "x");
        assertFalse(fs.mkdir("/home/file.txt/sub"));
    }

    // --- List ---

    @Test
    void listDirectory() {
        fs.writeFile("/home/a.txt", "aaa");
        fs.writeFile("/home/b.txt", "bbb");
        fs.mkdir("/home/subdir");

        List<String> entries = fs.list("/home");
        assertNotNull(entries);
        assertTrue(entries.contains("a.txt"));
        assertTrue(entries.contains("b.txt"));
        assertTrue(entries.contains("subdir/"));
    }

    @Test
    void listNonexistentReturnsNull() {
        assertNull(fs.list("/nope"));
    }

    @Test
    void listFileReturnsNull() {
        fs.writeFile("/home/f.txt", "x");
        assertNull(fs.list("/home/f.txt"));
    }

    // --- Delete ---

    @Test
    void deleteFile() {
        fs.writeFile("/home/del.txt", "bye");
        assertTrue(fs.delete("/home/del.txt"));
        assertFalse(fs.exists("/home/del.txt"));
    }

    @Test
    void deleteDirectory() {
        fs.mkdir("/home/removeme");
        assertTrue(fs.delete("/home/removeme"));
        assertFalse(fs.exists("/home/removeme"));
    }

    @Test
    void deleteNonexistentReturnsFalse() {
        assertFalse(fs.delete("/nope"));
    }

    @Test
    void cannotDeleteRoot() {
        assertFalse(fs.delete("/"));
    }

    // --- Copy / Move ---

    @Test
    void copyFile() {
        fs.writeFile("/home/src.txt", "data");
        assertTrue(fs.copy("/home/src.txt", "/home/dst.txt"));
        assertEquals("data", fs.readFile("/home/dst.txt"));
        assertEquals("data", fs.readFile("/home/src.txt")); // original still there
    }

    @Test
    void moveFile() {
        fs.writeFile("/home/old.txt", "payload");
        assertTrue(fs.move("/home/old.txt", "/home/new.txt"));
        assertEquals("payload", fs.readFile("/home/new.txt"));
        assertFalse(fs.exists("/home/old.txt"));
    }

    @Test
    void copyNonexistentReturnsFalse() {
        assertFalse(fs.copy("/nope", "/dest"));
    }

    // --- Path normalization ---

    @Test
    void trailingSlashNormalized() {
        fs.writeFile("/home/test.txt", "x");
        assertTrue(fs.exists("/home/test.txt/"));
    }

    @Test
    void backslashNormalized() {
        fs.writeFile("/home/test.txt", "x");
        assertEquals("x", fs.readFile("\\home\\test.txt"));
    }

    @Test
    void dotResolved() {
        fs.writeFile("/home/test.txt", "x");
        assertEquals("x", fs.readFile("/home/./test.txt"));
    }

    @Test
    void dotDotResolved() {
        fs.writeFile("/home/test.txt", "x");
        assertEquals("x", fs.readFile("/home/sub/../test.txt"));
    }

    // --- Size ---

    @Test
    void fileSize() {
        fs.writeFile("/home/size.txt", "12345");
        assertEquals(5, fs.getSize("/home/size.txt"));
    }

    @Test
    void directorySize() {
        fs.writeFile("/home/a.txt", "aaa");
        fs.writeFile("/home/b.txt", "bb");
        // Size of /home should include both files (3 + 2 = 5)
        long size = fs.getSize("/home");
        assertTrue(size >= 5);
    }

    @Test
    void nonexistentSizeReturnsNegative() {
        assertEquals(-1, fs.getSize("/nope"));
    }

    // --- isFile / isDirectory ---

    @Test
    void isFileTrue() {
        fs.writeFile("/home/f.txt", "x");
        assertTrue(fs.isFile("/home/f.txt"));
        assertFalse(fs.isDirectory("/home/f.txt"));
    }

    @Test
    void isDirectoryTrue() {
        assertTrue(fs.isDirectory("/home"));
        assertFalse(fs.isFile("/home"));
    }

    @Test
    void isFileNonexistent() {
        assertFalse(fs.isFile("/nope"));
        assertFalse(fs.isDirectory("/nope"));
    }
}

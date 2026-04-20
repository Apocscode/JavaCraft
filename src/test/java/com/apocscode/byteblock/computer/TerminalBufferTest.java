package com.apocscode.byteblock.computer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TerminalBuffer — pure Java grid buffer, no MC dependencies.
 */
class TerminalBufferTest {

    private TerminalBuffer buf;

    @BeforeEach
    void setUp() {
        buf = new TerminalBuffer();
    }

    @Test
    void defaultDimensions() {
        assertEquals(80, buf.getWidth());
        assertEquals(25, buf.getHeight());
    }

    @Test
    void clearSetsSpaces() {
        buf.write("Hello");
        buf.clear();
        for (int x = 0; x < buf.getWidth(); x++) {
            assertEquals(' ', buf.getChar(x, 0));
        }
        assertEquals(0, buf.getCursorX());
        assertEquals(0, buf.getCursorY());
    }

    @Test
    void writeAndRead() {
        buf.write("ABC");
        assertEquals('A', buf.getChar(0, 0));
        assertEquals('B', buf.getChar(1, 0));
        assertEquals('C', buf.getChar(2, 0));
        assertEquals(3, buf.getCursorX());
        assertEquals(0, buf.getCursorY());
    }

    @Test
    void writeDoesNotExceedWidth() {
        String longText = "X".repeat(100);
        buf.write(longText);
        // Cursor should stop at WIDTH
        assertEquals(80, buf.getCursorX());
        // Character at last valid position
        assertEquals('X', buf.getChar(79, 0));
    }

    @Test
    void newLineScrollsAtBottom() {
        // Fill to last row
        for (int i = 0; i < 24; i++) {
            buf.newLine();
        }
        assertEquals(24, buf.getCursorY());

        // Write something on last row
        buf.write("LAST");

        // One more newline triggers scroll
        buf.newLine();
        assertEquals(24, buf.getCursorY()); // stays at bottom
        // "LAST" should have scrolled up to row 23
        assertEquals('L', buf.getChar(0, 23));
    }

    @Test
    void printWithNewlines() {
        buf.print("line1\nline2\nline3");
        assertEquals('l', buf.getChar(0, 0));
        assertEquals('l', buf.getChar(0, 1));
        assertEquals('l', buf.getChar(0, 2));
    }

    @Test
    void setCursorPos() {
        buf.setCursorPos(10, 5);
        assertEquals(10, buf.getCursorX());
        assertEquals(5, buf.getCursorY());
    }

    @Test
    void setCursorPosClamps() {
        buf.setCursorPos(-5, -5);
        assertEquals(0, buf.getCursorX());
        assertEquals(0, buf.getCursorY());

        buf.setCursorPos(999, 999);
        assertEquals(79, buf.getCursorX());
        assertEquals(24, buf.getCursorY());
    }

    @Test
    void writeAtSpecificPosition() {
        buf.writeAt(5, 3, "Hi");
        assertEquals('H', buf.getChar(5, 3));
        assertEquals('i', buf.getChar(6, 3));
        // Should not move the main cursor
        assertEquals(0, buf.getCursorX());
        assertEquals(0, buf.getCursorY());
    }

    @Test
    void colorTracking() {
        buf.setTextColor(5);         // lime
        buf.setBackgroundColor(11);  // blue
        buf.write("A");
        assertEquals(5, buf.getFg(0, 0));
        assertEquals(11, buf.getBg(0, 0));
    }

    @Test
    void fillRect() {
        buf.setTextColor(9);
        buf.fillRect(2, 2, 5, 4, '#');
        for (int y = 2; y <= 4; y++) {
            for (int x = 2; x <= 5; x++) {
                assertEquals('#', buf.getChar(x, y));
                assertEquals(9, buf.getFg(x, y));
            }
        }
        // Outside the rect should be unchanged
        assertEquals(' ', buf.getChar(1, 2));
        assertEquals(' ', buf.getChar(6, 2));
    }

    @Test
    void hLine() {
        buf.hLine(3, 7, 1, '-');
        for (int x = 3; x <= 7; x++) {
            assertEquals('-', buf.getChar(x, 1));
        }
        assertEquals(' ', buf.getChar(2, 1));
        assertEquals(' ', buf.getChar(8, 1));
    }

    @Test
    void clearLine() {
        buf.setCursorPos(0, 3);
        buf.write("Some text here");
        buf.setCursorPos(0, 3);
        buf.clearLine();
        for (int x = 0; x < buf.getWidth(); x++) {
            assertEquals(' ', buf.getChar(x, 3));
        }
    }

    @Test
    void scrollDown() {
        buf.write("ROW0");
        buf.scrollDown();
        // Row 0 content should now be at row 1
        assertEquals('R', buf.getChar(0, 1));
        // Row 0 should be blank
        assertEquals(' ', buf.getChar(0, 0));
    }

    @Test
    void colorMasksTo4Bits() {
        buf.setTextColor(0xFF);  // should mask to 0xF = 15
        assertEquals(15, buf.getCurrentFg());
        buf.setBackgroundColor(0x23); // should mask to 3
        assertEquals(3, buf.getCurrentBg());
    }
}

package com.apocscode.byteblock.client;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 8x8 monochrome face presets for the robot. Each preset is a 64-bit mask:
 * bit (y*8 + x) = 1 means the pixel at column x (0..7), row y (0..7) is lit.
 *
 * <p>Coordinate convention used by the renderer: x increases to the head's
 * RIGHT (viewer's left when looking at the robot face-on), y increases UP.
 *
 * <p>Designed by hand. Use a Lua-style art string to keep the source readable —
 * '#' = lit, '.' = off, top row first. {@link #parse(String...)} converts to
 * a 64-bit mask, flipping rows so y=0 is the bottom row visually.
 */
public final class FacePresets {
    private FacePresets() {}

    private static final Map<String, Long> PRESETS = new LinkedHashMap<>();

    /** Returns mask for an id, or the classic face mask if unknown. */
    public static long get(String id) {
        if (id == null) return PRESETS.get("classic");
        Long m = PRESETS.get(id);
        return m == null ? PRESETS.get("classic") : m;
    }

    /** Ordered preset ids for UI listing. */
    public static String[] ids() { return PRESETS.keySet().toArray(new String[0]); }

    /** Parse an 8-row art table — '#' for lit, '.' for off, top row first. */
    private static long parse(String... rows) {
        long bits = 0L;
        for (int yTop = 0; yTop < 8; yTop++) {
            String row = yTop < rows.length ? rows[yTop] : "........";
            int y = 7 - yTop; // flip so y=0 is bottom
            for (int x = 0; x < 8 && x < row.length(); x++) {
                if (row.charAt(x) == '#') bits |= 1L << (y * 8 + x);
            }
        }
        return bits;
    }

    static {
        // Classic — two rectangular eyes + mouth grille (matches default look)
        PRESETS.put("classic", parse(
            "........",
            "........",
            ".##..##.",
            ".##..##.",
            "........",
            ".######.",
            "........",
            "........"
        ));
        PRESETS.put("happy", parse(
            "........",
            "........",
            "#......#",
            ".#....#.",
            "..####..",
            "........",
            ".######.",
            "........"
        ));
        PRESETS.put("smile", parse(
            "........",
            ".##..##.",
            ".##..##.",
            "........",
            "#......#",
            "#......#",
            ".######.",
            "........"
        ));
        PRESETS.put("angry", parse(
            "........",
            "##....##",
            ".##..##.",
            "..####..",
            "..####..",
            "........",
            "########",
            "........"
        ));
        PRESETS.put("sad", parse(
            "........",
            ".##..##.",
            ".##..##.",
            "........",
            "........",
            ".######.",
            "#......#",
            "........"
        ));
        PRESETS.put("sleepy", parse(
            "........",
            "........",
            "########",
            "........",
            "........",
            ".######.",
            "........",
            "........"
        ));
        PRESETS.put("surprised", parse(
            "........",
            ".##..##.",
            "####.###",
            ".##..##.",
            "........",
            "...##...",
            "..####..",
            "...##..."
        ));
        PRESETS.put("cool", parse(
            "........",
            "########",
            "########",
            "########",
            "........",
            ".######.",
            "........",
            "........"
        ));
        PRESETS.put("heart", parse(
            ".##..##.",
            "########",
            "########",
            ".######.",
            "..####..",
            "...##...",
            "........",
            ".######."
        ));
        PRESETS.put("ko", parse(
            "........",
            "#.#..#.#",
            ".#....#.",
            "#.#..#.#",
            "........",
            "...##...",
            "..####..",
            "...##..."
        ));
        PRESETS.put("cyclops", parse(
            "........",
            "..####..",
            ".######.",
            "..####..",
            "........",
            "........",
            ".######.",
            "........"
        ));
        PRESETS.put("r2", parse(
            "...##...",
            "..####..",
            "...##...",
            "........",
            "........",
            "#......#",
            "...##...",
            "#......#"
        ));
        PRESETS.put("pixel_grin", parse(
            "........",
            "##.##.##",
            "##.##.##",
            "........",
            "........",
            "########",
            ".#....#.",
            "..####.."
        ));
        PRESETS.put("star", parse(
            "...#...#",
            "..###.##",
            ".####.#.",
            "..###...",
            "........",
            ".######.",
            "........",
            "........"
        ));
        PRESETS.put("question", parse(
            ".####...",
            "##..##..",
            "....##..",
            "...##...",
            "..##....",
            "........",
            "..##....",
            "........"
        ));
        PRESETS.put("skull", parse(
            "........",
            ".######.",
            "##.##.##",
            "##....##",
            ".######.",
            ".#.##.#.",
            "..####..",
            "........"
        ));
    }
}

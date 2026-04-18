package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.TerminalBuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Visual block-based programming IDE for Drones and Robots.
 * Puzzle blocks snap together vertically to form programs.
 * Left palette selects blocks, right canvas arranges them.
 */
public class PuzzleProgram extends OSProgram {

    // ── Block system ────────────────────────────────────────

    enum Category { FLOW, MOVE, ACTION, DRONE }

    enum BlockType {
        // Flow
        START      ("Start",       5, Category.FLOW,   false, false, false),
        WAIT       ("Wait",        4, Category.FLOW,   true,  false, false),
        REPEAT     ("Repeat",      5, Category.FLOW,   true,  true,  false),
        END_REPEAT ("End Repeat",  5, Category.FLOW,   false, false, false),
        IF_DETECT  ("If Block",    4, Category.FLOW,   false, false, true),
        IF_FUEL    ("If Fuel >",   4, Category.FLOW,   true,  false, false),
        ELSE       ("Else",        4, Category.FLOW,   false, false, false),
        END_IF     ("End If",      4, Category.FLOW,   false, false, false),
        // Movement
        FORWARD    ("Forward",     9, Category.MOVE,   false, false, false),
        BACK       ("Back",        9, Category.MOVE,   false, false, false),
        UP         ("Up",          9, Category.MOVE,   false, false, false),
        DOWN       ("Down",        9, Category.MOVE,   false, false, false),
        TURN_LEFT  ("Turn Left",   9, Category.MOVE,   false, false, true),
        TURN_RIGHT ("Turn Right",  9, Category.MOVE,   false, false, true),
        // Actions (robot only)
        DIG        ("Dig",         1, Category.ACTION,  false, false, true),
        DIG_UP     ("Dig Up",      1, Category.ACTION,  false, false, true),
        DIG_DOWN   ("Dig Down",    1, Category.ACTION,  false, false, true),
        PLACE      ("Place",       1, Category.ACTION,  true,  false, true),
        SELECT     ("Select Slot", 1, Category.ACTION,  true,  false, true),
        // Drone-specific
        GOTO       ("Goto",        3, Category.DRONE,   true,  false, false),
        HOVER      ("Hover",       3, Category.DRONE,   false, false, false),
        LAND       ("Land",        3, Category.DRONE,   false, false, false);

        final String label;
        final int color;
        final Category category;
        final boolean hasParam;
        final boolean opensBlock;
        final boolean robotOnly;

        BlockType(String label, int color, Category cat,
                  boolean hasParam, boolean opens, boolean robotOnly) {
            this.label = label;
            this.color = color;
            this.category = cat;
            this.hasParam = hasParam;
            this.opensBlock = opens;
            this.robotOnly = robotOnly;
        }

        boolean droneOnly() { return category == Category.DRONE; }
    }

    static class Block {
        BlockType type;
        int param;

        Block(BlockType type) {
            this.type = type;
            this.param = switch (type) {
                case REPEAT -> 3;
                case WAIT   -> 20;
                case SELECT, PLACE -> 1;
                case IF_FUEL -> 100;
                case GOTO    -> 10;
                default      -> 0;
            };
        }

        String paramText() {
            return switch (type) {
                case REPEAT  -> "x" + param;
                case WAIT    -> String.format("%.1fs", param / 20.0);
                case SELECT, PLACE -> "#" + param;
                case IF_FUEL -> ">" + param;
                case GOTO    -> param + "m";
                default      -> "";
            };
        }
    }

    enum Target { ROBOT, DRONE }

    // ── State ───────────────────────────────────────────────

    private Target target = Target.ROBOT;
    private String filePath;
    private final List<Block> blocks = new ArrayList<>();
    private int canvasCursor = 0;
    private int paletteCursor = 0;
    private boolean paletteFocus = true;
    private int canvasScroll = 0;
    private int paletteScroll = 0;
    private boolean modified = false;
    private boolean needsRedraw = true;
    private String statusMsg = "";
    private int statusTicks = 0;

    private final List<Object> palette = new ArrayList<>(); // String headers + BlockType items

    // Layout constants
    private static final int PAL_W = 14;
    private static final int HDR = 1;
    private static final int FTR = 1;

    // ── Constructor ─────────────────────────────────────────

    public PuzzleProgram() { this(null); }

    public PuzzleProgram(String filePath) {
        super("Puzzle");
        this.filePath = filePath;
    }

    // ── Lifecycle ───────────────────────────────────────────

    @Override
    public void init(JavaOS os) {
        this.os = os;
        if (filePath != null) {
            String content = os.getFileSystem().readFile(filePath);
            if (content != null && loadFromFile(content)) {
                setStatus("Loaded: " + shortPath(filePath));
            } else {
                setStatus("New: " + shortPath(filePath));
            }
        } else {
            filePath = "/home/program.pzl";
            setStatus("New puzzle program");
        }
        if (blocks.isEmpty()) blocks.add(new Block(BlockType.START));
        buildPalette();
    }

    @Override
    public boolean tick() {
        if (statusTicks > 0 && --statusTicks == 0) needsRedraw = true;
        return running;
    }

    @Override
    public void handleEvent(OSEvent event) {
        switch (event.getType()) {
            case KEY -> { handleKey(event.getInt(0)); needsRedraw = true; }
            case MOUSE_CLICK -> { handleClick(event.getInt(1), event.getInt(2)); needsRedraw = true; }
            case MOUSE_SCROLL -> {
                int dir = event.getInt(0);
                if (paletteFocus) paletteScroll = Math.max(0, paletteScroll + dir);
                else canvasScroll = Math.max(0, canvasScroll + dir);
                needsRedraw = true;
            }
            default -> {}
        }
    }

    // ── Key / Mouse ─────────────────────────────────────────

    private void handleKey(int key) {
        int vis = TerminalBuffer.HEIGHT - HDR - FTR;
        switch (key) {
            case 258 -> paletteFocus = !paletteFocus;              // Tab
            case 265 -> {                                           // Up
                if (paletteFocus) movePalette(-1);
                else { if (canvasCursor > 0) canvasCursor--; ensureCanvasVis(vis); }
            }
            case 264 -> {                                           // Down
                if (paletteFocus) movePalette(1);
                else { if (canvasCursor < blocks.size() - 1) canvasCursor++; ensureCanvasVis(vis); }
            }
            case 263 -> {                                           // Left  = param --
                if (!paletteFocus && canvasCursor < blocks.size()) {
                    Block b = blocks.get(canvasCursor);
                    if (b.type.hasParam && b.param > 1) { b.param--; modified = true; }
                }
            }
            case 262 -> {                                           // Right = param ++
                if (!paletteFocus && canvasCursor < blocks.size()) {
                    Block b = blocks.get(canvasCursor);
                    if (b.type.hasParam) { b.param++; modified = true; }
                }
            }
            case 257, 335 -> { if (paletteFocus) insertFromPalette(); } // Enter
            case 259, 261 -> {                                      // Backspace / Delete
                if (!paletteFocus && canvasCursor < blocks.size()
                        && blocks.get(canvasCursor).type != BlockType.START) {
                    blocks.remove(canvasCursor);
                    if (canvasCursor >= blocks.size() && canvasCursor > 0) canvasCursor--;
                    modified = true;
                }
            }
            case 290 -> runProgram();      // F1
            case 291 -> saveProgram();     // F2
            case 292 -> running = false;   // F3
            case 293 -> toggleTarget();    // F4
            case 294 -> exportLua();       // F5
        }
    }

    private void handleClick(int mx, int my) {
        if (my == 0 || my == TerminalBuffer.HEIGHT - 1) return;
        int vy = my - HDR;
        if (mx < PAL_W - 1) {
            paletteFocus = true;
            int idx = paletteScroll + vy;
            if (idx >= 0 && idx < palette.size() && palette.get(idx) instanceof BlockType)
                paletteCursor = idx;
        } else {
            paletteFocus = false;
            int idx = canvasScroll + vy;
            if (idx >= 0 && idx < blocks.size()) canvasCursor = idx;
        }
    }

    // ── Palette ─────────────────────────────────────────────

    private void buildPalette() {
        palette.clear();
        Category lastCat = null;
        for (BlockType bt : BlockType.values()) {
            if (bt == BlockType.START) continue;
            if (bt.robotOnly && target == Target.DRONE) continue;
            if (bt.droneOnly() && target == Target.ROBOT) continue;
            if (bt.category != lastCat) {
                lastCat = bt.category;
                palette.add(lastCat.name());
            }
            palette.add(bt);
        }
        paletteCursor = 0;
        while (paletteCursor < palette.size()
                && !(palette.get(paletteCursor) instanceof BlockType))
            paletteCursor++;
    }

    private void movePalette(int dir) {
        int next = paletteCursor + dir;
        while (next >= 0 && next < palette.size() && palette.get(next) instanceof String)
            next += dir;
        if (next >= 0 && next < palette.size()) {
            paletteCursor = next;
            ensurePaletteVis();
        }
    }

    private void ensurePaletteVis() {
        int vis = TerminalBuffer.HEIGHT - HDR - FTR;
        if (paletteCursor < paletteScroll) paletteScroll = paletteCursor;
        if (paletteCursor >= paletteScroll + vis) paletteScroll = paletteCursor - vis + 1;
    }

    private void ensureCanvasVis(int vis) {
        if (canvasCursor < canvasScroll) canvasScroll = canvasCursor;
        if (canvasCursor >= canvasScroll + vis) canvasScroll = canvasCursor - vis + 1;
    }

    private void insertFromPalette() {
        if (paletteCursor < 0 || paletteCursor >= palette.size()) return;
        Object entry = palette.get(paletteCursor);
        if (!(entry instanceof BlockType bt)) return;

        int pos = Math.min(canvasCursor + 1, blocks.size());
        blocks.add(pos, new Block(bt));

        // Auto-insert closing block for openers
        if (bt == BlockType.REPEAT)
            blocks.add(pos + 1, new Block(BlockType.END_REPEAT));
        else if (bt == BlockType.IF_DETECT || bt == BlockType.IF_FUEL)
            blocks.add(pos + 1, new Block(BlockType.END_IF));

        canvasCursor = pos;
        paletteFocus = false;
        modified = true;
    }

    private void toggleTarget() {
        target = (target == Target.ROBOT) ? Target.DRONE : Target.ROBOT;
        buildPalette();
        setStatus("Target: " + target.name());
    }

    // ── Save / Load ─────────────────────────────────────────

    private void saveProgram() {
        StringBuilder sb = new StringBuilder();
        sb.append("#target=").append(target.name().toLowerCase()).append('\n');
        for (Block b : blocks) {
            sb.append(b.type.name());
            if (b.type.hasParam) sb.append(' ').append(b.param);
            sb.append('\n');
        }
        os.getFileSystem().writeFile(filePath, sb.toString());
        modified = false;
        setStatus("Saved: " + shortPath(filePath));
    }

    private boolean loadFromFile(String content) {
        blocks.clear();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#target=")) {
                try { target = Target.valueOf(line.substring(8).toUpperCase()); }
                catch (Exception ignored) {}
                continue;
            }
            if (line.startsWith("#")) continue;
            String[] parts = line.split("\\s+", 2);
            try {
                BlockType bt = BlockType.valueOf(parts[0]);
                Block b = new Block(bt);
                if (parts.length > 1 && bt.hasParam) {
                    try { b.param = Integer.parseInt(parts[1]); }
                    catch (NumberFormatException ignored) {}
                }
                blocks.add(b);
            } catch (IllegalArgumentException ignored) {}
        }
        buildPalette();
        return !blocks.isEmpty();
    }

    // ── Run / Export ────────────────────────────────────────

    private void runProgram() {
        String lua = generateLua();
        os.getFileSystem().writeFile("/tmp/_puzzle_run.lua", lua);
        os.launchProgram(new LuaShellProgram("/tmp/_puzzle_run.lua"));
        setStatus("Running...");
    }

    private void exportLua() {
        String luaPath = filePath.endsWith(".pzl")
                ? filePath.replace(".pzl", ".lua") : filePath + ".lua";
        os.getFileSystem().writeFile(luaPath, generateLua());
        setStatus("Exported: " + shortPath(luaPath));
    }

    private String generateLua() {
        StringBuilder sb = new StringBuilder();
        sb.append("-- Generated by Puzzle IDE\n");
        sb.append("-- Target: ").append(target.name().toLowerCase()).append("\n\n");
        String api = target == Target.ROBOT ? "robot" : "drone";

        int indent = 0;
        for (Block b : blocks) {
            BlockType t = b.type;

            // Decrease indent for closers / else
            if (t == BlockType.END_REPEAT || t == BlockType.END_IF)
                indent = Math.max(0, indent - 1);
            if (t == BlockType.ELSE)
                indent = Math.max(0, indent - 1);

            String pre = "  ".repeat(indent);

            switch (t) {
                case START      -> {}
                case FORWARD    -> sb.append(pre).append(api).append(".forward()\n");
                case BACK       -> sb.append(pre).append(api).append(".back()\n");
                case UP         -> sb.append(pre).append(api).append(".up()\n");
                case DOWN       -> sb.append(pre).append(api).append(".down()\n");
                case TURN_LEFT  -> sb.append(pre).append("robot.turnLeft()\n");
                case TURN_RIGHT -> sb.append(pre).append("robot.turnRight()\n");
                case DIG        -> sb.append(pre).append("robot.dig()\n");
                case DIG_UP     -> sb.append(pre).append("robot.digUp()\n");
                case DIG_DOWN   -> sb.append(pre).append("robot.digDown()\n");
                case PLACE      -> sb.append(pre).append("robot.place(").append(b.param).append(")\n");
                case SELECT     -> sb.append(pre).append("robot.select(").append(b.param).append(")\n");
                case WAIT       -> sb.append(pre).append("sleep(").append(String.format("%.1f", b.param / 20.0)).append(")\n");
                case REPEAT     -> sb.append(pre).append("for _i = 1, ").append(b.param).append(" do\n");
                case END_REPEAT -> sb.append(pre).append("end\n");
                case IF_DETECT  -> sb.append(pre).append("if robot.detect() then\n");
                case IF_FUEL    -> sb.append(pre).append("if ").append(api).append(".getFuel() > ").append(b.param).append(" then\n");
                case ELSE       -> sb.append(pre).append("else\n");
                case END_IF     -> sb.append(pre).append("end\n");
                case GOTO       -> sb.append(pre).append("drone.goto(").append(b.param).append(")\n");
                case HOVER      -> sb.append(pre).append("drone.hover()\n");
                case LAND       -> sb.append(pre).append("drone.land()\n");
            }

            // Increase indent for openers / else
            if (t == BlockType.REPEAT || t == BlockType.IF_DETECT
                    || t == BlockType.IF_FUEL || t == BlockType.ELSE)
                indent++;
        }
        return sb.toString();
    }

    // ── Render ──────────────────────────────────────────────

    @Override
    public void render(TerminalBuffer buf) {
        if (!needsRedraw) return;
        needsRedraw = false;
        int vis = TerminalBuffer.HEIGHT - HDR - FTR;

        // ── Header ──
        buf.setTextColor(0);
        buf.setBackgroundColor(11);
        buf.hLine(0, TerminalBuffer.WIDTH - 1, 0, ' ');
        buf.writeAt(0, 0, clip(" Puzzle: " + shortPath(filePath)
                + (modified ? "*" : "") + "  [" + target.name() + "]", 36));
        buf.setTextColor(7);
        buf.writeAt(37, 0, clip("F1:Run F3:X", 14));

        // ── Palette ──
        for (int vy = 0; vy < vis; vy++) {
            int idx = paletteScroll + vy;
            int sy = HDR + vy;
            if (idx >= palette.size()) {
                buf.setTextColor(0);
                buf.setBackgroundColor(15);
                buf.fillRect(0, sy, PAL_W - 2, sy, ' ');
            } else {
                Object entry = palette.get(idx);
                if (entry instanceof String h) {
                    buf.setTextColor(0);
                    buf.setBackgroundColor(8);
                    buf.fillRect(0, sy, PAL_W - 2, sy, ' ');
                    buf.writeAt(0, sy, clip("\u2500" + h, PAL_W - 1));
                } else if (entry instanceof BlockType bt) {
                    boolean sel = paletteFocus && idx == paletteCursor;
                    buf.setTextColor(sel ? 15 : bt.color);
                    buf.setBackgroundColor(sel ? bt.color : 15);
                    buf.fillRect(0, sy, PAL_W - 2, sy, ' ');
                    buf.writeAt(1, sy, clip(bt.label, PAL_W - 2));
                }
            }
            // Divider
            buf.setTextColor(7);
            buf.setBackgroundColor(15);
            buf.writeAt(PAL_W - 1, sy, "\u2502");
        }

        // ── Canvas ──
        int[] indents = computeIndents();
        int cw = TerminalBuffer.WIDTH - PAL_W;
        for (int vy = 0; vy < vis; vy++) {
            int idx = canvasScroll + vy;
            int sy = HDR + vy;
            int cx = PAL_W;
            buf.setBackgroundColor(15);
            buf.fillRect(cx, sy, cx + cw - 1, sy, ' ');

            if (idx >= blocks.size()) {
                if (idx == blocks.size()) {
                    buf.setTextColor(8);
                    buf.writeAt(cx + 2, sy, "~ end ~");
                }
                continue;
            }
            Block b = blocks.get(idx);
            boolean sel = !paletteFocus && idx == canvasCursor;
            int ind = indents[idx];

            if (sel) {
                buf.setBackgroundColor(8);
                buf.fillRect(cx, sy, cx + cw - 1, sy, ' ');
            }

            // Indent lines
            buf.setTextColor(7);
            for (int i = 0; i < ind; i++) {
                int lx = cx + 1 + i * 2;
                if (lx < cx + cw) buf.writeAt(lx, sy, "\u2502");
            }

            // Block text
            int tx = cx + 1 + ind * 2;
            buf.setTextColor(sel ? 0 : b.type.color);
            String text = blockLabel(b);
            if (tx < cx + cw - 1)
                buf.writeAt(tx, sy, clip(text, cx + cw - tx - 1));

            // Param hint
            if (sel && b.type.hasParam) {
                buf.setTextColor(4);
                int hx = cx + cw - 6;
                if (hx > tx + text.length())
                    buf.writeAt(hx, sy, "\u2190\u2192:v");
            }
        }

        // ── Footer ──
        buf.setTextColor(0);
        buf.setBackgroundColor(7);
        buf.hLine(0, TerminalBuffer.WIDTH - 1, TerminalBuffer.HEIGHT - 1, ' ');
        String foot;
        if (statusTicks > 0) foot = " " + statusMsg;
        else foot = " Tab:\u2194 \u2191\u2193:Nav Ret:Add Del:Rm F2:Sav F4:Tgt F5:Lua";
        buf.writeAt(0, TerminalBuffer.HEIGHT - 1, clip(foot, TerminalBuffer.WIDTH));
    }

    private String blockLabel(Block b) {
        return switch (b.type) {
            case START      -> "\u25BA START";
            case REPEAT     -> "\u2554 REPEAT " + b.paramText();
            case END_REPEAT -> "\u255A END REPEAT";
            case IF_DETECT, IF_FUEL -> "\u2554 " + b.type.label.toUpperCase() + " " + b.paramText();
            case ELSE       -> "\u2560 ELSE";
            case END_IF     -> "\u255A END IF";
            default         -> "\u25CF " + b.type.label.toUpperCase() + " " + b.paramText();
        };
    }

    private int[] computeIndents() {
        int[] ind = new int[blocks.size()];
        int level = 0;
        for (int i = 0; i < blocks.size(); i++) {
            BlockType t = blocks.get(i).type;
            if (t == BlockType.END_REPEAT || t == BlockType.END_IF)
                level = Math.max(0, level - 1);
            if (t == BlockType.ELSE) {
                level = Math.max(0, level - 1);
                ind[i] = level;
                level++;
            } else {
                ind[i] = level;
            }
            if (t == BlockType.REPEAT || t == BlockType.IF_DETECT || t == BlockType.IF_FUEL)
                level++;
        }
        return ind;
    }

    // ── Helpers ─────────────────────────────────────────────

    private void setStatus(String msg) {
        statusMsg = msg;
        statusTicks = 60;
        needsRedraw = true;
    }

    private String shortPath(String p) {
        if (p == null) return "untitled";
        return p.length() <= 18 ? p : "..." + p.substring(p.length() - 15);
    }

    private String clip(String s, int max) {
        return max <= 0 ? "" : (s.length() <= max ? s : s.substring(0, max));
    }
}

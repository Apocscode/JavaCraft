package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.PixelBuffer;
import com.apocscode.byteblock.computer.TerminalBuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Visual puzzle-piece programming IDE inspired by PneumaticCraft.
 * Features a draggable piece canvas, palette tray, GPS coordinate picker,
 * Lua/Java language selection, and PNC-style jigsaw connectors.
 * Pieces snap together vertically (flow) and horizontally (parameters).
 */
public class PuzzleProgram extends OSProgram {

    // ── Piece system (PNC-inspired) ─────────────────────────

    enum Category { FLOW, MOVE, ACTION, SENSOR, DRONE }

    enum PieceType {
        // Flow control — green (5=lime)
        START       ("Start",       5,  Category.FLOW,   false, true,  false, false),
        WAIT        ("Wait",        5,  Category.FLOW,   true,  true,  false, false),
        REPEAT      ("Repeat",      5,  Category.FLOW,   true,  true,  false, false),
        END_REPEAT  ("End Repeat",  5,  Category.FLOW,   false, true,  false, false),
        IF_DETECT   ("If Block",    5,  Category.FLOW,   false, true,  false, false),
        IF_FUEL     ("If Fuel >",   5,  Category.FLOW,   true,  true,  false, false),
        ELSE        ("Else",        5,  Category.FLOW,   false, true,  false, false),
        END_IF      ("End If",      5,  Category.FLOW,   false, true,  false, false),
        LABEL       ("Label",       5,  Category.FLOW,   true,  true,  false, false),
        JUMP        ("Jump To",     5,  Category.FLOW,   true,  true,  false, false),
        // Movement — cyan (9)
        FORWARD     ("Forward",     9,  Category.MOVE,   false, true,  false, false),
        BACK        ("Back",        9,  Category.MOVE,   false, true,  false, false),
        UP          ("Up",          9,  Category.MOVE,   false, true,  false, false),
        DOWN        ("Down",        9,  Category.MOVE,   false, true,  false, false),
        TURN_LEFT   ("Turn Left",   9,  Category.MOVE,   false, true,  true,  false),
        TURN_RIGHT  ("Turn Right",  9,  Category.MOVE,   false, true,  true,  false),
        // Actions — orange (1)
        DIG         ("Dig",         1,  Category.ACTION,  false, true,  true,  false),
        DIG_UP      ("Dig Up",      1,  Category.ACTION,  false, true,  true,  false),
        DIG_DOWN    ("Dig Down",    1,  Category.ACTION,  false, true,  true,  false),
        PLACE       ("Place",       1,  Category.ACTION,  true,  true,  true,  false),
        SELECT      ("Slot",        1,  Category.ACTION,  true,  true,  true,  false),
        ATTACK      ("Attack",      1,  Category.ACTION,  false, true,  true,  false),
        DROP        ("Drop",        1,  Category.ACTION,  true,  true,  true,  false),
        SUCK        ("Suck",        1,  Category.ACTION,  false, true,  true,  false),
        // Sensors — yellow (4)
        DETECT      ("Detect",      4,  Category.SENSOR,  false, false, true,  false),
        FUEL_LVL    ("Fuel Level",  4,  Category.SENSOR,  false, false, false, false),
        COMPARE     ("Compare",     4,  Category.SENSOR,  false, false, true,  false),
        INSPECT     ("Inspect",     4,  Category.SENSOR,  false, false, true,  false),
        // Drone — light blue (3)
        GOTO_XYZ    ("Goto XYZ",    3,  Category.DRONE,   true,  true,  false, true),
        HOVER       ("Hover",       3,  Category.DRONE,   false, true,  false, true),
        LAND        ("Land",        3,  Category.DRONE,   false, true,  false, true),
        GPS_SET     ("GPS Set",     3,  Category.DRONE,   true,  true,  false, true);

        final String label;
        final int color;         // palette color index
        final Category category;
        final boolean hasParam;  // has editable numeric parameter
        final boolean hasOutput; // has downward flow connector
        final boolean robotOnly;
        final boolean droneOnly;

        PieceType(String label, int color, Category cat,
                  boolean hasParam, boolean hasOutput, boolean robotOnly, boolean droneOnly) {
            this.label = label;
            this.color = color;
            this.category = cat;
            this.hasParam = hasParam;
            this.hasOutput = hasOutput;
            this.robotOnly = robotOnly;
            this.droneOnly = droneOnly;
        }

        int pieceWidth() { return PIECE_W; }
    }

    /** A placed piece on the canvas */
    static class Piece {
        PieceType type;
        int x, y;        // canvas position (char coords)
        int param;
        String paramStr; // for label/jump names, GPS coords
        Piece outputConnection;  // piece below (vertical flow)

        Piece(PieceType type, int x, int y) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.param = defaultParam(type);
            this.paramStr = "";
        }

        static int defaultParam(PieceType t) {
            return switch (t) {
                case REPEAT    -> 3;
                case WAIT      -> 20;
                case SELECT, PLACE, DROP -> 1;
                case IF_FUEL   -> 100;
                case GOTO_XYZ  -> 0;
                case GPS_SET   -> 0;
                default        -> 0;
            };
        }

        String displayLabel() {
            String p = paramDisplay();
            return p.isEmpty() ? type.label : type.label + " " + p;
        }

        String paramDisplay() {
            return switch (type) {
                case REPEAT    -> "x" + param;
                case WAIT      -> String.format("%.1fs", param / 20.0);
                case SELECT, PLACE, DROP -> "#" + param;
                case IF_FUEL   -> ">" + param;
                case GOTO_XYZ  -> paramStr.isEmpty() ? "0,0,0" : paramStr;
                case GPS_SET   -> paramStr.isEmpty() ? "set" : paramStr;
                case LABEL, JUMP -> paramStr.isEmpty() ? "?" : paramStr;
                default        -> "";
            };
        }

        int width() { return PIECE_W; }
    }

    enum Target { ROBOT, DRONE }
    enum Lang { LUA, JAVA }

    // ── UI state machine ────────────────────────────────────

    enum Mode {
        LANG_SELECT,   // initial language picker
        EDITING,       // main canvas + palette
        PARAM_EDIT,    // editing a piece parameter
        GPS_PICK       // GPS coordinate entry overlay
    }

    // ── State ───────────────────────────────────────────────

    private Target target = Target.ROBOT;
    private Lang lang = Lang.LUA;
    private String filePath;
    private final List<Piece> pieces = new ArrayList<>();
    private Mode mode = Mode.LANG_SELECT;

    // Canvas view
    private int scrollX = 0, scrollY = 0;  // canvas scroll offset

    // Pixel layout state (set during renderGraphics, used by mouse handlers)
    private int layoutCanvasW = 532;  // updated each frame from buffer width
    private int layoutH = 400;        // updated each frame from buffer height

    // Palette
    private final List<Object> palette = new ArrayList<>(); // String headers + PieceType
    private int paletteCursor = 0;
    private int paletteScroll = 0;

    // Selection / dragging
    private Piece selectedPiece;
    private Piece draggingPiece;
    private int dragOffX, dragOffY;
    private boolean draggingFromPalette;

    // Canvas cursor (for keyboard navigation)
    private int canvasCursorIdx = 0;

    // Focus
    private boolean paletteFocus = true;

    // Param edit state
    private final StringBuilder paramInput = new StringBuilder();
    private Piece editingPiece;

    // GPS pick state
    private final StringBuilder gpsInput = new StringBuilder();
    private Piece gpsPiece;

    // General
    private boolean modified = false;
    private String statusMsg = "";
    private int statusTicks = 0;

    // Layout constants — PNC-style 3-row puzzle pieces
    private static final int PIECE_H = 3;
    private static final int PIECE_W = 20;
    private static final int PAL_W = 16;
    private static final int HDR = 1;
    private static final int FTR = 1;
    private static final int CANVAS_X = PAL_W;
    private static final int CANVAS_W = TerminalBuffer.WIDTH - PAL_W;
    private static final int CANVAS_H = TerminalBuffer.HEIGHT - HDR - FTR;

    // Pixel-mode compact rendering constants (PCR style)
    private static final int PX_GRID = 5;      // pixels per grid X unit
    private static final int PX_GRIDV = 10;    // pixels per grid Y unit
    private static final int PX_PW = PIECE_W * PX_GRID;   // 100px piece width
    private static final int PX_PH = PIECE_H * PX_GRIDV;  // 30px piece height
    private static final int PX_TAB_W = 10;    // jigsaw connector width
    private static final int PX_TAB_H = 5;     // jigsaw connector height
    private static final int PX_HDR_H = 18;    // pixel header height
    private static final int PX_FTR_H = 14;    // pixel footer height

    // ── Constructors ────────────────────────────────────────

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
                mode = Mode.EDITING;
                setStatus("Loaded: " + shortPath(filePath));
            } else {
                setStatus("New: " + shortPath(filePath));
            }
        } else {
            filePath = "/Users/User/Documents/program.pzl";
            setStatus("New puzzle program");
        }
        if (mode == Mode.EDITING && pieces.isEmpty()) {
            pieces.add(new Piece(PieceType.START, 2, 0));
        }
        buildPalette();
    }

    @Override
    public boolean tick() {
        if (statusTicks > 0 && --statusTicks == 0) {}
        return running;
    }

    @Override
    public void handleEvent(OSEvent event) {
        switch (mode) {
            case LANG_SELECT -> handleLangSelectEvent(event);
            case EDITING     -> handleEditingEvent(event);
            case PARAM_EDIT  -> handleParamEditEvent(event);
            case GPS_PICK    -> handleGpsPickEvent(event);
        }
    }

    // ── Language selection screen ────────────────────────────

    private void handleLangSelectEvent(OSEvent event) {
        if (event.getType() == OSEvent.Type.KEY) {
            int key = event.getInt(0);
            switch (key) {
                case 49 -> { lang = Lang.LUA; finishLangSelect(); }          // 1
                case 50 -> { lang = Lang.JAVA; finishLangSelect(); }         // 2
                case 265, 264 -> lang = (lang == Lang.LUA) ? Lang.JAVA : Lang.LUA; // Up/Down toggle
                case 257, 335 -> finishLangSelect();                         // Enter
                case 292 -> running = false;                                 // F3 = exit
            }
        } else if (event.getType() == OSEvent.Type.MOUSE_CLICK) {
            int my = event.getInt(2);
            if (my == 8) { lang = Lang.LUA; finishLangSelect(); }
            else if (my == 10) { lang = Lang.JAVA; finishLangSelect(); }
        }
    }

    private void finishLangSelect() {
        mode = Mode.EDITING;
        if (pieces.isEmpty()) {
            pieces.add(new Piece(PieceType.START, 2, 0));
        }
        buildPalette();
        setStatus("Language: " + lang.name() + " | Target: " + target.name());
    }

    // ── Main editing event handling ─────────────────────────

    private void handleEditingEvent(OSEvent event) {
        switch (event.getType()) {
            case KEY -> handleEditKey(event.getInt(0));
            case MOUSE_CLICK -> handleEditClick(event.getInt(0), event.getInt(1), event.getInt(2));
            case MOUSE_DRAG  -> handleEditDrag(event.getInt(1), event.getInt(2));
            case MOUSE_UP    -> handleEditRelease();
            case MOUSE_SCROLL -> {
                int dir = event.getInt(0);
                int mx = event.getInt(1);
                int mpxScroll = mx * PixelBuffer.CELL_W;
                if (mpxScroll >= layoutCanvasW) {
                    paletteScroll = Math.max(0, paletteScroll + dir);
                } else {
                    scrollY = Math.max(0, scrollY + dir);
                }
            }
            default -> {}
        }
    }

    private void handleEditKey(int key) {
        int vis = CANVAS_H;
        switch (key) {
            case 258 -> paletteFocus = !paletteFocus;  // Tab
            case 265 -> { // Up
                if (paletteFocus) movePalette(-1);
                else { if (canvasCursorIdx > 0) canvasCursorIdx--; selectPieceByIndex(); }
            }
            case 264 -> { // Down
                if (paletteFocus) movePalette(1);
                else { if (canvasCursorIdx < pieces.size() - 1) canvasCursorIdx++; selectPieceByIndex(); }
            }
            case 263 -> { // Left = param --
                if (!paletteFocus && selectedPiece != null && selectedPiece.type.hasParam
                        && selectedPiece.param > 0) {
                    selectedPiece.param--;
                    modified = true;
                }
            }
            case 262 -> { // Right = param ++
                if (!paletteFocus && selectedPiece != null && selectedPiece.type.hasParam) {
                    selectedPiece.param++;
                    modified = true;
                }
            }
            case 257, 335 -> { // Enter
                if (paletteFocus) insertFromPalette();
                else if (selectedPiece != null) startParamEdit(selectedPiece);
            }
            case 259, 261 -> { // Backspace / Delete
                if (!paletteFocus && selectedPiece != null
                        && selectedPiece.type != PieceType.START) {
                    pieces.remove(selectedPiece);
                    selectedPiece = null;
                    if (canvasCursorIdx >= pieces.size()) canvasCursorIdx = pieces.size() - 1;
                    rebuildConnections();
                    modified = true;
                }
            }
            case 290 -> runProgram();       // F1
            case 291 -> saveProgram();      // F2
            case 292 -> running = false;    // F3
            case 293 -> toggleTarget();     // F4
            case 294 -> exportCode();       // F5
            case 295 -> { // F6 = GPS set
                if (selectedPiece != null && (selectedPiece.type == PieceType.GOTO_XYZ
                        || selectedPiece.type == PieceType.GPS_SET)) {
                    startGpsPicker(selectedPiece);
                }
            }
            case 296 -> { // F7 = change language
                mode = Mode.LANG_SELECT;
            }
        }
    }

    private void handleEditClick(int button, int mx, int my) {
        int CW = PixelBuffer.CELL_W, CH = PixelBuffer.CELL_H;
        int mpx = mx * CW, mpy = my * CH;

        // Header / footer
        if (mpy < PX_HDR_H || mpy >= layoutH - PX_FTR_H) return;

        // Right-click on canvas = delete piece under cursor
        if (button == 1 && mpx < layoutCanvasW) {
            Piece p = getPieceAtPx(mpx, mpy);
            if (p != null && p.type != PieceType.START) {
                pieces.remove(p);
                if (selectedPiece == p) selectedPiece = null;
                rebuildConnections();
                modified = true;
            }
            return;
        }

        // Palette click (right side)
        if (mpx >= layoutCanvasW) {
            paletteFocus = true;
            int vy = (mpy - PX_HDR_H) / 16;
            int idx = paletteScroll + vy;
            if (idx >= 0 && idx < palette.size() && palette.get(idx) instanceof PieceType) {
                paletteCursor = idx;
            }
            return;
        }

        // Canvas left-click
        paletteFocus = false;
        Piece p = getPieceAtPx(mpx, mpy);
        if (p != null) {
            selectedPiece = p;
            canvasCursorIdx = pieces.indexOf(p);
            draggingPiece = p;
            int pScreenX = (p.x - scrollX) * PX_GRID;
            int pScreenY = PX_HDR_H + (p.y - scrollY) * PX_GRIDV;
            dragOffX = mpx - pScreenX;
            dragOffY = mpy - pScreenY;
            draggingFromPalette = false;
        } else {
            selectedPiece = null;
        }
    }

    private void handleEditDrag(int mx, int my) {
        if (draggingPiece != null) {
            int CW = PixelBuffer.CELL_W, CH = PixelBuffer.CELL_H;
            int mpx = mx * CW, mpy = my * CH;
            int canPx = mpx - dragOffX + scrollX * PX_GRID;
            int canPy = (mpy - PX_HDR_H) - dragOffY + scrollY * PX_GRIDV;
            int newX = canPx / PX_GRID;
            int newY = canPy / PX_GRIDV;
            draggingPiece.x = Math.max(0, newX);
            draggingPiece.y = Math.max(0, newY);
            modified = true;
        }
    }

    private void handleEditRelease() {
        if (draggingPiece != null) {
            draggingPiece.x = Math.max(0, (draggingPiece.x / 2) * 2);
            draggingPiece.y = Math.max(0, draggingPiece.y);
            rebuildConnections();
            draggingPiece = null;
        }
    }

    // ── Param edit mode ─────────────────────────────────────

    private void startParamEdit(Piece p) {
        if (!p.type.hasParam && p.type != PieceType.LABEL && p.type != PieceType.JUMP) return;
        editingPiece = p;
        paramInput.setLength(0);
        if (p.type == PieceType.LABEL || p.type == PieceType.JUMP) {
            paramInput.append(p.paramStr);
        } else {
            paramInput.append(p.param);
        }
        mode = Mode.PARAM_EDIT;
    }

    private void handleParamEditEvent(OSEvent event) {
        if (event.getType() == OSEvent.Type.KEY) {
            int key = event.getInt(0);
            switch (key) {
                case 256 -> mode = Mode.EDITING; // Escape
                case 257, 335 -> { // Enter
                    applyParamEdit();
                    mode = Mode.EDITING;
                }
                case 259 -> { // Backspace
                    if (!paramInput.isEmpty()) paramInput.deleteCharAt(paramInput.length() - 1);
                }
            }
        } else if (event.getType() == OSEvent.Type.CHAR) {
            char c = event.getString(0).charAt(0);
            if (c >= 32 && paramInput.length() < 20) paramInput.append(c);
        }
    }

    private void applyParamEdit() {
        if (editingPiece == null) return;
        if (editingPiece.type == PieceType.LABEL || editingPiece.type == PieceType.JUMP) {
            editingPiece.paramStr = paramInput.toString().trim();
        } else {
            try {
                editingPiece.param = Math.max(0, Integer.parseInt(paramInput.toString().trim()));
            } catch (NumberFormatException ignored) {}
        }
        modified = true;
    }

    // ── GPS picker mode ─────────────────────────────────────

    private void startGpsPicker(Piece p) {
        gpsPiece = p;
        gpsInput.setLength(0);
        gpsInput.append(p.paramStr.isEmpty() ? "0,64,0" : p.paramStr);
        mode = Mode.GPS_PICK;
    }

    private void handleGpsPickEvent(OSEvent event) {
        if (event.getType() == OSEvent.Type.KEY) {
            int key = event.getInt(0);
            switch (key) {
                case 256 -> mode = Mode.EDITING; // Escape
                case 257, 335 -> { // Enter
                    if (gpsPiece != null) {
                        gpsPiece.paramStr = gpsInput.toString().trim();
                        modified = true;
                    }
                    mode = Mode.EDITING;
                }
                case 259 -> { // Backspace
                    if (!gpsInput.isEmpty()) gpsInput.deleteCharAt(gpsInput.length() - 1);
                }
            }
        } else if (event.getType() == OSEvent.Type.CHAR) {
            char c = event.getString(0).charAt(0);
            // Allow digits, minus, comma, space
            if ((c >= '0' && c <= '9') || c == '-' || c == ',' || c == ' ') {
                if (gpsInput.length() < 30) gpsInput.append(c);
            }
        }
    }

    // ── Palette ─────────────────────────────────────────────

    private void buildPalette() {
        palette.clear();
        Category lastCat = null;
        for (PieceType pt : PieceType.values()) {
            if (pt == PieceType.START) continue;
            if (pt.robotOnly && target == Target.DRONE) continue;
            if (pt.droneOnly && target == Target.ROBOT) continue;
            if (pt.category != lastCat) {
                lastCat = pt.category;
                palette.add(lastCat.name());
            }
            palette.add(pt);
        }
        paletteCursor = 0;
        while (paletteCursor < palette.size()
                && !(palette.get(paletteCursor) instanceof PieceType))
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
        if (paletteCursor < paletteScroll) paletteScroll = paletteCursor;
        if (paletteCursor >= paletteScroll + CANVAS_H) paletteScroll = paletteCursor - CANVAS_H + 1;
    }

    private void selectPieceByIndex() {
        if (canvasCursorIdx >= 0 && canvasCursorIdx < pieces.size()) {
            selectedPiece = pieces.get(canvasCursorIdx);
            // Scroll canvas to show selected piece
            if (selectedPiece.y < scrollY) scrollY = selectedPiece.y;
            if (selectedPiece.y + PIECE_H > scrollY + CANVAS_H) scrollY = selectedPiece.y + PIECE_H - CANVAS_H;
        }
    }

    private void insertFromPalette() {
        if (paletteCursor < 0 || paletteCursor >= palette.size()) return;
        Object entry = palette.get(paletteCursor);
        if (!(entry instanceof PieceType pt)) return;

        // Position: below the last piece, or at a default spot
        int px = 2, py = 1;
        if (!pieces.isEmpty()) {
            Piece last = pieces.getLast();
            px = last.x;
            py = last.y + PIECE_H + 1;
        }

        Piece newPiece = new Piece(pt, px, py);
        pieces.add(newPiece);

        // Auto-insert closing block for openers
        if (pt == PieceType.REPEAT) {
            pieces.add(new Piece(PieceType.END_REPEAT, px, py + PIECE_H + 1));
        } else if (pt == PieceType.IF_DETECT || pt == PieceType.IF_FUEL) {
            pieces.add(new Piece(PieceType.END_IF, px, py + PIECE_H + 1));
        }

        selectedPiece = newPiece;
        canvasCursorIdx = pieces.indexOf(newPiece);
        paletteFocus = false;
        rebuildConnections();
        modified = true;
        // Auto-scroll to new piece
        if (py + PIECE_H >= scrollY + CANVAS_H) scrollY = py + PIECE_H - CANVAS_H + 1;
    }

    private void toggleTarget() {
        target = (target == Target.ROBOT) ? Target.DRONE : Target.ROBOT;
        buildPalette();
        setStatus("Target: " + target.name());
    }

    // ── Connection rebuilding ───────────────────────────────

    private void rebuildConnections() {
        // Clear all connections
        for (Piece p : pieces) p.outputConnection = null;

        // Sort pieces by Y then X for connection order
        List<Piece> sorted = new ArrayList<>(pieces);
        sorted.sort((a, b) -> a.y != b.y ? Integer.compare(a.y, b.y) : Integer.compare(a.x, b.x));

        // Link pieces that are vertically adjacent (within snap distance)
        for (int i = 0; i < sorted.size(); i++) {
            Piece curr = sorted.get(i);
            if (!curr.type.hasOutput) continue;
            // Find closest piece below within 3 rows and similar X
            Piece best = null;
            int bestDist = Integer.MAX_VALUE;
            for (int j = 0; j < sorted.size(); j++) {
                if (i == j) continue;
                Piece other = sorted.get(j);
                int dy = other.y - curr.y;
                int dx = Math.abs(other.x - curr.x);
                if (dy > 0 && dy <= PIECE_H + 4 && dx <= 4 && dy < bestDist) {
                    best = other;
                    bestDist = dy;
                }
            }
            curr.outputConnection = best;
        }
    }

    // ── Piece hit testing ───────────────────────────────────

    private Piece getPieceAt(int screenX, int screenY) {
        int cx = screenX - CANVAS_X + scrollX;
        int cy = screenY - HDR + scrollY;
        for (int i = pieces.size() - 1; i >= 0; i--) {
            Piece p = pieces.get(i);
            if (cx >= p.x && cx < p.x + PIECE_W && cy >= p.y && cy < p.y + PIECE_H) {
                return p;
            }
        }
        return null;
    }

    /** Hit test using screen pixel coordinates (for pixel-mode mouse handling) */
    private Piece getPieceAtPx(int mpx, int mpy) {
        int canPx = mpx + scrollX * PX_GRID;
        int canPy = (mpy - PX_HDR_H) + scrollY * PX_GRIDV;
        for (int i = pieces.size() - 1; i >= 0; i--) {
            Piece p = pieces.get(i);
            int px = p.x * PX_GRID;
            int py = p.y * PX_GRIDV;
            if (canPx >= px && canPx < px + PX_PW && canPy >= py && canPy < py + PX_PH) {
                return p;
            }
        }
        return null;
    }

    // ── Save / Load ─────────────────────────────────────────

    private void saveProgram() {
        StringBuilder sb = new StringBuilder();
        sb.append("#target=").append(target.name().toLowerCase()).append('\n');
        sb.append("#lang=").append(lang.name().toLowerCase()).append('\n');
        for (Piece p : pieces) {
            sb.append(p.type.name())
              .append(' ').append(p.x)
              .append(' ').append(p.y)
              .append(' ').append(p.param);
            if (!p.paramStr.isEmpty()) sb.append(' ').append(p.paramStr);
            sb.append('\n');
        }
        os.getFileSystem().writeFile(filePath, sb.toString());
        modified = false;
        setStatus("Saved: " + shortPath(filePath));
    }

    private boolean loadFromFile(String content) {
        pieces.clear();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#target=")) {
                try { target = Target.valueOf(line.substring(8).toUpperCase()); }
                catch (Exception ignored) {}
                continue;
            }
            if (line.startsWith("#lang=")) {
                try { lang = Lang.valueOf(line.substring(6).toUpperCase()); }
                catch (Exception ignored) {}
                continue;
            }
            if (line.startsWith("#")) continue;
            String[] parts = line.split("\\s+", 5);
            if (parts.length < 4) continue;
            try {
                PieceType pt = PieceType.valueOf(parts[0]);
                int px = Integer.parseInt(parts[1]);
                int py = Integer.parseInt(parts[2]);
                int param = Integer.parseInt(parts[3]);
                Piece p = new Piece(pt, px, py);
                p.param = param;
                if (parts.length > 4) p.paramStr = parts[4];
                pieces.add(p);
            } catch (Exception ignored) {}
        }
        normalizeLayout();
        buildPalette();
        rebuildConnections();
        return !pieces.isEmpty();
    }

    /** Re-space pieces vertically so 3-row pieces don't overlap (handles old save files) */
    private void normalizeLayout() {
        List<Piece> sorted = new ArrayList<>(pieces);
        sorted.sort((a, b) -> a.y != b.y ? Integer.compare(a.y, b.y) : Integer.compare(a.x, b.x));
        int nextY = 0;
        for (Piece p : sorted) {
            if (p.y < nextY) p.y = nextY;
            nextY = p.y + PIECE_H + 1;
        }
    }

    // ── Run / Export ────────────────────────────────────────

    private void runProgram() {
        if (lang == Lang.LUA) {
            String lua = generateLua();
            os.getFileSystem().writeFile("/tmp/_puzzle_run.lua", lua);
            os.launchProgram(new LuaShellProgram("/tmp/_puzzle_run.lua"));
        } else {
            String java = generateJava();
            String javaPath = "/tmp/_puzzle_run.java";
            os.getFileSystem().writeFile(javaPath, java);
            setStatus("Java exported (no runtime)");
            return;
        }
        setStatus("Running...");
    }

    private void exportCode() {
        String ext = lang == Lang.LUA ? ".lua" : ".java";
        String outPath = filePath.endsWith(".pzl")
                ? filePath.replace(".pzl", ext) : filePath + ext;
        String code = lang == Lang.LUA ? generateLua() : generateJava();
        os.getFileSystem().writeFile(outPath, code);
        setStatus("Exported: " + shortPath(outPath));
    }

    /** Build ordered flow list by following output connections from START */
    private List<Piece> buildFlowOrder() {
        List<Piece> flow = new ArrayList<>();
        Piece current = null;
        for (Piece p : pieces) {
            if (p.type == PieceType.START) { current = p; break; }
        }
        while (current != null && flow.size() < pieces.size() + 1) {
            flow.add(current);
            current = current.outputConnection;
        }
        return flow;
    }

    private String generateLua() {
        List<Piece> flow = buildFlowOrder();
        StringBuilder sb = new StringBuilder();
        sb.append("-- Generated by Puzzle IDE (").append(lang.name()).append(")\n");
        sb.append("-- Target: ").append(target.name().toLowerCase()).append("\n\n");
        String api = target == Target.ROBOT ? "robot" : "drone";

        int indent = 0;
        for (Piece p : flow) {
            PieceType t = p.type;
            if (t == PieceType.END_REPEAT || t == PieceType.END_IF)
                indent = Math.max(0, indent - 1);
            if (t == PieceType.ELSE)
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
                case PLACE      -> sb.append(pre).append("robot.place(").append(p.param).append(")\n");
                case SELECT     -> sb.append(pre).append("robot.select(").append(p.param).append(")\n");
                case ATTACK     -> sb.append(pre).append("robot.attack()\n");
                case DROP       -> sb.append(pre).append("robot.drop(").append(p.param).append(")\n");
                case SUCK       -> sb.append(pre).append("robot.suck()\n");
                case WAIT       -> sb.append(pre).append("sleep(").append(String.format("%.1f", p.param / 20.0)).append(")\n");
                case REPEAT     -> sb.append(pre).append("for _i = 1, ").append(p.param).append(" do\n");
                case END_REPEAT -> sb.append(pre).append("end\n");
                case IF_DETECT  -> sb.append(pre).append("if robot.detect() then\n");
                case IF_FUEL    -> sb.append(pre).append("if ").append(api).append(".getFuel() > ").append(p.param).append(" then\n");
                case ELSE       -> sb.append(pre).append("else\n");
                case END_IF     -> sb.append(pre).append("end\n");
                case GOTO_XYZ   -> {
                    String coords = p.paramStr.isEmpty() ? "0,0,0" : p.paramStr;
                    sb.append(pre).append("drone.goto(").append(coords).append(")\n");
                }
                case HOVER      -> sb.append(pre).append("drone.hover()\n");
                case LAND       -> sb.append(pre).append("drone.land()\n");
                case GPS_SET    -> sb.append(pre).append("gps.set(").append(p.paramStr.isEmpty() ? "0,64,0" : p.paramStr).append(")\n");
                case LABEL      -> sb.append(pre).append("-- label: ").append(p.paramStr).append("\n");
                case JUMP       -> sb.append(pre).append("-- jump: ").append(p.paramStr).append("\n");
                case DETECT     -> sb.append(pre).append("local _det = robot.detect()\n");
                case FUEL_LVL   -> sb.append(pre).append("local _fuel = ").append(api).append(".getFuel()\n");
                case COMPARE    -> sb.append(pre).append("local _cmp = robot.compare()\n");
                case INSPECT    -> sb.append(pre).append("local _blk = robot.inspect()\n");
            }
            if (t == PieceType.REPEAT || t == PieceType.IF_DETECT
                    || t == PieceType.IF_FUEL || t == PieceType.ELSE)
                indent++;
        }
        return sb.toString();
    }

    private String generateJava() {
        List<Piece> flow = buildFlowOrder();
        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by Puzzle IDE (Java)\n");
        sb.append("// Target: ").append(target.name().toLowerCase()).append("\n\n");
        sb.append("import com.apocscode.byteblock.api.*;\n\n");
        sb.append("public class PuzzleProgram {\n");
        sb.append("  public static void main(RobotAPI ").append(target == Target.ROBOT ? "robot" : "drone").append(") {\n");
        String api = target == Target.ROBOT ? "robot" : "drone";

        int indent = 2;
        for (Piece p : flow) {
            PieceType t = p.type;
            if (t == PieceType.END_REPEAT || t == PieceType.END_IF)
                indent = Math.max(2, indent - 1);
            if (t == PieceType.ELSE)
                indent = Math.max(2, indent - 1);

            String pre = "  ".repeat(indent);
            switch (t) {
                case START      -> {}
                case FORWARD    -> sb.append(pre).append(api).append(".forward();\n");
                case BACK       -> sb.append(pre).append(api).append(".back();\n");
                case UP         -> sb.append(pre).append(api).append(".up();\n");
                case DOWN       -> sb.append(pre).append(api).append(".down();\n");
                case TURN_LEFT  -> sb.append(pre).append("robot.turnLeft();\n");
                case TURN_RIGHT -> sb.append(pre).append("robot.turnRight();\n");
                case DIG        -> sb.append(pre).append("robot.dig();\n");
                case DIG_UP     -> sb.append(pre).append("robot.digUp();\n");
                case DIG_DOWN   -> sb.append(pre).append("robot.digDown();\n");
                case PLACE      -> sb.append(pre).append("robot.place(").append(p.param).append(");\n");
                case SELECT     -> sb.append(pre).append("robot.select(").append(p.param).append(");\n");
                case ATTACK     -> sb.append(pre).append("robot.attack();\n");
                case DROP       -> sb.append(pre).append("robot.drop(").append(p.param).append(");\n");
                case SUCK       -> sb.append(pre).append("robot.suck();\n");
                case WAIT       -> sb.append(pre).append("Thread.sleep(").append(p.param * 50).append(");\n");
                case REPEAT     -> sb.append(pre).append("for (int _i = 0; _i < ").append(p.param).append("; _i++) {\n");
                case END_REPEAT -> sb.append(pre).append("}\n");
                case IF_DETECT  -> sb.append(pre).append("if (robot.detect()) {\n");
                case IF_FUEL    -> sb.append(pre).append("if (").append(api).append(".getFuel() > ").append(p.param).append(") {\n");
                case ELSE       -> sb.append(pre).append("} else {\n");
                case END_IF     -> sb.append(pre).append("}\n");
                case GOTO_XYZ   -> {
                    String coords = p.paramStr.isEmpty() ? "0, 0, 0" : p.paramStr.replace(",", ", ");
                    sb.append(pre).append("drone.goTo(").append(coords).append(");\n");
                }
                case HOVER      -> sb.append(pre).append("drone.hover();\n");
                case LAND       -> sb.append(pre).append("drone.land();\n");
                case GPS_SET    -> sb.append(pre).append("gps.set(").append(p.paramStr.isEmpty() ? "0, 64, 0" : p.paramStr.replace(",", ", ")).append(");\n");
                case LABEL      -> sb.append(pre).append("// label: ").append(p.paramStr).append("\n");
                case JUMP       -> sb.append(pre).append("// jump: ").append(p.paramStr).append("\n");
                case DETECT     -> sb.append(pre).append("boolean _det = robot.detect();\n");
                case FUEL_LVL   -> sb.append(pre).append("int _fuel = ").append(api).append(".getFuel();\n");
                case COMPARE    -> sb.append(pre).append("boolean _cmp = robot.compare();\n");
                case INSPECT    -> sb.append(pre).append("String _blk = robot.inspect();\n");
            }
            if (t == PieceType.REPEAT || t == PieceType.IF_DETECT
                    || t == PieceType.IF_FUEL || t == PieceType.ELSE)
                indent++;
        }
        sb.append("  }\n}\n");
        return sb.toString();
    }

    // ── Render ──────────────────────────────────────────────

    @Override
    public void render(TerminalBuffer buf) {
        buf.setCursorBlink(false);
        switch (mode) {
            case LANG_SELECT -> renderLangSelect(buf);
            case EDITING     -> renderEditing(buf);
            case PARAM_EDIT  -> { renderEditing(buf); renderParamOverlay(buf); }
            case GPS_PICK    -> { renderEditing(buf); renderGpsOverlay(buf); }
        }
    }

    // ── PCR-style pixel rendering (primary path) ──────────

    // Dark theme colors (ARGB)
    private static final int PX_BG        = 0xFF1A1A2E;
    private static final int PX_SIDEBAR   = 0xFF16213E;
    private static final int PX_HDR       = 0xFF0F3460;
    private static final int PX_FTR       = 0xFF1A1A3A;
    private static final int PX_TEXT      = 0xFFE8E8E8;
    private static final int PX_TEXT_DIM  = 0xFF808090;
    private static final int PX_GRID_DOT  = 0xFF252540;
    private static final int PX_SHADOW    = 0xFF0D0D1E;
    private static final int PX_SEL       = 0xFFFFFF44;
    private static final int PX_INPUT_BG  = 0xFF2A2A4A;
    private static final int PX_DIVIDER   = 0xFF303050;
    private static final int PX_DIALOG    = 0xFF1E1E3A;
    private static final int PX_CONN_LINE = 0xFF505070;

    private static int pieceArgb(int paletteIdx) {
        return switch (paletteIdx) {
            case 5  -> 0xFF2ECC71;  // lime → rich green
            case 9  -> 0xFF00BCD4;  // cyan → material cyan
            case 1  -> 0xFFFF9800;  // orange → material orange
            case 4  -> 0xFFFFEB3B;  // yellow → material yellow
            case 3  -> 0xFF42A5F5;  // light blue → material blue
            default -> TerminalBuffer.PALETTE[Math.max(0, Math.min(15, paletteIdx))];
        };
    }

    private static int darken(int argb, float f) {
        int a = (argb >> 24) & 0xFF;
        int r = Math.max(0, (int)(((argb >> 16) & 0xFF) * f));
        int g = Math.max(0, (int)(((argb >> 8) & 0xFF) * f));
        int b = Math.max(0, (int)((argb & 0xFF) * f));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lighten(int argb, float f) {
        int a = (argb >> 24) & 0xFF;
        int r = Math.min(255, (int)(((argb >> 16) & 0xFF) * f));
        int g = Math.min(255, (int)(((argb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int)((argb & 0xFF) * f));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int catArgb(Category cat) {
        return switch (cat) {
            case FLOW   -> 0xFF2ECC71;
            case MOVE   -> 0xFF00BCD4;
            case ACTION -> 0xFFFF9800;
            case SENSOR -> 0xFFFFEB3B;
            case DRONE  -> 0xFF42A5F5;
        };
    }

    private Category catByName(String name) {
        return switch (name) {
            case "MOVE"   -> Category.MOVE;
            case "ACTION" -> Category.ACTION;
            case "SENSOR" -> Category.SENSOR;
            case "DRONE"  -> Category.DRONE;
            default       -> Category.FLOW;
        };
    }

    @Override
    public void renderGraphics(PixelBuffer pb) {
        setCursorInfo(0, 0, false);
        switch (mode) {
            case LANG_SELECT -> renderLangSelectPx(pb);
            case EDITING     -> renderEditingPx(pb);
            case PARAM_EDIT  -> { renderEditingPx(pb); renderParamOverlayPx(pb); }
            case GPS_PICK    -> { renderEditingPx(pb); renderGpsOverlayPx(pb); }
        }
    }

    private void renderLangSelectPx(PixelBuffer pb) {
        int W = pb.getWidth(), H = pb.getHeight();
        pb.clear(PX_BG);

        int bw = Math.min(288, W - 20), bh = Math.min(220, H - 20);
        int bx = (W - bw) / 2, by = (H - bh) / 2;

        pb.fillRoundRect(bx + 4, by + 4, bw, bh, 8, PX_SHADOW);
        pb.fillRoundRect(bx, by, bw, bh, 8, PX_DIALOG);
        pb.fillRoundRect(bx, by, bw, 28, 8, PX_HDR);
        pb.fillRect(bx, by + 20, bw, 8, PX_HDR);
        pb.drawStringCentered(bx, bw, by + 6, "\u25C6 PUZZLE IDE", PX_TEXT);
        pb.drawHLine(bx + 8, bx + bw - 8, by + 29, PX_DIVIDER);
        pb.drawStringCentered(bx, bw, by + 44, "Select output language:", PX_TEXT);

        boolean luaSel = (lang == Lang.LUA);
        int luaY = by + 66;
        pb.fillRoundRect(bx + 24, luaY, bw - 48, 28, 6, luaSel ? 0xFF2ECC71 : PX_INPUT_BG);
        pb.drawStringCentered(bx, bw, luaY + 6, (luaSel ? "\u25B6 " : "  ") + "1. Lua", luaSel ? 0xFF000000 : PX_TEXT);

        boolean javaSel = (lang == Lang.JAVA);
        int javaY = by + 102;
        pb.fillRoundRect(bx + 24, javaY, bw - 48, 28, 6, javaSel ? 0xFF00BCD4 : PX_INPUT_BG);
        pb.drawStringCentered(bx, bw, javaY + 6, (javaSel ? "\u25B6 " : "  ") + "2. Java", javaSel ? 0xFF000000 : PX_TEXT);

        pb.drawStringCentered(bx, bw, by + 146, "Target: " + target.name(), PX_TEXT_DIM);
        pb.drawStringCentered(bx, bw, by + 172, "Up/Down Select   Enter Confirm", PX_TEXT_DIM);
        pb.drawStringCentered(bx, bw, by + 188, "F3 Exit   F4 Toggle Target", PX_TEXT_DIM);
    }

    private void renderEditingPx(PixelBuffer pb) {
        int W = pb.getWidth(), H = pb.getHeight();
        int palW = (W >= 500) ? 108 : ((W >= 350) ? 88 : 72);
        int canvasW = W - palW;
        int canvasH = H - PX_HDR_H - PX_FTR_H;

        // Update layout state for mouse handlers
        layoutCanvasW = canvasW;
        layoutH = H;

        pb.clear(PX_BG);

        // Header toolbar
        pb.fillRect(0, 0, W, PX_HDR_H, PX_HDR);
        pb.drawString(4, 1, "\u25C6 PUZZLE", PX_TEXT);
        pb.drawVLine(60, 2, PX_HDR_H - 2, PX_DIVIDER);
        String info = shortPath(filePath) + (modified ? "*" : "")
                + " [" + target.name() + "/" + lang.name() + "]";
        pb.drawString(64, 1, clip(info, (W - 200) / 8), PX_TEXT_DIM);
        // Hotkey hints on the right
        String keys = "F1\u25B6 F2\u2261 F3\u2717 F4\u2699 F5\u2191";
        pb.drawString(W - keys.length() * 8 - 4, 1, keys, PX_TEXT_DIM);

        renderCanvasPx(pb, 0, PX_HDR_H, canvasW, canvasH);
        renderPalettePx(pb, canvasW, PX_HDR_H, palW, canvasH);
        renderFooterPx(pb, 0, H - PX_FTR_H, W, PX_FTR_H);
    }

    private void renderPalettePx(PixelBuffer pb, int px, int py, int pw, int ph) {
        // Dark sidebar background
        pb.fillRect(px, py, pw, ph, PX_SIDEBAR);
        pb.drawVLine(px, py, py + ph - 1, PX_DIVIDER);

        int rowH = 16;
        int maxChars = (pw - 14) / 8;
        int visRows = ph / rowH;

        for (int vy = 0; vy < visRows; vy++) {
            int idx = paletteScroll + vy;
            if (idx >= palette.size()) continue;
            int sy = py + vy * rowH;

            Object entry = palette.get(idx);
            if (entry instanceof String h) {
                // Category header bar
                int catCol = catArgb(catByName(h));
                pb.fillRect(px + 1, sy, pw - 1, rowH, darken(catCol, 0.25f));
                pb.drawString(px + 6, sy, clip("\u2590 " + h, maxChars), catCol);
            } else if (entry instanceof PieceType pt) {
                boolean sel = paletteFocus && idx == paletteCursor;
                int pColor = pieceArgb(pt.color);
                if (sel) {
                    pb.fillRect(px + 1, sy, pw - 1, rowH, pColor);
                    pb.drawString(px + 6, sy, clip(catIcon(pt.category) + " " + pt.label, maxChars), 0xFF000000);
                } else {
                    pb.drawString(px + 6, sy, clip(catIcon(pt.category) + " " + pt.label, maxChars), pColor);
                }
            }
        }
    }

    private void renderCanvasPx(PixelBuffer pb, int cx, int cy, int cw, int ch) {
        // Subtle grid dots
        for (int py = 0; py < ch; py += 20) {
            for (int px = 0; px < cw; px += 20) {
                int gx = cx + px, gy = cy + py;
                if (gx < cx + cw && gy < cy + ch) {
                    pb.setPixel(gx, gy, PX_GRID_DOT);
                }
            }
        }

        // Connection lines (before pieces so pieces render on top)
        for (Piece p : pieces) {
            if (p.outputConnection == null) continue;
            Piece child = p.outputConnection;
            int lineX = cx + (p.x * PX_GRID + PX_PW / 2) - scrollX * PX_GRID;
            int fromY = cy + (p.y + PIECE_H) * PX_GRIDV - scrollY * PX_GRIDV;
            int toY   = cy + child.y * PX_GRIDV - scrollY * PX_GRIDV;

            if (lineX >= cx && lineX < cx + cw) {
                int y1 = Math.max(cy, fromY);
                int y2 = Math.min(cy + ch - 1, toY);
                if (y1 < y2) {
                    pb.drawVLine(lineX, y1, y2, PX_CONN_LINE);
                    pb.drawVLine(lineX + 1, y1, y2, PX_CONN_LINE);
                    // Arrow head
                    if (toY >= cy && toY < cy + ch) {
                        for (int i = 0; i < 4; i++) {
                            pb.drawHLine(lineX - i, lineX + 1 + i, toY - i, PX_CONN_LINE);
                        }
                    }
                }
            }
        }

        // Draw pieces (compact PCR-style)
        for (Piece p : pieces) drawPiecePx(pb, p, p == selectedPiece, cx, cy, cw, ch);
    }

    private void drawPiecePx(PixelBuffer pb, Piece p, boolean sel,
                              int canvasX, int canvasTop, int canvasW, int canvasH) {
        int px = canvasX + (p.x - scrollX) * PX_GRID;
        int py = canvasTop + (p.y - scrollY) * PX_GRIDV;

        // Clipping
        if (px + PX_PW + PX_TAB_W <= canvasX || px >= canvasX + canvasW) return;
        if (py + PX_PH + PX_TAB_H <= canvasTop || py >= canvasTop + canvasH) return;

        int pColor = pieceArgb(p.type.color);
        int borderColor = sel ? PX_SEL : darken(pColor, 0.45f);
        int hiColor = lighten(pColor, 1.3f);
        int darkColor = darken(pColor, 0.7f);

        // Drop shadow
        pb.fillRoundRect(px + 2, py + 2, PX_PW, PX_PH, 3, PX_SHADOW);

        // Body fill
        pb.fillRoundRect(px, py, PX_PW, PX_PH, 3, pColor);

        // Inner highlight band (top, 3D effect)
        pb.drawHLine(px + 4, px + PX_PW - 4, py + 2, hiColor);

        // Inner shadow band (bottom, depth)
        pb.drawHLine(px + 4, px + PX_PW - 4, py + PX_PH - 3, darkColor);

        // Body border
        pb.drawRect(px, py, PX_PW, PX_PH, borderColor);

        // ── Output tab (bottom) — semicircular jigsaw connector ──
        if (p.type.hasOutput) {
            int tabCX = px + PX_PW / 2;
            int tabTop = py + PX_PH - 1;
            for (int row = 0; row < PX_TAB_H; row++) {
                float t = (float)(row + 1) / PX_TAB_H;
                int halfW = (int)(PX_TAB_W / 2.0f * Math.sqrt(1 - t * t));
                if (halfW > 0) {
                    pb.drawHLine(tabCX - halfW, tabCX + halfW, tabTop + row, pColor);
                    pb.setPixel(tabCX - halfW, tabTop + row, borderColor);
                    pb.setPixel(tabCX + halfW, tabTop + row, borderColor);
                }
            }
            // Tab highlight
            pb.drawHLine(tabCX - PX_TAB_W / 2 + 2, tabCX + PX_TAB_W / 2 - 2, tabTop + 1, hiColor);
        }

        // ── Input notch (top) — semicircular cutout, skip for START ──
        if (p.type != PieceType.START) {
            int notchCX = px + PX_PW / 2;
            for (int row = 0; row < PX_TAB_H; row++) {
                float t = (float)(row + 1) / PX_TAB_H;
                int halfW = (int)(PX_TAB_W / 2.0f * Math.sqrt(1 - t * t));
                if (halfW > 0) {
                    pb.drawHLine(notchCX - halfW, notchCX + halfW, py + row, PX_BG);
                    pb.setPixel(notchCX - halfW, py + row, borderColor);
                    pb.setPixel(notchCX + halfW, py + row, borderColor);
                }
            }
        }

        // ── Label text ──
        boolean darkText = (p.type.color == 4);
        int textColor = darkText ? 0xFF1A1A2E : 0xFFFFFFFF;
        String label = catIcon(p.type.category) + " " + p.displayLabel();
        int textY = py + (PX_PH - 16) / 2;
        pb.drawString(px + 6, textY, clip(label, (PX_PW - 12) / 8), textColor);

        // ── Selection markers ──
        if (sel) {
            pb.fillRect(px - 1, py - 1, PX_PW + 2, 1, PX_SEL);
            pb.fillRect(px - 1, py + PX_PH, PX_PW + 2, 1, PX_SEL);
            pb.fillRect(px - 1, py, 1, PX_PH, PX_SEL);
            pb.fillRect(px + PX_PW, py, 1, PX_PH, PX_SEL);
        }
    }

    private void renderFooterPx(PixelBuffer pb, int fx, int fy, int fw, int fh) {
        pb.fillRect(fx, fy, fw, fh, PX_FTR);
        String foot;
        int footColor = PX_TEXT_DIM;
        if (statusTicks > 0) {
            footColor = 0xFF2ECC71;
            foot = " " + statusMsg;
        } else if (selectedPiece != null && (selectedPiece.type == PieceType.GOTO_XYZ
                || selectedPiece.type == PieceType.GPS_SET)) {
            foot = " Tab:\u2194 Enter:Edit Del:Rm F6:GPS";
        } else {
            foot = " Tab:\u2194 Enter:Edit Del:Rm F2:Save F4:Tgt";
        }
        pb.drawString(fx + 2, fy - 1, clip(foot, fw / 8), footColor);
    }

    private void renderParamOverlayPx(PixelBuffer pb) {
        int W = pb.getWidth(), H = pb.getHeight();
        pb.fillRect(0, 0, W, H, 0xAA0A0A1A);

        int bw = Math.min(240, W - 20), bh = 120;
        int bx = (W - bw) / 2, by = (H - bh) / 2;

        pb.fillRoundRect(bx + 3, by + 3, bw, bh, 6, PX_SHADOW);
        pb.fillRoundRect(bx, by, bw, bh, 6, PX_DIALOG);
        pb.fillRoundRect(bx, by, bw, 24, 6, PX_HDR);
        pb.fillRect(bx, by + 16, bw, 8, PX_HDR);
        String title = editingPiece != null ? "Edit: " + editingPiece.type.label : "Edit Parameter";
        pb.drawStringCentered(bx, bw, by + 4, title, PX_TEXT);

        pb.drawString(bx + 12, by + 34, "Value:", PX_TEXT_DIM);
        pb.fillRoundRect(bx + 12, by + 52, bw - 24, 24, 4, PX_INPUT_BG);
        pb.drawString(bx + 20, by + 56, clip(paramInput.toString() + "_", 24), PX_TEXT);
        pb.drawStringCentered(bx, bw, by + 92, "Enter: OK   Esc: Cancel", PX_TEXT_DIM);
    }

    private void renderGpsOverlayPx(PixelBuffer pb) {
        int W = pb.getWidth(), H = pb.getHeight();
        pb.fillRect(0, 0, W, H, 0xAA0A0A1A);

        int bw = Math.min(300, W - 20), bh = 160;
        int bx = (W - bw) / 2, by = (H - bh) / 2;

        pb.fillRoundRect(bx + 3, by + 3, bw, bh, 6, PX_SHADOW);
        pb.fillRoundRect(bx, by, bw, bh, 6, PX_DIALOG);
        int gpsColor = darken(0xFF42A5F5, 0.5f);
        pb.fillRoundRect(bx, by, bw, 24, 6, gpsColor);
        pb.fillRect(bx, by + 16, bw, 8, gpsColor);
        pb.drawStringCentered(bx, bw, by + 4, "\u2302 GPS Location Tool", PX_TEXT);

        pb.drawString(bx + 12, by + 34, "Enter coordinates (X,Y,Z):", PX_TEXT);
        pb.drawString(bx + 12, by + 50, "Format: x,y,z  (e.g. 100,64,-50)", PX_TEXT_DIM);
        pb.fillRoundRect(bx + 12, by + 70, bw - 24, 24, 4, PX_INPUT_BG);
        pb.drawString(bx + 20, by + 74, clip(gpsInput.toString() + "_", 34), 0xFF00BCD4);

        String[] coords = gpsInput.toString().split(",");
        if (coords.length == 3) {
            pb.drawString(bx + 12, by + 106,
                "X:" + coords[0].trim() + "  Y:" + coords[1].trim() + "  Z:" + coords[2].trim(),
                0xFF2ECC71);
        } else {
            pb.drawString(bx + 12, by + 106, "Need 3 values: X,Y,Z", 0xFFFF5555);
        }
        pb.drawStringCentered(bx, bw, by + 132, "Enter: Set   Esc: Cancel", PX_TEXT_DIM);
    }

    // ── Legacy text-mode rendering ───────────────────────────

    private String catIcon(Category cat) {
        return switch (cat) {
            case FLOW   -> "\u25B8"; // ▸
            case MOVE   -> "\u25B6"; // ▶
            case ACTION -> "\u25A0"; // ■
            case SENSOR -> "\u25C6"; // ◆
            case DRONE  -> "\u2302"; // ⌂
        };
    }

    private void renderLangSelect(TerminalBuffer buf) {
        buf.setBackgroundColor(15);
        buf.setTextColor(0);
        buf.clear();

        int bx = 22, by = 3, bw = 36;
        // Title bar
        buf.setTextColor(9);
        for (int x = bx; x < bx + bw; x++) buf.writeAt(x, by, "\u2550");
        buf.writeAt(bx, by, "\u2554");
        buf.writeAt(bx + bw - 1, by, "\u2557");
        buf.setTextColor(9);
        buf.setBackgroundColor(11);
        buf.fillRect(bx + 1, by + 1, bx + bw - 2, by + 1, ' ');
        buf.setTextColor(0);
        buf.writeAt(bx + 2, by + 1, "\u25C6 PUZZLE IDE");
        buf.setBackgroundColor(15);
        buf.setTextColor(9);
        for (int y = by + 1; y <= by + 14; y++) {
            buf.writeAt(bx, y, "\u2551");
            buf.writeAt(bx + bw - 1, y, "\u2551");
        }
        for (int x = bx + 1; x < bx + bw - 1; x++) buf.writeAt(x, by + 2, "\u2550");
        buf.writeAt(bx, by + 2, "\u2560");
        buf.writeAt(bx + bw - 1, by + 2, "\u2563");

        // Body background
        for (int y = by + 3; y <= by + 13; y++)
            buf.fillRect(bx + 1, y, bx + bw - 2, y, ' ');

        buf.setTextColor(0);
        buf.writeAt(bx + 3, by + 4, "Select output language:");

        // Lua option
        boolean luaSel = (lang == Lang.LUA);
        buf.setBackgroundColor(luaSel ? 5 : 15);
        buf.setTextColor(luaSel ? 0 : 5);
        buf.fillRect(bx + 4, by + 6, bx + bw - 5, by + 6, ' ');
        buf.writeAt(bx + 5, by + 6, (luaSel ? "\u25B6 " : "  ") + "1. Lua");

        // Java option
        boolean javaSel = (lang == Lang.JAVA);
        buf.setBackgroundColor(javaSel ? 9 : 15);
        buf.setTextColor(javaSel ? 0 : 9);
        buf.fillRect(bx + 4, by + 8, bx + bw - 5, by + 8, ' ');
        buf.writeAt(bx + 5, by + 8, (javaSel ? "\u25B6 " : "  ") + "2. Java");

        // Target
        buf.setBackgroundColor(15);
        buf.setTextColor(8);
        buf.writeAt(bx + 3, by + 10, "Target: " + target.name());

        // Hints
        buf.setTextColor(7);
        buf.writeAt(bx + 3, by + 12, "\u2191\u2193 Select  Enter Confirm");
        buf.writeAt(bx + 3, by + 13, "F3 Exit   F4 Toggle Target");

        // Bottom border
        buf.setTextColor(9);
        for (int x = bx + 1; x < bx + bw - 1; x++) buf.writeAt(x, by + 14, "\u2550");
        buf.writeAt(bx, by + 14, "\u255A");
        buf.writeAt(bx + bw - 1, by + 14, "\u255D");
    }

    // ── Main editing render ─────────────────────────────────

    private void renderEditing(TerminalBuffer buf) {
        // ── Header toolbar (dark blue) ──
        buf.setTextColor(0);
        buf.setBackgroundColor(11);
        buf.hLine(0, TerminalBuffer.WIDTH - 1, 0, ' ');
        buf.writeAt(1, 0, "\u25C6 PUZZLE IDE");
        buf.setTextColor(7);
        buf.writeAt(13, 0, "\u2502");
        buf.setTextColor(0);
        String info = " " + shortPath(filePath) + (modified ? "*" : "")
                + "  [" + target.name() + "/" + lang.name() + "]";
        buf.writeAt(14, 0, clip(info, 30));
        // Toolbar buttons
        buf.setTextColor(7);
        buf.writeAt(45, 0, "\u2502");
        buf.setTextColor(0);
        buf.writeAt(47, 0, "F1\u25B6 F2\u2261 F3\u2717 F4\u2699 F5\u2191 F7\u266A");

        renderPalette(buf);
        renderCanvas(buf);
        renderFooter(buf);
    }

    // ── PNC-style palette (dark sidebar) ────────────────────

    private void renderPalette(TerminalBuffer buf) {
        // Dark background for entire palette
        buf.setBackgroundColor(15);
        for (int vy = 0; vy < CANVAS_H; vy++) {
            buf.setTextColor(7);
            buf.fillRect(0, HDR + vy, PAL_W - 2, HDR + vy, ' ');
            // Divider line
            buf.writeAt(PAL_W - 1, HDR + vy, "\u2502");
        }

        for (int vy = 0; vy < CANVAS_H; vy++) {
            int idx = paletteScroll + vy;
            int sy = HDR + vy;
            if (idx >= palette.size()) continue;

            Object entry = palette.get(idx);
            if (entry instanceof String h) {
                // Category header — colored bar
                int catClr = catColorByName(h);
                buf.setBackgroundColor(catClr);
                buf.setTextColor(0);
                buf.fillRect(0, sy, PAL_W - 2, sy, ' ');
                buf.writeAt(1, sy, clip("\u2590 " + h, PAL_W - 2));
            } else if (entry instanceof PieceType pt) {
                boolean sel = paletteFocus && idx == paletteCursor;
                if (sel) {
                    // Selected: colored background, white text
                    buf.setBackgroundColor(pt.color);
                    buf.setTextColor(0);
                } else {
                    // Normal: dark background, colored text
                    buf.setBackgroundColor(15);
                    buf.setTextColor(pt.color);
                }
                buf.fillRect(0, sy, PAL_W - 2, sy, ' ');
                String icon = catIcon(pt.category);
                buf.writeAt(1, sy, clip(icon + " " + pt.label, PAL_W - 2));
            }
            // Divider always on top
            buf.setTextColor(7);
            buf.setBackgroundColor(15);
            buf.writeAt(PAL_W - 1, sy, "\u2502");
        }
    }

    private int catColorByName(String name) {
        return switch (name) {
            case "FLOW"   -> 5;
            case "MOVE"   -> 9;
            case "ACTION" -> 1;
            case "SENSOR" -> 4;
            case "DRONE"  -> 3;
            default       -> 7;
        };
    }

    // ── PNC-style canvas with 3-row puzzle pieces ───────────

    private void renderCanvas(TerminalBuffer buf) {
        int W = TerminalBuffer.WIDTH;

        // Dark canvas background
        buf.setTextColor(7);
        buf.setBackgroundColor(15);
        for (int vy = 0; vy < CANVAS_H; vy++)
            buf.fillRect(CANVAS_X, HDR + vy, W - 1, HDR + vy, ' ');

        // Subtle grid dots
        for (int vy = 0; vy < CANVAS_H; vy++) {
            for (int vx = 0; vx < CANVAS_W; vx += 4) {
                int cy = vy + scrollY;
                int cx = vx + scrollX;
                if (cy % 4 == 0 && cx % 4 == 0)
                    buf.writeAt(CANVAS_X + vx, HDR + vy, "\u00B7");
            }
        }

        // Connection lines (draw before pieces)
        for (Piece p : pieces) {
            if (p.outputConnection == null) continue;
            Piece child = p.outputConnection;
            int connX = CANVAS_X + p.x + PIECE_W / 2 - scrollX;
            int fromY = HDR + p.y + PIECE_H - scrollY;
            int toY   = HDR + child.y - 1 - scrollY;

            buf.setTextColor(p.type.color);
            buf.setBackgroundColor(15);
            for (int ly = fromY; ly <= toY; ly++) {
                if (ly >= HDR && ly < HDR + CANVAS_H && connX >= CANVAS_X && connX < W)
                    buf.writeAt(connX, ly, "\u2502");
            }
            // Arrow head just above child
            if (toY >= HDR && toY < HDR + CANVAS_H && connX >= CANVAS_X && connX < W)
                buf.writeAt(connX, toY, "\u25BC");
        }

        // Draw 3-row puzzle pieces
        for (Piece p : pieces) drawPiece(buf, p, p == selectedPiece);
    }

    /** Renders a single 3-row PNC-style puzzle piece with double-line border */
    private void drawPiece(TerminalBuffer buf, Piece p, boolean sel) {
        int W = TerminalBuffer.WIDTH;
        int sx = CANVAS_X + p.x - scrollX;
        int sy = HDR + p.y - scrollY;
        if (sy + PIECE_H <= HDR || sy >= HDR + CANVAS_H) return;
        if (sx + PIECE_W <= CANVAS_X || sx >= W) return;

        int borderClr = sel ? 4 : p.type.color; // yellow border when selected
        int fillClr   = p.type.color;
        int textClr   = (fillClr == 4) ? 15 : 0; // black text on yellow, white on others

        // Row 0: top border ╔═══...═══╗
        if (sy >= HDR && sy < HDR + CANVAS_H) {
            buf.setTextColor(borderClr);
            buf.setBackgroundColor(15);
            if (sx >= CANVAS_X && sx < W) buf.writeAt(sx, sy, "\u2554");
            for (int xi = sx + 1; xi < sx + PIECE_W - 1; xi++)
                if (xi >= CANVAS_X && xi < W) buf.writeAt(xi, sy, "\u2550");
            if (sx + PIECE_W - 1 >= CANVAS_X && sx + PIECE_W - 1 < W)
                buf.writeAt(sx + PIECE_W - 1, sy, "\u2557");
        }

        // Row 1: content ║ icon Label param ║
        if (sy + 1 >= HDR && sy + 1 < HDR + CANVAS_H) {
            int cy = sy + 1;
            // Left border
            buf.setTextColor(borderClr);
            buf.setBackgroundColor(15);
            if (sx >= CANVAS_X && sx < W) buf.writeAt(sx, cy, "\u2551");
            // Colored interior fill
            buf.setBackgroundColor(fillClr);
            buf.setTextColor(textClr);
            for (int xi = sx + 1; xi < sx + PIECE_W - 1; xi++)
                if (xi >= CANVAS_X && xi < W) buf.writeAt(xi, cy, " ");
            // Icon + label
            String icon = catIcon(p.type.category);
            String label = p.displayLabel();
            String content = " " + icon + " " + label;
            content = clip(content, PIECE_W - 2);
            for (int i = 0; i < content.length(); i++) {
                int xi = sx + 1 + i;
                if (xi >= CANVAS_X && xi < W)
                    buf.writeAt(xi, cy, String.valueOf(content.charAt(i)));
            }
            // Right border
            buf.setTextColor(borderClr);
            buf.setBackgroundColor(15);
            if (sx + PIECE_W - 1 >= CANVAS_X && sx + PIECE_W - 1 < W)
                buf.writeAt(sx + PIECE_W - 1, cy, "\u2551");
        }

        // Row 2: bottom border ╚═══...═══╝
        if (sy + 2 >= HDR && sy + 2 < HDR + CANVAS_H) {
            buf.setTextColor(borderClr);
            buf.setBackgroundColor(15);
            if (sx >= CANVAS_X && sx < W) buf.writeAt(sx, sy + 2, "\u255A");
            for (int xi = sx + 1; xi < sx + PIECE_W - 1; xi++)
                if (xi >= CANVAS_X && xi < W) buf.writeAt(xi, sy + 2, "\u2550");
            if (sx + PIECE_W - 1 >= CANVAS_X && sx + PIECE_W - 1 < W)
                buf.writeAt(sx + PIECE_W - 1, sy + 2, "\u255D");
            // Output connector marker (small triangle below bottom center)
            if (p.type.hasOutput && p.outputConnection != null) {
                int connX = sx + PIECE_W / 2;
                if (connX >= CANVAS_X && connX < W)
                    buf.writeAt(connX, sy + 2, "\u2566"); // ╦ as output tab
            }
        }

        // Selection glow: bright markers around the selected piece
        if (sel) {
            buf.setTextColor(4);
            buf.setBackgroundColor(15);
            // Left indicator
            if (sx - 1 >= CANVAS_X && sy + 1 >= HDR && sy + 1 < HDR + CANVAS_H)
                buf.writeAt(sx - 1, sy + 1, "\u25B8");
            // Right indicator
            if (sx + PIECE_W >= CANVAS_X && sx + PIECE_W < W && sy + 1 >= HDR && sy + 1 < HDR + CANVAS_H)
                buf.writeAt(sx + PIECE_W, sy + 1, "\u25C2");
        }
    }

    // ── Footer ──────────────────────────────────────────────

    private void renderFooter(TerminalBuffer buf) {
        buf.setTextColor(0);
        buf.setBackgroundColor(8);
        buf.hLine(0, TerminalBuffer.WIDTH - 1, TerminalBuffer.HEIGHT - 1, ' ');
        String foot;
        if (statusTicks > 0) {
            buf.setTextColor(5);
            foot = " " + statusMsg;
        } else if (selectedPiece != null && (selectedPiece.type == PieceType.GOTO_XYZ
                || selectedPiece.type == PieceType.GPS_SET)) {
            foot = " Tab:\u2194 Enter:Edit Del:Rm F2:Save F5:" + lang.name() + " F6:GPS";
        } else {
            foot = " Tab:\u2194 Enter:Edit Del:Rm F2:Save F4:Tgt F5:" + lang.name();
        }
        buf.writeAt(0, TerminalBuffer.HEIGHT - 1, clip(foot, TerminalBuffer.WIDTH));
    }

    // ── PNC-style param edit overlay ────────────────────────

    private void renderParamOverlay(TerminalBuffer buf) {
        int bx = 18, by = 7, bw = 30, bh = 8;
        // Shadow
        buf.setBackgroundColor(15);
        buf.setTextColor(7);
        for (int y = by + 1; y <= by + bh; y++)
            buf.fillRect(bx + 1, y, bx + bw, y, ' ');
        // Panel background
        buf.setBackgroundColor(15);
        for (int y = by; y < by + bh; y++)
            buf.fillRect(bx, y, bx + bw - 1, y, ' ');
        // Title bar
        buf.setTextColor(9);
        buf.writeAt(bx, by, "\u2554");
        for (int x = bx + 1; x < bx + bw - 1; x++) buf.writeAt(x, by, "\u2550");
        buf.writeAt(bx + bw - 1, by, "\u2557");
        buf.setBackgroundColor(11);
        buf.setTextColor(0);
        buf.fillRect(bx + 1, by + 1, bx + bw - 2, by + 1, ' ');
        String title = editingPiece != null ? " Edit: " + editingPiece.type.label : " Edit Parameter";
        buf.writeAt(bx + 1, by + 1, clip(title, bw - 2));
        // Separator
        buf.setBackgroundColor(15);
        buf.setTextColor(9);
        buf.writeAt(bx, by + 2, "\u2560");
        for (int x = bx + 1; x < bx + bw - 1; x++) buf.writeAt(x, by + 2, "\u2550");
        buf.writeAt(bx + bw - 1, by + 2, "\u2563");
        // Sides
        for (int y = by + 1; y < by + bh - 1; y++) {
            buf.writeAt(bx, y, "\u2551");
            buf.writeAt(bx + bw - 1, y, "\u2551");
        }
        // Input area
        buf.setTextColor(7);
        buf.writeAt(bx + 2, by + 3, "Value:");
        buf.setBackgroundColor(7);
        buf.setTextColor(15);
        buf.fillRect(bx + 9, by + 3, bx + bw - 3, by + 3, ' ');
        buf.setTextColor(0);
        buf.writeAt(bx + 9, by + 3, clip(paramInput.toString() + "_", bw - 12));
        // Hints
        buf.setBackgroundColor(15);
        buf.setTextColor(8);
        buf.writeAt(bx + 2, by + 5, "Enter\u25B6 OK   Esc\u25B6 Cancel");
        // Bottom border
        buf.setTextColor(9);
        buf.writeAt(bx, by + bh - 1, "\u255A");
        for (int x = bx + 1; x < bx + bw - 1; x++) buf.writeAt(x, by + bh - 1, "\u2550");
        buf.writeAt(bx + bw - 1, by + bh - 1, "\u255D");
    }

    // ── PNC-style GPS picker overlay ────────────────────────

    private void renderGpsOverlay(TerminalBuffer buf) {
        int bx = 14, by = 4, bw = 40, bh = 12;
        // Shadow
        buf.setBackgroundColor(15);
        buf.setTextColor(7);
        for (int y = by + 1; y <= by + bh; y++)
            buf.fillRect(bx + 1, y, bx + bw, y, ' ');
        // Panel
        buf.setBackgroundColor(15);
        for (int y = by; y < by + bh; y++)
            buf.fillRect(bx, y, bx + bw - 1, y, ' ');
        // Title bar
        buf.setTextColor(3);
        buf.writeAt(bx, by, "\u2554");
        for (int x = bx + 1; x < bx + bw - 1; x++) buf.writeAt(x, by, "\u2550");
        buf.writeAt(bx + bw - 1, by, "\u2557");
        buf.setBackgroundColor(3);
        buf.setTextColor(0);
        buf.fillRect(bx + 1, by + 1, bx + bw - 2, by + 1, ' ');
        buf.writeAt(bx + 2, by + 1, "\u2302 GPS Location Tool");
        // Separator
        buf.setBackgroundColor(15);
        buf.setTextColor(3);
        buf.writeAt(bx, by + 2, "\u2560");
        for (int x = bx + 1; x < bx + bw - 1; x++) buf.writeAt(x, by + 2, "\u2550");
        buf.writeAt(bx + bw - 1, by + 2, "\u2563");
        // Sides
        for (int y = by + 1; y < by + bh - 1; y++) {
            buf.writeAt(bx, y, "\u2551");
            buf.writeAt(bx + bw - 1, y, "\u2551");
        }
        // Content
        buf.setTextColor(0);
        buf.writeAt(bx + 2, by + 3, "Enter coordinates (X,Y,Z):");
        buf.setTextColor(7);
        buf.writeAt(bx + 2, by + 4, "Format: x,y,z (e.g. 100,64,-50)");
        // Input field
        buf.setBackgroundColor(7);
        buf.setTextColor(15);
        buf.fillRect(bx + 2, by + 6, bx + bw - 3, by + 6, ' ');
        buf.setTextColor(9);
        buf.writeAt(bx + 2, by + 6, clip(gpsInput.toString() + "_", bw - 4));
        // Coordinate preview
        buf.setBackgroundColor(15);
        String[] coords = gpsInput.toString().split(",");
        if (coords.length == 3) {
            buf.setTextColor(5);
            buf.writeAt(bx + 2, by + 8, "X:" + coords[0].trim()
                    + "  Y:" + coords[1].trim() + "  Z:" + coords[2].trim());
        } else {
            buf.setTextColor(14);
            buf.writeAt(bx + 2, by + 8, "Need 3 values: X,Y,Z");
        }
        // Hints
        buf.setTextColor(8);
        buf.writeAt(bx + 2, by + 9, "Enter\u25B6 Set   Esc\u25B6 Cancel");
        // Bottom border
        buf.setTextColor(3);
        buf.writeAt(bx, by + bh - 1, "\u255A");
        for (int x = bx + 1; x < bx + bw - 1; x++) buf.writeAt(x, by + bh - 1, "\u2550");
        buf.writeAt(bx + bw - 1, by + bh - 1, "\u255D");
    }

    // ── Helpers ─────────────────────────────────────────────

    private void setStatus(String msg) {
        statusMsg = msg;
        statusTicks = 60;
    }

    private String shortPath(String p) {
        if (p == null) return "untitled";
        return p.length() <= 18 ? p : "..." + p.substring(p.length() - 15);
    }

    private String clip(String s, int max) {
        return max <= 0 ? "" : (s.length() <= max ? s : s.substring(0, max));
    }
}

package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.PixelBuffer;
import com.apocscode.byteblock.computer.TerminalBuffer;
import com.apocscode.byteblock.computer.BitmapFont;

/**
 * Calculator program for ByteOS. Standard 4-function calculator with display and button grid.
 */
public class CalculatorProgram extends OSProgram {

    private final StringBuilder display = new StringBuilder("0");
    private double accumulator;
    private String pendingOp = "";
    private boolean clearOnNext;

    // Button layout (5 rows x 4 cols)
    private static final String[][] BUTTONS = {
        {"C",  "CE", "%",  "/"},
        {"7",  "8",  "9",  "*"},
        {"4",  "5",  "6",  "-"},
        {"1",  "2",  "3",  "+"},
        {"0",  ".",  "+/-","="}
    };

    private static final int BTN_COLS = 4, BTN_ROWS = 5;
    private static final int PAD = 8;
    private static final int BTN_W = 44;
    private static final int BTN_H = 28;
    private static final int BTN_GAP = 4;
    private static final int DISPLAY_H = 40;

    public CalculatorProgram() {
        super("Calculator");
    }

    @Override
    public void init(JavaOS os) {
        this.os = os;
    }

    @Override
    public boolean tick() { return running; }

    @Override
    public void handleEvent(OSEvent event) {
        switch (event.getType()) {
            case MOUSE_CLICK -> {} // Ignore — handled via MOUSE_CLICK_PX
            case MOUSE_CLICK_PX -> handleClickPx(event.getInt(1), event.getInt(2));
            case CHAR -> handleChar(event.getString(0).charAt(0));
            case KEY -> {
                if (event.getInt(0) == 257 || event.getInt(0) == 335) pressButton("=");
                else if (event.getInt(0) == 259) pressButton("CE");
                else if (event.getInt(0) == 256) running = false;
            }
            default -> {}
        }
    }

    private void handleChar(char c) {
        if (c >= '0' && c <= '9') pressButton(String.valueOf(c));
        else if (c == '.') pressButton(".");
        else if (c == '+') pressButton("+");
        else if (c == '-') pressButton("-");
        else if (c == '*') pressButton("*");
        else if (c == '/') pressButton("/");
        else if (c == '%') pressButton("%");
        else if (c == '=' || c == '\r' || c == '\n') pressButton("=");
    }

    private void handleClickPx(int px, int py) {
        int startX = PAD, startY = PAD + DISPLAY_H + 8;
        for (int r = 0; r < BTN_ROWS; r++) {
            for (int c = 0; c < BTN_COLS; c++) {
                int bx = startX + c * (BTN_W + BTN_GAP);
                int by = startY + r * (BTN_H + BTN_GAP);
                if (px >= bx && px < bx + BTN_W && py >= by && py < by + BTN_H) {
                    pressButton(BUTTONS[r][c]);
                    return;
                }
            }
        }
    }

    private void pressButton(String btn) {
        switch (btn) {
            case "C" -> { display.setLength(0); display.append("0"); accumulator = 0; pendingOp = ""; clearOnNext = false; }
            case "CE" -> { display.setLength(0); display.append("0"); clearOnNext = false; }
            case "+/-" -> {
                if (!display.toString().equals("0")) {
                    if (display.charAt(0) == '-') display.deleteCharAt(0);
                    else display.insert(0, '-');
                }
            }
            case "." -> {
                if (clearOnNext) { display.setLength(0); display.append("0"); clearOnNext = false; }
                if (display.indexOf(".") < 0) display.append('.');
            }
            case "+", "-", "*", "/" -> {
                evaluate();
                pendingOp = btn;
                clearOnNext = true;
            }
            case "%" -> {
                try {
                    double val = Double.parseDouble(display.toString());
                    val = accumulator * val / 100.0;
                    display.setLength(0);
                    display.append(formatResult(val));
                } catch (NumberFormatException ignored) {}
            }
            case "=" -> {
                evaluate();
                pendingOp = "";
                clearOnNext = true;
            }
            default -> {
                // Digit
                if (clearOnNext) { display.setLength(0); clearOnNext = false; }
                if (display.toString().equals("0")) display.setLength(0);
                if (display.length() < 15) display.append(btn);
            }
        }
    }

    private void evaluate() {
        try {
            double current = Double.parseDouble(display.toString());
            double result = switch (pendingOp) {
                case "+" -> accumulator + current;
                case "-" -> accumulator - current;
                case "*" -> accumulator * current;
                case "/" -> current != 0 ? accumulator / current : Double.NaN;
                default -> current;
            };
            accumulator = result;
            display.setLength(0);
            display.append(Double.isNaN(result) ? "Error" : formatResult(result));
        } catch (NumberFormatException e) {
            display.setLength(0);
            display.append("Error");
        }
    }

    private static String formatResult(double val) {
        if (val == (long) val && Math.abs(val) < 1e15) return String.valueOf((long) val);
        String s = String.format("%.8g", val);
        // Trim trailing zeros after decimal point
        if (s.contains(".") && !s.contains("e") && !s.contains("E")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }

    @Override
    public void render(TerminalBuffer buf) {
        buf.setTextColor(0);
        buf.setBackgroundColor(7);
        buf.clear();
        buf.writeAt(1, 1, "Calculator");
        buf.setBackgroundColor(15);
        buf.setTextColor(0);
        String disp = display.toString();
        buf.writeAt(TerminalBuffer.WIDTH - disp.length() - 2, 3, disp);
    }

    @Override
    public void renderGraphics(PixelBuffer pb) {
        setCursorInfo(0, 0, false);
        // Canvas width matches the actual window: PAD + 4 cols * (BTN_W + BTN_GAP) = 200px
        int W = PAD + BTN_COLS * (BTN_W + BTN_GAP);

        // Background
        pb.clear(0xFF2C2C3E);

        // Display panel
        pb.fillRoundRect(PAD, PAD, W - PAD * 2, DISPLAY_H, 3, 0xFF1A1A28);
        pb.drawRect(PAD, PAD, W - PAD * 2, DISPLAY_H, 0xFF444466);
        // Display text (right-aligned)
        String disp = display.toString();
        if (disp.length() > 18) disp = disp.substring(0, 18);
        pb.drawStringRight(W - PAD - 8, PAD + 14, disp, 0xFFEEEEFF);
        // Pending operation indicator
        if (!pendingOp.isEmpty()) {
            pb.drawString(PAD + 4, PAD + 4, pendingOp, 0xFF8888AA);
        }

        // Button grid
        int startX = PAD, startY = PAD + DISPLAY_H + 8;
        for (int r = 0; r < BTN_ROWS; r++) {
            for (int c = 0; c < BTN_COLS; c++) {
                int bx = startX + c * (BTN_W + BTN_GAP);
                int by = startY + r * (BTN_H + BTN_GAP);
                String label = BUTTONS[r][c];

                int bg, fg;
                if (label.equals("=")) {
                    bg = 0xFF2266CC; fg = 0xFFFFFFFF;
                } else if ("+-*/".contains(label) || label.equals("%")) {
                    bg = 0xFF444466; fg = 0xFFCCDDFF;
                } else if (label.equals("C") || label.equals("CE")) {
                    bg = 0xFF663333; fg = 0xFFFFCCCC;
                } else {
                    bg = 0xFF3A3A50; fg = 0xFFEEEEEE;
                }

                pb.fillRoundRect(bx, by, BTN_W, BTN_H, 3, bg);
                pb.drawRect(bx, by, BTN_W, BTN_H, 0xFF555577);
                // Highlight edge
                pb.drawHLine(bx + 1, bx + BTN_W - 2, by + 1, 0x20FFFFFF | (bg & 0xFF000000));

                pb.drawStringCentered(bx, BTN_W, by + 7, label, fg);
            }
        }
    }
}

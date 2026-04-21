package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.PixelBuffer;
import com.apocscode.byteblock.computer.TerminalBuffer;
import com.apocscode.byteblock.computer.peripheral.CreatePeripheralAdapter;
import com.apocscode.byteblock.computer.peripheral.CreatePeripheralAdapter.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Create Mod Dashboard — built-in machine monitor for Create block entities.
 *
 * <p>Scans all 6 adjacent sides for Create peripherals and presents them in
 * a real-time dashboard on the 640×400 canvas. Refreshes every 20 ticks (1 s)
 * for a live gauge feel. Organises machines into two categories:</p>
 * <ul>
 *   <li><b>Kinetics</b>  — speedometers (RPM gauge) and stressometers (load bar)</li>
 *   <li><b>Machines</b>  — tanks, bearings, deployers, mechanical presses, millstones</li>
 * </ul>
 */
public class CreateDashboardProgram extends OSProgram {

    // ── Colour palette — steampunk amber/copper theme ─────────────────────
    private static final int C_BG         = 0xFF0F0A05;
    private static final int C_PANEL      = 0xFF1A1208;
    private static final int C_BORDER     = 0xFF8B5E1A;
    private static final int C_HEADER     = 0xFF2A1A08;
    private static final int C_TITLE      = 0xFFFFCC66;
    private static final int C_TEXT       = 0xFFDDC090;
    private static final int C_DIM        = 0xFF887755;
    private static final int C_GOOD       = 0xFF88FF66;
    private static final int C_WARN       = 0xFFFFCC44;
    private static final int C_BAD        = 0xFFFF4444;
    private static final int C_RPM_BAR    = 0xFFFFAA22;
    private static final int C_STRESS_OK  = 0xFF44CC44;
    private static final int C_STRESS_BAD = 0xFFFF3333;
    private static final int C_FLUID_BAR  = 0xFF4488FF;
    private static final int C_RUNNING    = 0xFF44FF88;
    private static final int C_STOPPED    = 0xFF555555;

    // ── Layout ────────────────────────────────────────────────────────────
    private static final int W         = PixelBuffer.SCREEN_W; // 640
    private static final int H         = PixelBuffer.SCREEN_H; // 400
    private static final int HEADER_H  = 24;
    private static final int FOOTER_H  = 14;
    private static final int CONTENT_Y = HEADER_H;
    private static final int CONTENT_H = H - HEADER_H - FOOTER_H;
    private static final int COL_W     = W / 2;  // two columns

    // ── Per-machine data snapshot ─────────────────────────────────────────
    private record MachineEntry(String type, BlockEntity be, Direction side) {}

    // ── State ─────────────────────────────────────────────────────────────
    private JavaOS os;
    private long lastRefresh = -1;
    private String statusMsg = "Searching for Create machines...";

    /** All adjacent Create machines, keyed by side. */
    private final List<MachineEntry> machines = new ArrayList<>();

    // ── Cached data snapshots (refreshed each tick cycle) ─────────────────
    private final Map<Direction, Object> snapshots = new LinkedHashMap<>();

    public CreateDashboardProgram() { super("Create Dashboard"); }

    @Override
    public void init(JavaOS os) {
        this.os = os;
        refresh();
    }

    @Override
    public boolean tick() {
        long tick = os.getTickCount();
        if (tick - lastRefresh >= 20 || lastRefresh < 0) {
            refresh();
            lastRefresh = tick;
        }
        return running;
    }

    private void refresh() {
        if (!ModList.get().isLoaded("create")) {
            statusMsg = "Create mod not installed.";
            machines.clear();
            return;
        }
        Level level = os.getLevel();
        BlockPos pos = os.getBlockPos();
        if (level == null || pos == null) { statusMsg = "No world context."; return; }

        machines.clear();
        snapshots.clear();

        for (Direction dir : Direction.values()) {
            BlockEntity be = level.getBlockEntity(pos.relative(dir));
            if (be == null) continue;
            String type = CreatePeripheralAdapter.getTypeJava(be);
            if (type == null) continue;
            machines.add(new MachineEntry(type, be, dir));
            // Take a snapshot immediately for rendering
            snapshots.put(dir, takeSnapshot(type, be));
        }

        statusMsg = machines.isEmpty()
            ? "No Create machines adjacent. Attach cables/pipes or place machine directly next to computer."
            : null;
    }

    /** Take a type-specific data snapshot for a machine. Returns a typed record or null. */
    private Object takeSnapshot(String type, BlockEntity be) {
        return switch (type) {
            case "create_speedometer"  -> CreatePeripheralAdapter.querySpeedJava(be);
            case "create_stressometer" -> CreatePeripheralAdapter.queryStressJava(be);
            case "create_tank"         -> CreatePeripheralAdapter.queryTankJava(be);
            case "create_bearing"      -> CreatePeripheralAdapter.queryBearingJava(be);
            case "create_deployer"     -> CreatePeripheralAdapter.queryDeployerJava(be);
            case "create_press"        -> CreatePeripheralAdapter.queryPressJava(be);
            case "create_millstone"    -> CreatePeripheralAdapter.queryMillJava(be);
            default -> null;
        };
    }

    // ── Input ─────────────────────────────────────────────────────────────

    /** Not used — this program renders pixel-mode only. */
    @Override
    public void render(TerminalBuffer buffer) {}

    @Override
    public void handleEvent(OSEvent event) {
        // No interactive elements currently — dashboard is read-only
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    @Override
    public void renderGraphics(PixelBuffer pb) {
        pb.fillRect(0, 0, W, H, C_BG);
        drawHeader(pb);
        if (statusMsg != null) {
            pb.drawStringCentered(0, W, H / 2, statusMsg, C_WARN);
            drawFooter(pb);
            return;
        }
        drawMachineGrid(pb);
        drawFooter(pb);
    }

    private void drawHeader(PixelBuffer pb) {
        pb.fillRect(0, 0, W, HEADER_H, C_HEADER);
        pb.drawRect(0, 0, W, HEADER_H, C_BORDER);
        pb.drawString(8, 4, "Create Mod Dashboard", C_TITLE);

        // Machine count & overall health
        long overStressed = machines.stream().filter(m -> isOverstressed(m)).count();
        String health = overStressed > 0
            ? overStressed + " OVERSTRESSED!"
            : machines.size() + " machine(s) connected";
        int healthCol = overStressed > 0 ? C_BAD : C_GOOD;
        pb.drawStringRight(W - 8, 4, health, healthCol);
    }

    private boolean isOverstressed(MachineEntry m) {
        Object snap = snapshots.get(m.side());
        if (snap instanceof SpeedInfo s)  return s.overstressed();
        if (snap instanceof StressInfo s) return s.overstressed();
        return false;
    }

    private void drawFooter(PixelBuffer pb) {
        int y = H - FOOTER_H;
        pb.fillRect(0, y, W, FOOTER_H, C_HEADER);
        pb.drawRect(0, y, W, FOOTER_H, C_BORDER);
        pb.drawString(4, y + 2, "Auto-refresh: 1s  |  Adjacent blocks only", C_DIM);
        pb.drawStringRight(W - 4, y + 2, "Create Dashboard", C_TITLE);
    }

    // ── Machine grid ──────────────────────────────────────────────────────

    private void drawMachineGrid(PixelBuffer pb) {
        // Lay out machines in a 2-column grid
        int cardH = 72;
        int cardW = COL_W - 12;
        int padX  = 8;
        int padY  = 6;
        int col   = 0;
        int row   = 0;
        int maxRows = CONTENT_H / (cardH + padY);

        for (int i = 0; i < machines.size() && row < maxRows; i++) {
            MachineEntry m  = machines.get(i);
            Object        snap = snapshots.get(m.side());
            int cx = padX + col * (COL_W);
            int cy = CONTENT_Y + padY + row * (cardH + padY);

            drawMachineCard(pb, m.type(), m.side(), snap, cx, cy, cardW, cardH);

            col++;
            if (col >= 2) { col = 0; row++; }
        }
    }

    /** Draw a single machine card. */
    private void drawMachineCard(PixelBuffer pb, String type, Direction side,
                                  Object snap, int x, int y, int w, int h) {
        pb.fillRect(x, y, w, h, C_PANEL);
        pb.drawRect(x, y, w, h, C_BORDER);

        String title  = machineTitle(type);
        String sideS  = side.getName().toUpperCase();
        pb.drawString(x + 4, y + 3, title, C_TITLE);
        pb.drawStringRight(x + w - 2, y + 3, sideS, C_DIM);
        pb.drawHLine(x + 1, x + w - 2, y + 13, C_BORDER);

        int dataY = y + 16;

        if (snap == null) {
            pb.drawString(x + 4, dataY + 8, "No data", C_DIM);
            return;
        }

        switch (type) {
            case "create_speedometer"  -> drawSpeedCard(pb,   (SpeedInfo)   snap, x, dataY, w, h - 16);
            case "create_stressometer" -> drawStressCard(pb,  (StressInfo)  snap, x, dataY, w, h - 16);
            case "create_tank"         -> drawTankCard(pb,    (TankInfo)    snap, x, dataY, w, h - 16);
            case "create_bearing"      -> drawBearingCard(pb, (BearingInfo) snap, x, dataY, w, h - 16);
            case "create_deployer"     -> drawDeployerCard(pb,(DeployerInfo)snap, x, dataY, w, h - 16);
            case "create_press"        -> drawPressCard(pb,   (PressInfo)   snap, x, dataY, w, h - 16);
            case "create_millstone"    -> drawMillCard(pb,    (MillInfo)    snap, x, dataY, w, h - 16);
        }
    }

    // ── Speedometer card ──────────────────────────────────────────────────

    private void drawSpeedCard(PixelBuffer pb, SpeedInfo s, int x, int y, int w, int h) {
        // RPM display
        String rpmStr = String.format("%.1f RPM", s.speed());
        pb.drawString(x + 4, y + 2, rpmStr, s.overstressed() ? C_BAD : C_RPM_BAR);

        // Bar gauge (max 256 RPM = full)
        float maxRpm = 256f;
        float pct    = Math.min(1f, Math.abs(s.speed()) / maxRpm);
        int barW = w - 8, barH = 10;
        int filled = (int)(barW * pct);
        int barCol = s.overstressed() ? C_BAD : C_RPM_BAR;
        pb.fillRect(x + 4, y + 16, barW, barH, 0xFF111111);
        pb.fillRect(x + 4, y + 16, filled, barH, barCol);
        pb.drawRect(x + 4, y + 16, barW, barH, C_BORDER);

        String status = s.overstressed() ? "OVERSTRESSED" : (s.speed() == 0f ? "STOPPED" : "RUNNING");
        int col = s.overstressed() ? C_BAD : (s.speed() == 0f ? C_STOPPED : C_RUNNING);
        pb.drawString(x + 4, y + 30, status, col);

        // Direction indicator
        if (s.speed() > 0) pb.drawString(x + w - 20, y + 30, "CW",  C_DIM);
        if (s.speed() < 0) pb.drawString(x + w - 22, y + 30, "CCW", C_DIM);
    }

    // ── Stressometer card ─────────────────────────────────────────────────

    private void drawStressCard(PixelBuffer pb, StressInfo s, int x, int y, int w, int h) {
        float pct    = s.capacity() > 0 ? s.stress() / s.capacity() : 0f;
        int   barW   = w - 8, barH = 12;
        int   filled = (int)(barW * Math.min(1f, pct));
        int   barCol = s.overstressed() ? C_STRESS_BAD : (pct > 0.85f ? C_WARN : C_STRESS_OK);

        pb.fillRect(x + 4, y + 4, barW, barH, 0xFF111111);
        pb.fillRect(x + 4, y + 4, filled, barH, barCol);
        pb.drawRect(x + 4, y + 4, barW, barH, C_BORDER);

        String pctStr = String.format("%.1f%%  %.0f / %.0f SU", pct * 100, s.stress(), s.capacity());
        pb.drawString(x + 4, y + 5, pctStr, C_TITLE);

        String status = s.overstressed() ? "OVERSTRESSED!" : "OK";
        pb.drawString(x + 4, y + 20, "Network Load: " + status, s.overstressed() ? C_BAD : C_GOOD);
    }

    // ── Fluid tank card ───────────────────────────────────────────────────

    private void drawTankCard(PixelBuffer pb, TankInfo t, int x, int y, int w, int h) {
        float pct    = t.capacityMb() > 0 ? (float) t.amountMb() / t.capacityMb() : 0f;
        int   barW   = w - 8, barH = 14;
        int   filled = (int)(barW * pct);
        pb.fillRect(x + 4, y + 2, barW, barH, 0xFF111133);
        pb.fillRect(x + 4, y + 2, filled, barH, C_FLUID_BAR);
        pb.drawRect(x + 4, y + 2, barW, barH, C_BORDER);

        String fluidLabel = trimNamespace(t.fluidName());
        pb.drawString(x + 6, y + 4, fluidLabel, C_TITLE);

        String amtLabel = t.amountMb() + " / " + t.capacityMb() + " mB ("
            + String.format("%.0f%%", pct * 100) + ")";
        pb.drawString(x + 4, y + 20, amtLabel, C_TEXT);

        if (t.amountMb() == 0) {
            pb.drawString(x + 4, y + 32, "EMPTY", C_DIM);
        } else if (t.amountMb() >= t.capacityMb()) {
            pb.drawString(x + 4, y + 32, "FULL", C_GOOD);
        }
    }

    // ── Bearing card ──────────────────────────────────────────────────────

    private void drawBearingCard(PixelBuffer pb, BearingInfo b, int x, int y, int w, int h) {
        String runStr = b.running() ? "ROTATING" : "STOPPED";
        pb.drawString(x + 4, y + 2, runStr, b.running() ? C_RUNNING : C_STOPPED);
        pb.drawString(x + 4, y + 16, String.format("Angle: %.1f°", b.angle()), C_TEXT);
        pb.drawString(x + 4, y + 28, String.format("Speed: %.1f RPM", b.speed()), C_RPM_BAR);

        // Simple angle needle indicator
        drawAngleIndicator(pb, x + w - 30, y + 2, 24, b.angle());
    }

    private void drawAngleIndicator(PixelBuffer pb, int cx, int cy, int r, float angleDeg) {
        pb.fillRect(cx - r, cy, r * 2, r * 2, 0xFF111111);
        pb.drawRect(cx - r, cy, r * 2, r * 2, C_BORDER);
        double rad = Math.toRadians(angleDeg - 90);
        int nx = (int)(Math.cos(rad) * (r - 3));
        int ny = (int)(Math.sin(rad) * (r - 3));
        pb.drawLine(cx, cy + r, cx + nx, cy + r + ny, C_RPM_BAR);
    }

    // ── Deployer card ─────────────────────────────────────────────────────

    private void drawDeployerCard(PixelBuffer pb, DeployerInfo d, int x, int y, int w, int h) {
        String state = formatEnum(d.state());
        String mode  = formatEnum(d.mode());
        boolean active = state.equals("EXPANDING") || state.equals("RETRACTING");
        pb.drawString(x + 4, y + 2, "State: " + state, active ? C_RUNNING : C_TEXT);
        pb.drawString(x + 4, y + 14, "Mode:  " + mode, C_TEXT);
        String held = d.heldItem() + (d.heldCount() > 1 ? " x" + d.heldCount() : "");
        String heldLabel = held.length() > 22 ? held.substring(0, 21) + "~" : held;
        pb.drawString(x + 4, y + 26, "Held:  " + heldLabel, C_TEXT);
    }

    // ── Press card ────────────────────────────────────────────────────────

    private void drawPressCard(PixelBuffer pb, PressInfo p, int x, int y, int w, int h) {
        pb.drawString(x + 4, y + 2, p.running() ? "PRESSING" : "IDLE",
            p.running() ? C_RUNNING : C_STOPPED);

        // Progress arc/bar
        int barW = w - 8, barH = 10;
        int fill = (int)(barW * p.progressPct() / 100f);
        pb.fillRect(x + 4, y + 16, barW, barH, 0xFF111111);
        pb.fillRect(x + 4, y + 16, fill, barH, C_RPM_BAR);
        pb.drawRect(x + 4, y + 16, barW, barH, C_BORDER);
        pb.drawString(x + 4, y + 17, String.format("%.0f%%", p.progressPct()), C_TITLE);
    }

    // ── Millstone card ────────────────────────────────────────────────────

    private void drawMillCard(PixelBuffer pb, MillInfo m, int x, int y, int w, int h) {
        pb.drawString(x + 4, y + 2, m.running() ? "GRINDING" : "IDLE",
            m.running() ? C_RUNNING : C_STOPPED);

        int iy = y + 14;
        if (!m.inputs().isEmpty()) {
            String inp = m.inputs().get(0);
            if (inp.length() > 20) inp = inp.substring(0, 19) + "~";
            pb.drawString(x + 4, iy, "In:  " + inp, C_TEXT);
            iy += 12;
        }
        for (int i = 0; i < Math.min(2, m.outputs().size()); i++) {
            String out = m.outputs().get(i);
            if (out.length() > 20) out = out.substring(0, 19) + "~";
            pb.drawString(x + 4, iy, "Out: " + out, C_GOOD);
            iy += 12;
        }
    }

    // ── Util ──────────────────────────────────────────────────────────────

    private static String machineTitle(String type) {
        return switch (type) {
            case "create_speedometer"  -> "Speedometer";
            case "create_stressometer" -> "Stressometer";
            case "create_tank"         -> "Fluid Tank";
            case "create_bearing"      -> "Mech. Bearing";
            case "create_deployer"     -> "Deployer";
            case "create_press"        -> "Mech. Press";
            case "create_millstone"    -> "Millstone";
            default -> type;
        };
    }

    private static String trimNamespace(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    private static String formatEnum(String raw) {
        // e.g. "USE" → "USE", "WAITING" → "WAITING"
        return raw.toUpperCase();
    }
}

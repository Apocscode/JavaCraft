package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.PixelBuffer;
import com.apocscode.byteblock.computer.TerminalBuffer;
import com.apocscode.byteblock.computer.peripheral.AE2PeripheralAdapter;
import com.apocscode.byteblock.computer.peripheral.AE2PeripheralAdapter.AECraftingJob;
import com.apocscode.byteblock.computer.peripheral.AE2PeripheralAdapter.AEEnergyInfo;
import com.apocscode.byteblock.computer.peripheral.AE2PeripheralAdapter.AEFluidEntry;
import com.apocscode.byteblock.computer.peripheral.AE2PeripheralAdapter.AEItemEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ME Network Dashboard — built-in AE2 storage & crafting monitor.
 *
 * <p>Auto-detects an adjacent ME network on any of the 6 sides. Displays
 * four tabs on the 640×400 canvas:
 * <ul>
 *   <li><b>Storage</b>  — scrollable item list with craftable badges &amp; counts</li>
 *   <li><b>Fluids</b>   — fluid list with mB amounts</li>
 *   <li><b>Crafting</b> — active CPU jobs with progress bars</li>
 *   <li><b>Network</b>  — energy gauge, node count, powered status</li>
 * </ul>
 *
 * Refresh rate: every 40 ticks (2 s). Scroll and tab switching respond
 * to mouse click/scroll events forwarded by DesktopProgram.
 */
public class MEDashboardProgram extends OSProgram {

    // ── UI colours (ARGB) ─────────────────────────────────────────────────
    private static final int C_BG          = 0xFF0A0A1A;
    private static final int C_PANEL       = 0xFF0F1428;
    private static final int C_BORDER      = 0xFF2233AA;
    private static final int C_HEADER      = 0xFF1A2060;
    private static final int C_TITLE       = 0xFFAADDFF;
    private static final int C_TEXT        = 0xFFCCCCCC;
    private static final int C_DIM         = 0xFF888888;
    private static final int C_GOOD        = 0xFF44FF88;
    private static final int C_WARN        = 0xFFFFCC44;
    private static final int C_BAD         = 0xFFFF4444;
    private static final int C_CRAFT_BADGE = 0xFF2266FF;
    private static final int C_TAB_ACTIVE  = 0xFF1A3080;
    private static final int C_TAB_IDLE    = 0xFF0D1840;
    private static final int C_ENERGY_BAR  = 0xFF2299FF;
    private static final int C_SEARCH_BG   = 0xFF0C1030;
    private static final int C_HIGHLIGHT   = 0xFF172048;
    private static final int C_FLUID_BAR   = 0xFF44AAFF;
    private static final int C_PROGRESS    = 0xFF44FF66;

    // ── Layout constants ──────────────────────────────────────────────────
    private static final int W  = PixelBuffer.SCREEN_W; // 640
    private static final int H  = PixelBuffer.SCREEN_H; // 400
    private static final int HEADER_H  = 24;
    private static final int TAB_H     = 18;
    private static final int SEARCH_H  = 16;
    private static final int FOOTER_H  = 14;
    private static final int CONTENT_Y = HEADER_H + TAB_H + SEARCH_H;
    private static final int CONTENT_H = H - CONTENT_Y - FOOTER_H;
    private static final int ROW_H     = 13;
    private static final int VISIBLE_ROWS = CONTENT_H / ROW_H;

    private static final String[] TAB_NAMES = { "Storage", "Fluids", "Crafting", "Network" };
    private static final int TAB_W = W / TAB_NAMES.length;

    // ── State ─────────────────────────────────────────────────────────────
    private JavaOS os;
    private int   activeTab   = 0;
    private int   scrollItem  = 0;
    private int   scrollFluid = 0;
    private final StringBuilder searchBuffer = new StringBuilder();

    // ── Data (refreshed every 40 ticks) ───────────────────────────────────
    private BlockEntity         meNode       = null;
    private AEEnergyInfo        energy       = null;
    private List<AEItemEntry>   items        = new ArrayList<>();
    private List<AEFluidEntry>  fluids       = new ArrayList<>();
    private List<AECraftingJob> craftingJobs = new ArrayList<>();
    private int                 nodeCount    = 0;
    private long                lastRefresh  = -1;
    private String              statusMsg    = "Searching for ME Network...";

    public MEDashboardProgram() { super("ME Dashboard"); }

    @Override
    public void init(JavaOS os) {
        this.os = os;
        refresh();
    }

    @Override
    public boolean tick() {
        long tick = os.getTickCount();
        if (tick - lastRefresh >= 40 || lastRefresh < 0) {
            refresh();
            lastRefresh = tick;
        }
        return running;
    }

    private void refresh() {
        if (!ModList.get().isLoaded("ae2")) {
            statusMsg = "Applied Energistics 2 not installed.";
            meNode    = null;
            return;
        }
        Level level = os.getLevel();
        BlockPos pos = os.getBlockPos();
        if (level == null || pos == null) { statusMsg = "No world context."; return; }

        // Scan all 6 sides for an AE2 node
        meNode = null;
        for (Direction dir : Direction.values()) {
            BlockEntity be = level.getBlockEntity(pos.relative(dir));
            if (be != null && AE2PeripheralAdapter.isAvailableJava(be)) {
                meNode = be;
                break;
            }
        }
        if (meNode == null) {
            statusMsg  = "No ME Network detected. Place adjacent to AE2 cable/controller.";
            items      = new ArrayList<>();
            fluids     = new ArrayList<>();
            craftingJobs = new ArrayList<>();
            energy     = null;
            nodeCount  = 0;
            return;
        }
        statusMsg    = null;
        energy       = AE2PeripheralAdapter.queryEnergyJava(meNode);
        nodeCount    = AE2PeripheralAdapter.queryNodeCountJava(meNode);
        craftingJobs = AE2PeripheralAdapter.queryCraftingJobsJava(meNode);

        // Items — sort by count desc
        items = AE2PeripheralAdapter.queryItemsJava(meNode);
        items.sort(Comparator.comparingLong(AEItemEntry::count).reversed());

        // Fluids — sort by amount desc
        fluids = AE2PeripheralAdapter.queryFluidsJava(meNode);
        fluids.sort(Comparator.comparingLong(AEFluidEntry::amountMb).reversed());
    }

    // ── Input handling ─────────────────────────────────────────────────────

    @Override
    public void handleEvent(OSEvent event) {
        switch (event.getType()) {
            case MOUSE_CLICK, MOUSE_CLICK_PX -> {
                int px = event.getInt(1);
                int py = event.getInt(2);
                handleClick(px, py);
            }
            case MOUSE_SCROLL -> {
                int dir = event.getInt(0);
                if (activeTab == 0) {
                    scrollItem = Math.max(0, scrollItem - dir);
                    int max = Math.max(0, filteredItems().size() - VISIBLE_ROWS);
                    scrollItem = Math.min(scrollItem, max);
                } else if (activeTab == 1) {
                    scrollFluid = Math.max(0, scrollFluid - dir);
                    int max = Math.max(0, fluids.size() - VISIBLE_ROWS);
                    scrollFluid = Math.min(scrollFluid, max);
                }
            }
            case CHAR -> {
                if (activeTab == 0 || activeTab == 1) {
                    char c = event.getString(0).charAt(0);
                    searchBuffer.append(c);
                    scrollItem = 0;
                }
            }
            case KEY -> {
                int key = event.getInt(0);
                if (key == 259 || key == 14) { // backspace
                    if (!searchBuffer.isEmpty()) {
                        searchBuffer.deleteCharAt(searchBuffer.length() - 1);
                        scrollItem = 0;
                    }
                } else if (key == 256 || key == 1) { // ESC — clear search
                    searchBuffer.setLength(0);
                    scrollItem = 0;
                }
            }
            default -> {}
        }
    }

    private void handleClick(int px, int py) {
        // Tab bar
        if (py >= HEADER_H && py < HEADER_H + TAB_H) {
            int tab = px / TAB_W;
            if (tab >= 0 && tab < TAB_NAMES.length) {
                activeTab = tab;
                scrollItem = 0;
                scrollFluid = 0;
            }
        }
    }

    private List<AEItemEntry> filteredItems() {
        String q = searchBuffer.toString().toLowerCase();
        if (q.isEmpty()) return items;
        List<AEItemEntry> out = new ArrayList<>();
        for (AEItemEntry e : items) {
            if (e.displayName().toLowerCase().contains(q) || e.name().toLowerCase().contains(q))
                out.add(e);
        }
        return out;
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    /** Not used — this program renders pixel-mode only. */
    @Override
    public void render(TerminalBuffer buffer) {}

    @Override
    public void renderGraphics(PixelBuffer pb) {
        pb.fillRect(0, 0, W, H, C_BG);
        drawHeader(pb);
        drawTabs(pb);
        if (statusMsg != null) {
            drawCenteredMessage(pb, statusMsg, C_WARN);
            drawFooter(pb);
            return;
        }
        drawSearch(pb);
        switch (activeTab) {
            case 0 -> drawStorageTab(pb);
            case 1 -> drawFluidsTab(pb);
            case 2 -> drawCraftingTab(pb);
            case 3 -> drawNetworkTab(pb);
        }
        drawFooter(pb);
    }

    // ── Header ────────────────────────────────────────────────────────────

    private void drawHeader(PixelBuffer pb) {
        pb.fillRect(0, 0, W, HEADER_H, C_HEADER);
        pb.drawRect(0, 0, W, HEADER_H, C_BORDER);
        pb.drawString(8, 4, "ME Network Dashboard", C_TITLE);

        // Energy indicator (top-right)
        if (energy != null) {
            double pct    = energy.capacity() > 0 ? energy.stored() / energy.capacity() : 0;
            int    barW   = 160;
            int    barX   = W - barW - 8;
            int    barY   = 4;
            int    barH   = 10;
            int    filled = (int)(barW * pct);
            int    col    = pct > 0.4 ? C_GOOD : pct > 0.15 ? C_WARN : C_BAD;
            pb.fillRect(barX, barY, barW, barH, 0xFF111122);
            pb.fillRect(barX, barY, filled, barH, col);
            pb.drawRect(barX, barY, barW, barH, C_BORDER);
            String label = String.format("%.0f / %.0f AE", energy.stored(), energy.capacity());
            pb.drawString(barX + 2, barY + 1, label, C_TITLE);

            // Powered dot
            int dotCol = energy.powered() ? C_GOOD : C_BAD;
            pb.fillRect(W - barW - 20, barY + 2, 6, 6, dotCol);
        } else if (meNode == null) {
            pb.drawString(W - 120, 4, "OFFLINE", C_BAD);
        }
    }

    // ── Tabs ──────────────────────────────────────────────────────────────

    private void drawTabs(PixelBuffer pb) {
        int y = HEADER_H;
        for (int i = 0; i < TAB_NAMES.length; i++) {
            int x   = i * TAB_W;
            int col = (i == activeTab) ? C_TAB_ACTIVE : C_TAB_IDLE;
            pb.fillRect(x, y, TAB_W, TAB_H, col);
            pb.drawRect(x, y, TAB_W, TAB_H, C_BORDER);
            pb.drawStringCentered(x, TAB_W, y + 4, TAB_NAMES[i], i == activeTab ? C_TITLE : C_DIM);
        }
    }

    // ── Search bar ────────────────────────────────────────────────────────

    private void drawSearch(PixelBuffer pb) {
        int y = HEADER_H + TAB_H;
        pb.fillRect(0, y, W, SEARCH_H, C_SEARCH_BG);
        pb.drawRect(0, y, W, SEARCH_H, C_BORDER);
        if (activeTab == 0 || activeTab == 1) {
            String q = searchBuffer.toString();
            pb.drawString(4, y + 2, "Search: " + q + "_", C_TEXT);
        } else {
            pb.drawString(4, y + 2, "ME Network Monitor", C_DIM);
        }
    }

    // ── Centred message ────────────────────────────────────────────────────

    private void drawCenteredMessage(PixelBuffer pb, String msg, int color) {
        int midY = HEADER_H + TAB_H + H / 3;
        pb.drawStringCentered(0, W, midY, msg, color);
    }

    // ── Storage tab ───────────────────────────────────────────────────────

    private void drawStorageTab(PixelBuffer pb) {
        List<AEItemEntry> filtered = filteredItems();
        int y = CONTENT_Y;

        // Column headers
        pb.fillRect(0, y, W, ROW_H, C_PANEL);
        pb.drawString( 4, y + 2, "Item", C_DIM);
        pb.drawStringRight(W - 60, y + 2, "Count", C_DIM);
        pb.drawStringRight(W - 4,  y + 2, "Craft", C_DIM);
        y += ROW_H;
        pb.drawHLine(0, W, y, C_BORDER);

        int visStart = scrollItem;
        int visEnd   = Math.min(filtered.size(), visStart + VISIBLE_ROWS - 1);
        for (int i = visStart; i < visEnd; i++) {
            AEItemEntry e   = filtered.get(i);
            int rowY        = y + (i - visStart) * ROW_H;
            int rowBg       = (i % 2 == 0) ? C_PANEL : C_BG;
            pb.fillRect(0, rowY, W, ROW_H, rowBg);

            // Display name (truncate to fit)
            String label = e.displayName();
            int maxChars = 50;
            if (label.length() > maxChars) label = label.substring(0, maxChars - 1) + "~";
            pb.drawString(4, rowY + 2, label, C_TEXT);

            // Count (right-aligned)
            pb.drawStringRight(W - 60, rowY + 2, formatCount(e.count()), C_TITLE);

            // Craftable badge
            if (e.craftable()) {
                pb.fillRect(W - 52, rowY + 2, 48, ROW_H - 4, C_CRAFT_BADGE);
                pb.drawStringCentered(W - 52, 48, rowY + 2, "CRAFT", 0xFFFFFFFF);
            }
        }

        // Scrollbar
        drawScrollbar(pb, CONTENT_Y + ROW_H, CONTENT_H - ROW_H, filtered.size(), VISIBLE_ROWS - 1, scrollItem);

        // Empty state
        if (filtered.isEmpty()) {
            String msg = items.isEmpty() ? "ME Network is empty." : "No items match search.";
            pb.drawStringCentered(0, W, CONTENT_Y + CONTENT_H / 2, msg, C_DIM);
        }
    }

    // ── Fluids tab ────────────────────────────────────────────────────────

    private void drawFluidsTab(PixelBuffer pb) {
        int y = CONTENT_Y;

        pb.fillRect(0, y, W, ROW_H, C_PANEL);
        pb.drawString(4, y + 2, "Fluid", C_DIM);
        pb.drawStringRight(W - 4, y + 2, "Amount (mB)", C_DIM);
        y += ROW_H;
        pb.drawHLine(0, W, y, C_BORDER);

        int visStart = scrollFluid;
        int visEnd   = Math.min(fluids.size(), visStart + VISIBLE_ROWS - 1);
        for (int i = visStart; i < visEnd; i++) {
            AEFluidEntry e = fluids.get(i);
            int rowY       = y + (i - visStart) * ROW_H;
            int rowBg      = (i % 2 == 0) ? C_PANEL : C_BG;
            pb.fillRect(0, rowY, W, ROW_H, rowBg);

            // Fluid fill bar (proportion of max in list)
            long maxAmt = fluids.isEmpty() ? 1 : fluids.get(0).amountMb();
            int barW = maxAmt > 0 ? (int)((W - 150) * e.amountMb() / maxAmt) : 0;
            pb.fillRect(4, rowY + 2, barW, ROW_H - 4, 0xFF114466);

            // Fluid name (trim namespace prefix for readability)
            String name = trimNamespace(e.name());
            pb.drawString(8, rowY + 2, name, C_FLUID_BAR);

            // Amount right-aligned
            pb.drawStringRight(W - 4, rowY + 2, formatCount(e.amountMb()) + " mB", C_TITLE);
        }
        drawScrollbar(pb, CONTENT_Y + ROW_H, CONTENT_H - ROW_H, fluids.size(), VISIBLE_ROWS - 1, scrollFluid);
        if (fluids.isEmpty()) {
            pb.drawStringCentered(0, W, CONTENT_Y + CONTENT_H / 2, "No fluids in ME network.", C_DIM);
        }
    }

    // ── Crafting tab ──────────────────────────────────────────────────────

    private void drawCraftingTab(PixelBuffer pb) {
        int y = CONTENT_Y;

        if (craftingJobs.isEmpty()) {
            pb.drawStringCentered(0, W, y + CONTENT_H / 2, "No active crafting jobs.", C_DIM);
            return;
        }

        pb.fillRect(0, y, W, ROW_H, C_PANEL);
        pb.drawString(4,       y + 2, "Item",     C_DIM);
        pb.drawString(250,     y + 2, "CPU",      C_DIM);
        pb.drawString(380,     y + 2, "Progress", C_DIM);
        pb.drawStringRight(W - 4, y + 2, "Elapsed", C_DIM);
        y += ROW_H;
        pb.drawHLine(0, W, y, C_BORDER);

        int jobH = 30;
        for (int i = 0; i < craftingJobs.size() && y + jobH < H - FOOTER_H; i++) {
            AECraftingJob job = craftingJobs.get(i);
            int rowBg = (i % 2 == 0) ? C_PANEL : C_BG;
            pb.fillRect(0, y, W, jobH, rowBg);

            // Item name
            pb.drawString(4, y + 3, job.itemName(), C_TEXT);
            // CPU name
            pb.drawString(250, y + 3, job.cpuName(), C_DIM);

            // Progress bar
            double pct   = job.totalItems() > 0 ? (double) job.doneItems() / job.totalItems() : 0;
            int barX = 380, barW = 200, barH = 10;
            pb.fillRect(barX, y + 3, barW, barH, 0xFF111111);
            pb.fillRect(barX, y + 3, (int)(barW * pct), barH, C_PROGRESS);
            pb.drawRect(barX, y + 3, barW, barH, C_BORDER);
            String pctLabel = String.format("%.0f%%  %d/%d", pct * 100, job.doneItems(), job.totalItems());
            pb.drawString(barX + 2, y + 4, pctLabel, C_TITLE);

            // Elapsed time
            long secs = job.elapsedNanos() / 1_000_000_000L;
            String elapsed = secs < 60 ? secs + "s" : (secs / 60) + "m " + (secs % 60) + "s";
            pb.drawStringRight(W - 4, y + 3, elapsed, C_DIM);

            y += jobH;
        }
    }

    // ── Network tab ───────────────────────────────────────────────────────

    private void drawNetworkTab(PixelBuffer pb) {
        int y = CONTENT_Y + 8;

        // ── Energy section ───────────────────────────────────────────────
        pb.fillRect(8, y, W - 16, 80, C_PANEL);
        pb.drawRect(8, y, W - 16, 80, C_BORDER);
        pb.drawString(16, y + 4, "Energy", C_TITLE);

        if (energy != null) {
            double pct    = energy.capacity() > 0 ? energy.stored() / energy.capacity() : 0;
            int barX = 16, barY = y + 20, barW = W - 32, barH = 18;
            int filled    = (int)(barW * pct);
            int barCol    = pct > 0.4 ? C_ENERGY_BAR : pct > 0.15 ? C_WARN : C_BAD;
            pb.fillRect(barX, barY, barW, barH, 0xFF111122);
            pb.fillRect(barX, barY, filled, barH, barCol);
            pb.drawRect(barX, barY, barW, barH, C_BORDER);

            String stored = String.format("%.1f AE", energy.stored());
            String cap    = String.format("%.1f AE", energy.capacity());
            pb.drawStringCentered(barX, barW, barY + 2, stored + " / " + cap, C_TITLE);

            pb.drawString(16, y + 44, String.format("Avg Usage:    %.2f AE/t", energy.avgUsage()),    C_TEXT);
            pb.drawString(16, y + 56, String.format("Avg Injection: %.2f AE/t", energy.avgInjection()), C_TEXT);

            int powX = W - 120, powY = y + 4;
            String powLabel = energy.powered() ? "POWERED" : "OFFLINE";
            int    powCol   = energy.powered() ? C_GOOD : C_BAD;
            pb.fillRect(powX - 4, powY - 2, 100, 14, 0xFF111122);
            pb.drawString(powX, powY, powLabel, powCol);
        } else {
            pb.drawString(16, y + 28, "Energy data unavailable.", C_DIM);
        }

        // ── Network stats ────────────────────────────────────────────────
        y += 96;
        pb.fillRect(8, y, W / 2 - 12, 60, C_PANEL);
        pb.drawRect(8, y, W / 2 - 12, 60, C_BORDER);
        pb.drawString(16, y + 4, "Network", C_TITLE);
        pb.drawString(16, y + 20, "Grid Nodes: " + nodeCount,       C_TEXT);
        pb.drawString(16, y + 32, "Item Types: " + items.size(),    C_TEXT);
        pb.drawString(16, y + 44, "Fluid Types: " + fluids.size(),  C_TEXT);

        // ── Storage summary ───────────────────────────────────────────────
        int sx = W / 2 + 4;
        pb.fillRect(sx, y, W / 2 - 12, 60, C_PANEL);
        pb.drawRect(sx, y, W / 2 - 12, 60, C_BORDER);
        pb.drawString(sx + 8, y + 4, "Storage", C_TITLE);
        long totalItems = items.stream().mapToLong(AEItemEntry::count).sum();
        long totalFluids = fluids.stream().mapToLong(AEFluidEntry::amountMb).sum();
        pb.drawString(sx + 8, y + 20, "Total Items:  " + formatCount(totalItems), C_TEXT);
        pb.drawString(sx + 8, y + 32, "Total Fluids: " + formatCount(totalFluids) + " mB", C_TEXT);
        pb.drawString(sx + 8, y + 44, "Crafting Jobs: " + craftingJobs.size(),    C_TEXT);

        // ── Active crafting badge ─────────────────────────────────────────
        y += 72;
        if (!craftingJobs.isEmpty()) {
            pb.fillRect(8, y, W - 16, 14, 0xFF0A2A0A);
            pb.drawRect(8, y, W - 16, 14, C_GOOD);
            pb.drawStringCentered(8, W - 16, y + 2,
                craftingJobs.size() + " active crafting job(s) — see Crafting tab", C_GOOD);
        }
    }

    // ── Footer ────────────────────────────────────────────────────────────

    private void drawFooter(PixelBuffer pb) {
        int y = H - FOOTER_H;
        pb.fillRect(0, y, W, FOOTER_H, C_HEADER);
        pb.drawRect(0, y, W, FOOTER_H, C_BORDER);
        String info = meNode != null
            ? "Connected  |  " + items.size() + " item types  |  Refresh: 2s"
            : "Disconnected";
        pb.drawString(4, y + 2, info, C_DIM);
        pb.drawStringRight(W - 4, y + 2, "ME Dashboard", C_TITLE);
    }

    // ── Scrollbar helper ──────────────────────────────────────────────────

    private void drawScrollbar(PixelBuffer pb, int y, int h, int total, int visible, int scroll) {
        if (total <= visible) return;
        int sbW  = 4;
        int sbX  = W - sbW - 1;
        pb.fillRect(sbX, y, sbW, h, 0xFF111122);
        float thumbH   = Math.max(16f, (float) visible / total * h);
        float thumbTop = (float) scroll / total * h;
        pb.fillRect(sbX, y + (int) thumbTop, sbW, (int) thumbH, C_BORDER);
    }

    // ── Util ──────────────────────────────────────────────────────────────

    private static String formatCount(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fk", n / 1_000.0);
        return Long.toString(n);
    }

    private static String trimNamespace(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }
}

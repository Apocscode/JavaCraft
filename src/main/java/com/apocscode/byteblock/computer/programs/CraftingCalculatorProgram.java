package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.block.entity.PrinterBlockEntity;
import com.apocscode.byteblock.computer.BitmapFont;
import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.PixelBuffer;
import com.apocscode.byteblock.computer.TerminalBuffer;
import com.apocscode.byteblock.computer.storage.StorageScanner;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.network.Filterable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Materials Calculator — search any registered item or block (all mods included)
 * and recursively resolve all raw crafting materials needed to produce it.
 *
 * Features:
 *  - Live search across the entire item registry (all namespaces/mods)
 *  - Recursive recipe decomposition down to base materials
 *  - Craft-count stepper (1–999)
 *  - Print output to an adjacent Printer block
 *  - Copy output to system clipboard
 */
public class CraftingCalculatorProgram extends OSProgram {

    // ── Layout ───────────────────────────────────────────────────────────────
    // Window opens at (0,0,80,23): bodyW=640, bodyH=23*16=368
    private static final int PW     = PixelBuffer.SCREEN_W;            // 640
    private static final int PH     = 23 * PixelBuffer.CELL_H;         // 368
    private static final int HDR_H  = 18;
    private static final int SRH_H  = 22;
    private static final int BTM_H  = 28;
    private static final int LIST_W = 256;
    private static final int LIST_Y = HDR_H + SRH_H;          // 40
    private static final int LIST_H = PH - LIST_Y - BTM_H;    // 332
    private static final int MAT_X  = LIST_W + 1;             // 257
    private static final int MAT_W  = PW - MAT_X;             // 383
    private static final int ROW_H  = 18;
    private static final int CW     = BitmapFont.CHAR_W;       // 8
    private static final int CH     = BitmapFont.CHAR_H;       // 16

    // ── Colors ───────────────────────────────────────────────────────────────
    private static final int C_BG    = 0xFF1A1A2E;
    private static final int C_PANEL = 0xFF16213E;
    private static final int C_HDR   = 0xFF0F3460;
    private static final int C_SEP   = 0xFF2A2A4A;
    private static final int C_SRCH  = 0xFF1C1C3A;
    private static final int C_SEL   = 0xFF533483;
    private static final int C_TEXT  = 0xFFE0E0FF;
    private static final int C_MUTED = 0xFF7878A0;
    private static final int C_ACNT  = 0xFF7B68EE;
    private static final int C_GRN   = 0xFF50D090;
    private static final int C_YEL   = 0xFFFFD060;
    private static final int C_RED   = 0xFFFF6060;
    private static final int C_BTN   = 0xFF2A4A8A;
    private static final int C_BTNH  = 0xFF3A6ABA;
    private static final int C_SUBHD  = 0xFF1E1E3F;
    private static final int C_SRCH_FOC = 0xFF23234E;
    private static final int C_DROP_BG  = 0xFF18183A;
    private static final int C_DROP_SEL = 0xFF2C2C6A;
    private static final int C_DROP_DIV = 0xFF3A3A5A;

    // ── Dropdown constants ────────────────────────────────────────────────────
    private static final String[] DROPDOWN_ITEMS = {
        "Save", "Save As", "Save to Disk", null,
        "Print", "Book", "Clipboard", "Mod Clipboard"
    };
    private static final int DROP_W    = 160;
    private static final int DROP_ROW  = 18;
    private static final int BTN_OUT_X = 4;
    private static final int BTN_OUT_W = 90;
    private static final int BTN_SCAN_X = BTN_OUT_X + BTN_OUT_W + 6; // 100
    private static final int BTN_SCAN_W = 80;
    private static final int HINT_X     = BTN_SCAN_X + BTN_SCAN_W + 10;

    // ── Static caches (shared across instances) ──────────────────────────────
    /** Full sorted list of every registered item ID (built once). */
    private static List<String> ALL_IDS;
    /** Display-name cache: registry ID → translated name. */
    private static final Map<String, String> NAME_CACHE = new HashMap<>();

    // ── State ────────────────────────────────────────────────────────────────
    private final StringBuilder searchQuery = new StringBuilder();
    private int searchCursor;

    private List<String> results = new ArrayList<>();
    private int listScroll;
    private int selectedIndex = -1;
    private String selectedId;

    private int craftCount = 1;
    private Map<String, Long> materials = new LinkedHashMap<>();
    private boolean hasRecipe;
    private int matScroll;
    private boolean searchFocused = false;
    private boolean dropdownOpen  = false;

    private String statusMsg  = "";
    private int    statusColor = C_TEXT;
    private int    statusTicks;

    // ── Storage scan state ────────────────────────────────────────────────────
    /** Item counts from the last storage scan, or null if not yet scanned. */
    private Map<String, Long> storageInventory = null;
    /** Set to true by handleClick() to request a scan on the next tick. */
    private volatile boolean scanRequested = false;
    /** Receives the completed scan result from the server thread. */
    private final AtomicReference<Map<String, Long>> pendingScan = new AtomicReference<>();

    /** Last known mouse pixel position (updated on click/drag). */
    private int mouseX, mouseY;

    // ────────────────────────────────────────────────────────────────────────
    public CraftingCalculatorProgram() {
        super("Materials");
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────
    @Override
    public void render(TerminalBuffer buffer) {} // unused — pixel mode only

    @Override
    public void init(JavaOS os) {
        this.os = os;
        buildAllIds();
        applyFilter();
    }

    @Override
    public boolean tick() {
        if (statusTicks > 0) statusTicks--;

        // Storage scan — submit to server thread so BE access is thread-safe.
        if (scanRequested) {
            scanRequested = false;
            Level clientLevel = os.getLevel();
            BlockPos pos = os.getBlockPos();
            if (clientLevel != null && pos != null && clientLevel.isClientSide()) {
                Minecraft mc = Minecraft.getInstance();
                MinecraftServer server = mc.getSingleplayerServer();
                if (server != null) {
                    net.minecraft.server.level.ServerLevel serverLevel =
                            server.getLevel(clientLevel.dimension());
                    if (serverLevel != null) {
                        final BlockPos scanPos = pos;
                        server.execute(() -> {
                            Map<String, Long> result = StorageScanner.scan(serverLevel, scanPos);
                            pendingScan.set(result);
                        });
                        setStatus("Scanning storage...", C_ACNT);
                    } else {
                        setStatus("Scan unavailable (multiplayer not yet supported)", C_YEL);
                    }
                } else {
                    setStatus("Scan unavailable (multiplayer not yet supported)", C_YEL);
                }
            }
        }

        // Pick up completed scan from server thread
        Map<String, Long> completed = pendingScan.getAndSet(null);
        if (completed != null) {
            storageInventory = completed;
            int total = storageInventory.values().stream()
                    .mapToInt(v -> v > Integer.MAX_VALUE ? Integer.MAX_VALUE : v.intValue()).sum();
            setStatus("Scanned " + storageInventory.size() + " item type(s), " + total + " total", C_GRN);
        }

        return running;
    }

    // ── Events ───────────────────────────────────────────────────────────────
    @Override
    public void handleEvent(OSEvent event) {
        switch (event.getType()) {
            case CHAR          -> handleChar(event.getString(0).charAt(0));
            case KEY           -> handleKey(event.getInt(0));
            case MOUSE_CLICK   -> {} // handled via pixel variant
            case MOUSE_CLICK_PX -> {
                mouseX = event.getInt(1);
                mouseY = event.getInt(2);
                handleClick();
            }
            case MOUSE_DRAG_PX -> {
                mouseX = event.getInt(1);
                mouseY = event.getInt(2);
            }
            case MOUSE_SCROLL -> {
                mouseX = event.getInt(1) * PixelBuffer.CELL_W;
                mouseY = event.getInt(2) * PixelBuffer.CELL_H;
                handleScroll(event.getInt(0));
            }
            default            -> {}
        }
    }

    private void handleChar(char c) {
        if (c >= 32 && c <= 126) {
            dropdownOpen = false;
            searchQuery.insert(searchCursor++, c);
            searchFocused = true;
            resetSelection();
            applyFilter();
        }
    }

    private void handleKey(int key) {
        switch (key) {
            case 256 -> { if (dropdownOpen) dropdownOpen = false; else running = false; } // Escape
            case 259 -> {                                          // Backspace
                if (searchCursor > 0) {
                    searchQuery.deleteCharAt(--searchCursor);
                    resetSelection();
                    applyFilter();
                }
            }
            case 261 -> {                                          // Delete
                if (searchCursor < searchQuery.length()) {
                    searchQuery.deleteCharAt(searchCursor);
                    resetSelection();
                    applyFilter();
                }
            }
            case 263 -> { if (searchCursor > 0) searchCursor--; }               // Left
            case 262 -> { if (searchCursor < searchQuery.length()) searchCursor++; } // Right
            case 268 -> searchCursor = 0;                         // Home
            case 269 -> searchCursor = searchQuery.length();      // End
            case 265 -> moveSelection(-1);                        // Up
            case 264 -> moveSelection(+1);                        // Down
            case 257 -> { if (selectedIndex >= 0) recomputeMaterials(); } // Enter
        }
    }

    private void handleClick() {
        int px = mouseX, py = mouseY;

        // ── Dropdown intercept ───────────────────────────────────────────────
        if (dropdownOpen) {
            int dropH = computeDropdownHeight();
            int dropX = BTN_OUT_X;
            int dropY = PH - BTM_H - dropH;
            if (px >= dropX && px < dropX + DROP_W && py >= dropY && py < PH - BTM_H) {
                int itemY = dropY + 4;
                for (int i = 0; i < DROPDOWN_ITEMS.length; i++) {
                    String item = DROPDOWN_ITEMS[i];
                    if (item == null) {
                        itemY += 5;
                    } else {
                        if (py >= itemY && py < itemY + DROP_ROW) {
                            activateDropdownItem(i);
                            dropdownOpen = false;
                            return;
                        }
                        itemY += DROP_ROW;
                    }
                }
            }
            dropdownOpen = false;
            return;
        }

        // ── Search bar ───────────────────────────────────────────────────────
        if (py >= HDR_H && py < HDR_H + SRH_H) {
            searchFocused = true;
            return;
        }

        // ── Left panel: item list ──
        if (px < LIST_W && py >= LIST_Y && py < LIST_Y + LIST_H) {
            searchFocused = false;
            int row = (py - LIST_Y) / ROW_H + listScroll;
            if (row >= 0 && row < results.size()) {
                selectedIndex = row;
                selectedId    = results.get(row);
                recomputeMaterials();
            }
            return;
        }

        // ── Right panel header: craft-count stepper ──
        if (py >= LIST_Y && py < LIST_Y + ROW_H && px >= MAT_X) {
            searchFocused = false;
            int sx = MAT_X + MAT_W - 70;
            if (px >= sx && px < sx + 14) {                      // [−]
                craftCount = Math.max(1, craftCount - 1);
                recomputeMaterials();
            } else {
                String cntStr = String.valueOf(craftCount);
                int ux = sx + 18 + cntStr.length() * CW + 4;
                if (px >= ux && px < ux + 14) {                  // [+]
                    craftCount = Math.min(999, craftCount + 1);
                    recomputeMaterials();
                }
            }
            return;
        }

        // ── Bottom bar ───────────────────────────────────────────────────────
        int btmY = PH - BTM_H;
        if (py >= btmY && py < PH) {
            if (px >= BTN_OUT_X && px < BTN_OUT_X + BTN_OUT_W) {
                dropdownOpen = !dropdownOpen;
            } else if (px >= BTN_SCAN_X && px < BTN_SCAN_X + BTN_SCAN_W) {
                // Request a storage scan
                dropdownOpen = false;
                scanRequested = true;
                setStatus("Scanning storage...", C_ACNT);
            }
        }
    }

    private void activateDropdownItem(int i) {
        switch (DROPDOWN_ITEMS[i]) {
            case "Save"          -> doSave();
            case "Save As"       -> doSave();
            case "Save to Disk"  -> doSaveToDisk();
            case "Print"         -> doPrint();
            case "Book"          -> doWriteBook();
            case "Clipboard"     -> doCopy();
            case "Mod Clipboard" -> doModClipboard();
        }
    }

    private void handleScroll(int delta) {
        if (mouseX >= MAT_X) {
            matScroll  = clamp(matScroll  + delta, 0, Math.max(0, materials.size() - visibleMatRows()));
        } else {
            listScroll = clamp(listScroll + delta, 0, Math.max(0, results.size()   - visibleListRows()));
        }
    }

    // ── Search / selection ───────────────────────────────────────────────────
    private void resetSelection() {
        selectedIndex = -1;
        selectedId    = null;
        materials.clear();
        hasRecipe     = false;
    }

    private void applyFilter() {
        if (ALL_IDS == null) return;
        String q = searchQuery.toString().toLowerCase();
        results.clear();
        for (String id : ALL_IDS) {
            if (q.isEmpty() || id.contains(q) || getName(id).toLowerCase().contains(q)) {
                results.add(id);
            }
        }
        listScroll = 0;
    }

    private void moveSelection(int dir) {
        if (results.isEmpty()) return;
        selectedIndex = clamp(selectedIndex + dir, 0, results.size() - 1);
        if (selectedIndex < listScroll) listScroll = selectedIndex;
        if (selectedIndex >= listScroll + visibleListRows()) {
            listScroll = selectedIndex - visibleListRows() + 1;
        }
        selectedId = results.get(selectedIndex);
        recomputeMaterials();
    }

    private static void buildAllIds() {
        if (ALL_IDS != null) return;
        ALL_IDS = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null) ALL_IDS.add(id.toString());
        }
        Collections.sort(ALL_IDS);
    }

    private static String getName(String registryId) {
        return NAME_CACHE.computeIfAbsent(registryId, id -> {
            try {
                ResourceLocation rl = ResourceLocation.parse(id);
                Item item = BuiltInRegistries.ITEM.get(rl);
                if (item == null) return id;
                return item.getName(new ItemStack(item)).getString();
            } catch (Exception e) {
                return id;
            }
        });
    }

    // ── Recipe resolution ────────────────────────────────────────────────────
    private void recomputeMaterials() {
        if (selectedId == null) { materials.clear(); return; }

        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(selectedId));
        if (item == null) { materials.clear(); return; }

        RecipeManager rm = getRecipeManager();
        if (rm == null) {
            setStatus("Recipe manager unavailable", C_RED);
            materials.clear();
            return;
        }

        RegistryAccess ra = getRegistryAccess();
        Map<String, Long> out = new LinkedHashMap<>();
        hasRecipe = resolve(item, craftCount, rm, ra, out, new HashSet<>());

        // Sort by amount descending so the most-needed materials appear first
        materials = out.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
        matScroll = 0;

        if (hasRecipe) {
            setStatus(materials.size() + " material(s) for "
                    + getName(selectedId) + " x" + craftCount, C_GRN);
        } else {
            setStatus("No crafting recipe found for " + getName(selectedId), C_YEL);
        }
    }

    /**
     * Recursively resolves all raw crafting ingredients for {@code item × need}.
     * Writes results into {@code out} (registry ID → total count).
     * Returns true if a recipe chain was found, false if item is a base material.
     */
    private boolean resolve(Item item, long need, RecipeManager rm,
                            RegistryAccess ra, Map<String, Long> out, Set<String> seen) {
        String id = BuiltInRegistries.ITEM.getKey(item).toString();

        // Find the first crafting recipe that produces this item
        RecipeHolder<CraftingRecipe> best = null;
        for (RecipeHolder<CraftingRecipe> h : rm.getAllRecipesFor(RecipeType.CRAFTING)) {
            if (h.value().getResultItem(ra).is(item)) { best = h; break; }
        }

        if (best == null) {
            // Base material — no recipe, record it directly
            out.merge(id, need, Long::sum);
            return false;
        }

        int    yields  = Math.max(1, best.value().getResultItem(ra).getCount());
        long   batches = (need + yields - 1) / yields; // ceiling division

        for (Ingredient ing : best.value().getIngredients()) {
            ItemStack[] choices = ing.getItems();
            if (choices == null || choices.length == 0) continue;

            // Pick the first non-empty ingredient choice
            Item ingItem = null;
            for (ItemStack s : choices) {
                if (!s.isEmpty()) { ingItem = s.getItem(); break; }
            }
            if (ingItem == null) continue;

            String ingId = BuiltInRegistries.ITEM.getKey(ingItem).toString();
            if (seen.contains(ingId)) {
                // Cycle guard — record as base material
                out.merge(ingId, batches, Long::sum);
                continue;
            }
            seen.add(ingId);
            resolve(ingItem, batches, rm, ra, out, seen);
            seen.remove(ingId);
        }
        return true;
    }

    // ── Actions ──────────────────────────────────────────────────────────────
    private void doPrint() {
        if (selectedId == null) {
            setStatus("Select an item first", C_YEL);
            return;
        }
        Level level = os.getLevel();
        BlockPos pos = os.getBlockPos();
        if (level == null || pos == null) {
            setStatus("No world context", C_RED);
            return;
        }
        PrinterBlockEntity printer = null;
        for (Direction dir : Direction.values()) {
            BlockEntity be = level.getBlockEntity(pos.relative(dir));
            if (be instanceof PrinterBlockEntity p) { printer = p; break; }
        }
        if (printer == null) {
            setStatus("No printer adjacent to computer", C_RED);
            return;
        }
        printer.queuePrint("Materials: " + getName(selectedId), buildOutput());
        setStatus("Sent to printer!", C_GRN);
    }

    private void doCopy() {
        if (selectedId == null) {
            setStatus("Select an item first", C_YEL);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.keyboardHandler.setClipboard(buildOutput());
            setStatus("Copied to clipboard!", C_GRN);
        }
    }

    private String buildOutput() {
        StringBuilder sb = new StringBuilder();
        sb.append("Materials for: ")
          .append(getName(selectedId))
          .append(" x").append(craftCount).append("\n");
        sb.append("------------------------------\n");
        if (materials.isEmpty()) {
            sb.append("No crafting recipe found.\n");
        } else {
            for (Map.Entry<String, Long> e : materials.entrySet()) {
                sb.append(String.format("%-28s x%d\n", getName(e.getKey()), e.getValue()));
            }
        }
        return sb.toString();
    }

    private void setStatus(String msg, int color) {
        statusMsg   = msg;
        statusColor = color;
        statusTicks = 140;
    }

    // ── World access ─────────────────────────────────────────────────────────
    private RecipeManager getRecipeManager() {
        Level level = os.getLevel();
        if (level != null) return level.getRecipeManager();
        Minecraft mc = Minecraft.getInstance();
        return (mc != null && mc.level != null) ? mc.level.getRecipeManager() : null;
    }

    private RegistryAccess getRegistryAccess() {
        Level level = os.getLevel();
        if (level != null) return level.registryAccess();
        Minecraft mc = Minecraft.getInstance();
        return (mc != null && mc.level != null) ? mc.level.registryAccess() : RegistryAccess.EMPTY;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private int visibleListRows() { return (LIST_H - ROW_H) / ROW_H; }  // minus sub-header row
    private int visibleMatRows()  { return (LIST_H - ROW_H * 2) / ROW_H; } // minus header+colhdr rows

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ── Rendering ─────────────────────────────────────────────────────────────
    @Override
    public void renderGraphics(PixelBuffer pb) {
        pb.clear(C_BG);
        drawHeader(pb);
        drawSearchBar(pb);
        pb.fillRect(LIST_W, LIST_Y, 1, LIST_H, C_SEP);   // vertical divider
        drawItemList(pb);
        drawMaterialsPanel(pb);
        drawBottomBar(pb);
        drawDropdown(pb);                                  // overlay — rendered last
    }

    // ── Header ───────────────────────────────────────────────────────────────
    private void drawHeader(PixelBuffer pb) {
        pb.fillRect(0, 0, PW, HDR_H, C_HDR);
        pb.drawString(6, (HDR_H - CH) / 2, "Materials Calculator", C_TEXT);
        String cnt = results.size() + " items";
        pb.drawStringRight(PW - 6, (HDR_H - CH) / 2, cnt, C_MUTED);
    }

    // ── Search bar ───────────────────────────────────────────────────────────
    private void drawSearchBar(PixelBuffer pb) {
        int y   = HDR_H;
        int bgC = searchFocused ? C_SRCH_FOC : C_SRCH;
        pb.fillRect(0, y, PW, SRH_H, bgC);
        pb.fillRect(0, y + SRH_H - 1, PW, 1, C_SEP);
        if (searchFocused) pb.drawRect(0, y, PW, SRH_H, C_ACNT);  // focus border

        pb.drawString(6, y + (SRH_H - CH) / 2, ">", C_ACNT);

        String q     = searchQuery.toString();
        int    textX = 18;
        int    textY = y + (SRH_H - CH) / 2;

        if (q.isEmpty() && !searchFocused) {
            pb.drawString(textX, textY, "Click here to search all items and blocks...", C_MUTED);
        } else {
            if (!q.isEmpty()) pb.drawString(textX, textY, q, C_TEXT);
            // Blinking cursor when focused
            if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
                pb.fillRect(textX + searchCursor * CW, textY - 1, 1, CH + 2, C_ACNT);
            }
        }
    }

    // ── Item list ────────────────────────────────────────────────────────────
    private void drawItemList(PixelBuffer pb) {
        pb.fillRect(0, LIST_Y, LIST_W, LIST_H, C_PANEL);

        // Sub-header
        pb.fillRect(0, LIST_Y, LIST_W, ROW_H, C_HDR);
        pb.drawString(6, LIST_Y + (ROW_H - CH) / 2, "Items & Blocks", C_ACNT);

        int startY  = LIST_Y + ROW_H;
        int maxRows = (LIST_H - ROW_H) / ROW_H;

        for (int i = 0; i < maxRows; i++) {
            int idx  = i + listScroll;
            if (idx >= results.size()) break;

            String id   = results.get(idx);
            int    rowY = startY + i * ROW_H;
            boolean sel = (idx == selectedIndex);

            pb.fillRect(0, rowY, LIST_W, ROW_H, sel ? C_SEL : C_PANEL);
            if (sel) pb.fillRect(0, rowY, 2, ROW_H, C_ACNT);   // accent stripe

            // Namespace muted, item path normal
            int    colon = id.indexOf(':');
            String ns    = colon >= 0 ? id.substring(0, colon + 1) : "";
            String path  = colon >= 0 ? id.substring(colon + 1)    : id;
            int    nsW   = ns.length() * CW;

            pb.drawString(6,        rowY + (ROW_H - CH) / 2, ns,   C_MUTED);
            pb.drawString(6 + nsW,  rowY + (ROW_H - CH) / 2, path, sel ? 0xFFFFFFFF : C_TEXT);
            pb.fillRect(0, rowY + ROW_H - 1, LIST_W, 1, C_SEP);
        }

        // Scrollbar
        if (results.size() > maxRows && maxRows > 0) {
            int trackH = LIST_H - ROW_H;
            int thumbH = Math.max(8, trackH * maxRows / results.size());
            int thumbY = listScroll * (trackH - thumbH) / Math.max(1, results.size() - maxRows);
            pb.fillRect(LIST_W - 3, startY, 3, trackH, C_SEP);
            pb.fillRect(LIST_W - 3, startY + thumbY, 3, thumbH, C_ACNT);
        }
    }

    // ── Materials panel ──────────────────────────────────────────────────────
    private void drawMaterialsPanel(PixelBuffer pb) {
        pb.fillRect(MAT_X, LIST_Y, MAT_W, LIST_H, C_PANEL);

        if (selectedId == null) {
            pb.drawStringCentered(MAT_X, MAT_W, LIST_Y + LIST_H / 2 - CH,
                    "Select an item from the list", C_MUTED);
            pb.drawStringCentered(MAT_X, MAT_W, LIST_Y + LIST_H / 2 + 4,
                    "to see crafting materials", C_MUTED);
            return;
        }

        // ── Item name header + craft-count stepper ──
        pb.fillRect(MAT_X, LIST_Y, MAT_W, ROW_H, C_HDR);
        String itemName = getName(selectedId);
        // Truncate name to leave room for the stepper (~220 px)
        if (itemName.length() * CW > MAT_W - 100) {
            itemName = itemName.substring(0, (MAT_W - 100) / CW - 1) + "\u2026";
        }
        pb.drawString(MAT_X + 6, LIST_Y + (ROW_H - CH) / 2, itemName, C_TEXT);

        // Craft-count stepper: [−] N [+]
        int sx = MAT_X + MAT_W - 70;
        int sy = LIST_Y + (ROW_H - CH) / 2;
        pb.drawString(sx - CW, sy, "x", C_MUTED);

        // [−] button
        boolean dnh = (mouseX >= sx && mouseX < sx + 14
                && mouseY >= LIST_Y && mouseY < LIST_Y + ROW_H);
        pb.fillRect(sx, LIST_Y + 2, 14, ROW_H - 4, dnh ? C_BTNH : C_BTN);
        pb.drawString(sx + 3, sy, "-", C_TEXT);

        // Count
        String cntStr = String.valueOf(craftCount);
        pb.drawString(sx + 18, sy, cntStr, C_YEL);

        // [+] button
        int ux = sx + 18 + cntStr.length() * CW + 4;
        boolean uph = (mouseX >= ux && mouseX < ux + 14
                && mouseY >= LIST_Y && mouseY < LIST_Y + ROW_H);
        pb.fillRect(ux, LIST_Y + 2, 14, ROW_H - 4, uph ? C_BTNH : C_BTN);
        pb.drawString(ux + 3, sy, "+", C_TEXT);

        // ── Column headers ──
        int colY = LIST_Y + ROW_H;
        pb.fillRect(MAT_X, colY, MAT_W, ROW_H, C_SUBHD);
        pb.drawString(MAT_X + 6, colY + (ROW_H - CH) / 2, "Material", C_ACNT);
        pb.drawStringRight(MAT_X + MAT_W - 6, colY + (ROW_H - CH) / 2, "Amount", C_ACNT);
        pb.fillRect(MAT_X, colY + ROW_H - 1, MAT_W, 1, C_SEP);

        // ── Material rows ──
        if (materials.isEmpty()) {
            String msg = hasRecipe ? "Computing..." : "No crafting recipe found";
            pb.drawString(MAT_X + 8, LIST_Y + ROW_H * 2 + 8, msg, C_YEL);
            return;
        }

        int dataY   = LIST_Y + ROW_H * 2;
        int maxRows = visibleMatRows();
        List<Map.Entry<String, Long>> entries = new ArrayList<>(materials.entrySet());

        for (int i = 0; i < maxRows; i++) {
            int idx = i + matScroll;
            if (idx >= entries.size()) break;

            Map.Entry<String, Long> e = entries.get(idx);
            int rowY = dataY + i * ROW_H;
            pb.fillRect(MAT_X, rowY, MAT_W, ROW_H, (i % 2 == 0) ? C_PANEL : C_SUBHD);

            String mid   = e.getKey();
            int    colon = mid.indexOf(':');
            String ns    = colon >= 0 ? mid.substring(0, colon + 1) : "";
            String path  = colon >= 0 ? mid.substring(colon + 1)    : mid;
            int    nsW   = ns.length() * CW;

            pb.drawString(MAT_X + 6,        rowY + (ROW_H - CH) / 2, ns,   C_MUTED);
            pb.drawString(MAT_X + 6 + nsW,  rowY + (ROW_H - CH) / 2, path, C_TEXT);

            // Need amount
            long need = e.getValue();
            String needStr = "x" + need;

            if (storageInventory != null) {
                // Show need + have with color coding
                long have = storageInventory.getOrDefault(e.getKey(), 0L);
                int haveColor;
                if (have >= need)      haveColor = C_GRN;
                else if (have > 0)     haveColor = C_YEL;
                else                   haveColor = C_RED;

                String haveStr = "(have " + have + ")";
                pb.drawStringRight(MAT_X + MAT_W - 6, rowY + (ROW_H - CH) / 2, haveStr, haveColor);
                int haveW = haveStr.length() * CW + 8;
                pb.drawStringRight(MAT_X + MAT_W - 6 - haveW, rowY + (ROW_H - CH) / 2, needStr, C_YEL);
            } else {
                pb.drawStringRight(MAT_X + MAT_W - 6, rowY + (ROW_H - CH) / 2, needStr, C_YEL);
            }
        }

        // Scrollbar
        if (entries.size() > maxRows && maxRows > 0) {
            int trackH = LIST_H - ROW_H * 2;
            int thumbH = Math.max(8, trackH * maxRows / entries.size());
            int thumbY = matScroll * (trackH - thumbH) / Math.max(1, entries.size() - maxRows);
            pb.fillRect(MAT_X + MAT_W - 3, dataY, 3, trackH, C_SEP);
            pb.fillRect(MAT_X + MAT_W - 3, dataY + thumbY, 3, thumbH, C_ACNT);
        }
    }

    // ── Bottom bar ───────────────────────────────────────────────────────────
    private void drawBottomBar(PixelBuffer pb) {
        int y = PH - BTM_H;
        pb.fillRect(0, y, PW, BTM_H, C_HDR);
        pb.fillRect(0, y, PW, 1, C_SEP);

        int btnY = y + 4;
        int btnH = BTM_H - 8;
        int mid  = y + (BTM_H - CH) / 2;

        // Output dropdown button
        boolean outH = !dropdownOpen
                && mouseX >= BTN_OUT_X && mouseX < BTN_OUT_X + BTN_OUT_W
                && mouseY >= btnY && mouseY < y + BTM_H - 4;
        int outBg = (dropdownOpen || outH) ? C_BTNH : C_BTN;
        pb.fillRect(BTN_OUT_X, btnY, BTN_OUT_W, btnH, outBg);
        pb.drawStringCentered(BTN_OUT_X, BTN_OUT_W, mid,
                dropdownOpen ? "Output [^]" : "Output [v]", C_TEXT);

        // Scan storage button
        boolean scanH = mouseX >= BTN_SCAN_X && mouseX < BTN_SCAN_X + BTN_SCAN_W
                && mouseY >= btnY && mouseY < y + BTM_H - 4;
        int scanBg = scanH ? C_BTNH : (storageInventory != null ? 0xFF1A4A2A : C_BTN);
        pb.fillRect(BTN_SCAN_X, btnY, BTN_SCAN_W, btnH, scanBg);
        String scanLabel = storageInventory != null ? "[BT] Scanned" : "Scan [BT]";
        pb.drawStringCentered(BTN_SCAN_X, BTN_SCAN_W, mid, scanLabel,
                storageInventory != null ? C_GRN : C_TEXT);

        // Status / hint
        if (statusTicks > 0 && !statusMsg.isEmpty()) {
            pb.drawString(HINT_X, mid, statusMsg, statusColor);
        } else if (selectedId != null && !materials.isEmpty()) {
            String hint = storageInventory != null
                    ? materials.size() + " material(s)  |  Scan fresh: click [BT]"
                    : materials.size() + " material(s)  |  Click [BT] to check storage";
            pb.drawString(HINT_X, mid, hint, C_MUTED);
        } else {
            pb.drawString(HINT_X, mid,
                    "Search items  |  Click [BT] to scan storage",
                    C_MUTED);
        }
    }

    // ── Dropdown overlay ─────────────────────────────────────────────────────
    private void drawDropdown(PixelBuffer pb) {
        if (!dropdownOpen) return;
        int dropH = computeDropdownHeight();
        int dropX = BTN_OUT_X;
        int dropY = PH - BTM_H - dropH;
        pb.fillRect(dropX, dropY, DROP_W, dropH, C_DROP_BG);
        pb.drawRect(dropX, dropY, DROP_W, dropH, C_DROP_DIV);

        int itemY = dropY + 4;
        for (String item : DROPDOWN_ITEMS) {
            if (item == null) {
                pb.fillRect(dropX + 6, itemY + 2, DROP_W - 12, 1, C_DROP_DIV);
                itemY += 5;
            } else {
                boolean hov = mouseX >= dropX && mouseX < dropX + DROP_W
                        && mouseY >= itemY && mouseY < itemY + DROP_ROW;
                if (hov) pb.fillRect(dropX + 1, itemY, DROP_W - 2, DROP_ROW, C_DROP_SEL);
                pb.drawString(dropX + 10, itemY + (DROP_ROW - CH) / 2, item,
                        hov ? C_TEXT : C_MUTED);
                itemY += DROP_ROW;
            }
        }
    }

    private int computeDropdownHeight() {
        int h = 8;
        for (String item : DROPDOWN_ITEMS) h += (item == null) ? 5 : DROP_ROW;
        return h;
    }

    // ── Save / export actions ─────────────────────────────────────────────────
    private void doSave() { doSaveToDisk(); }

    private void doSaveToDisk() {
        if (selectedId == null) { setStatus("Select an item first", C_YEL); return; }
        String safeName = selectedId.replace(':', '_').replace('/', '_');
        Path dir = Path.of("material_exports");
        try {
            Files.createDirectories(dir);
            Path out = dir.resolve("materials_" + safeName + ".txt");
            Files.writeString(out, buildOutput());
            setStatus("Saved: materials_" + safeName + ".txt", C_GRN);
        } catch (IOException e) {
            setStatus("Save failed: " + e.getMessage(), C_RED);
        }
    }

    private void doWriteBook() {
        if (selectedId == null) { setStatus("Select an item first", C_YEL); return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) { setStatus("No game context", C_RED); return; }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) { setStatus("Book requires singleplayer", C_YEL); return; }
        ServerPlayer sp = server.getPlayerList().getPlayerByName(mc.getUser().getName());
        if (sp == null) { setStatus("Player not found on server", C_RED); return; }

        String output = buildOutput();
        java.util.List<Filterable<String>> pages = new java.util.ArrayList<>();
        int pageLen = 256;
        for (int i = 0; i < output.length(); i += pageLen) {
            pages.add(Filterable.passThrough(
                    output.substring(i, Math.min(i + pageLen, output.length()))));
        }
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(pages));
        server.execute(() -> sp.addItem(book));
        setStatus("Book added to inventory!", C_GRN);
    }

    private void doModClipboard() {
        if (selectedId == null) { setStatus("Select an item first", C_YEL); return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.keyboardHandler.setClipboard("=== ByteBlock Materials Export ===\n" + buildOutput());
            setStatus("Copied to mod clipboard!", C_GRN);
        }
    }
}

package com.apocscode.byteblock.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import com.apocscode.byteblock.entity.DroneEntity;
import com.apocscode.byteblock.entity.RobotEntity;
import com.apocscode.byteblock.network.BluetoothNetwork;

import java.util.ArrayList;
import java.util.List;

/**
 * GPS Location / Fleet Programming Tool.
 *
 * Cycle modes with Shift + Scroll while holding the tool:
 *   WAYPOINT — single point (legacy, used by the Puzzle IDE).
 *   ROUTE    — two-point pickup → drop route (source + destination chests).
 *   AREA     — two-corner patrol/work area (AABB).
 *   PATH     — ordered chain of waypoints.
 *
 * Interactions:
 *   R-click block           — set primary, then secondary (or append PATH node).
 *   Shift + R-click drone/robot — apply currently-stored data.
 *   Shift + R-click air     — clear current mode's stored data.
 */
public class GpsToolItem extends Item {

    public enum Mode {
        WAYPOINT("Waypoint", 0x00ccff),
        ROUTE("Route",       0x22cc22),
        AREA("Area",         0xffcc00),
        PATH("Path",         0xcc33ff);

        public final String display;
        public final int color; // RGB (for renderer)

        Mode(String display, int color) {
            this.display = display;
            this.color = color;
        }

        public static Mode fromOrdinal(int i) {
            Mode[] vs = values();
            if (i < 0) i = 0;
            return vs[Math.floorMod(i, vs.length)];
        }
    }

    public GpsToolItem(Properties properties) {
        super(properties);
    }

    // ── NBT accessors (public for client renderer) ─────────────────────

    public static Mode getMode(ItemStack stack) {
        return Mode.fromOrdinal(readTag(stack).getInt("Mode"));
    }

    public static void setMode(ItemStack stack, Mode mode) {
        CompoundTag tag = readTag(stack);
        tag.putInt("Mode", mode.ordinal());
        writeTag(stack, tag);
    }

    public static Mode cycleMode(ItemStack stack, int direction) {
        Mode[] vs = Mode.values();
        int next = Math.floorMod(getMode(stack).ordinal() + (direction >= 0 ? 1 : -1), vs.length);
        setMode(stack, vs[next]);
        return vs[next];
    }

    public static BlockPos getA(ItemStack stack) {
        CompoundTag tag = readTag(stack);
        if (!tag.getBoolean("HasA") && !tag.contains("X")) return null;
        return new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
    }

    public static BlockPos getB(ItemStack stack) {
        CompoundTag tag = readTag(stack);
        if (!tag.getBoolean("HasB")) return null;
        return new BlockPos(tag.getInt("BX"), tag.getInt("BY"), tag.getInt("BZ"));
    }

    public static List<BlockPos> getPath(ItemStack stack) {
        CompoundTag tag = readTag(stack);
        List<BlockPos> out = new ArrayList<>();
        if (!tag.contains("Path")) return out;
        ListTag list = tag.getList("Path", 10); // CompoundTag tag id
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            out.add(new BlockPos(c.getInt("X"), c.getInt("Y"), c.getInt("Z")));
        }
        return out;
    }

    public static void setA(ItemStack stack, BlockPos pos) {
        CompoundTag tag = readTag(stack);
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        tag.putBoolean("HasA", true);
        writeTag(stack, tag);
    }

    public static void setB(ItemStack stack, BlockPos pos) {
        CompoundTag tag = readTag(stack);
        tag.putInt("BX", pos.getX());
        tag.putInt("BY", pos.getY());
        tag.putInt("BZ", pos.getZ());
        tag.putBoolean("HasB", true);
        writeTag(stack, tag);
    }

    public static void appendPath(ItemStack stack, BlockPos pos) {
        CompoundTag tag = readTag(stack);
        ListTag list = tag.contains("Path") ? tag.getList("Path", 10) : new ListTag();
        CompoundTag c = new CompoundTag();
        c.putInt("X", pos.getX());
        c.putInt("Y", pos.getY());
        c.putInt("Z", pos.getZ());
        list.add(c);
        tag.put("Path", list);
        writeTag(stack, tag);
    }

    /** Stash a chest label associated with the green/input frame (route src or waypoint). */
    public static void setInputLabel(ItemStack stack, String label) {
        CompoundTag tag = readTag(stack);
        if (label == null || label.isEmpty()) tag.remove("InputLabel");
        else tag.putString("InputLabel", label);
        writeTag(stack, tag);
    }

    /** Stash a chest label associated with the blue/output frame (route dst). */
    public static void setOutputLabel(ItemStack stack, String label) {
        CompoundTag tag = readTag(stack);
        if (label == null || label.isEmpty()) tag.remove("OutputLabel");
        else tag.putString("OutputLabel", label);
        writeTag(stack, tag);
    }

    public static String getInputLabel(ItemStack stack) {
        return readTag(stack).getString("InputLabel");
    }

    public static String getOutputLabel(ItemStack stack) {
        return readTag(stack).getString("OutputLabel");
    }

    /**
     * Serialize the GPS tool's NBT into a compact JSON string for upload to a
     * computer's filesystem and for wireless broadcasts. Schema:
     *   { "mode":"ROUTE", "a":{x,y,z}|null, "b":{x,y,z}|null,
     *     "path":[{x,y,z},...], "inputLabel":"", "outputLabel":"" }
     */
    public static String serializeJson(ItemStack stack) {
        StringBuilder sb = new StringBuilder(256);
        Mode m = getMode(stack);
        BlockPos a = getA(stack), b = getB(stack);
        List<BlockPos> path = getPath(stack);
        sb.append('{');
        sb.append("\"mode\":\"").append(m.name()).append('"');
        sb.append(",\"a\":");   appendPosJson(sb, a);
        sb.append(",\"b\":");   appendPosJson(sb, b);
        sb.append(",\"path\":[");
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) sb.append(',');
            appendPosJson(sb, path.get(i));
        }
        sb.append(']');
        sb.append(",\"inputLabel\":\"").append(escape(getInputLabel(stack))).append('"');
        sb.append(",\"outputLabel\":\"").append(escape(getOutputLabel(stack))).append('"');
        sb.append('}');
        return sb.toString();
    }

    private static void appendPosJson(StringBuilder sb, BlockPos p) {
        if (p == null) { sb.append("null"); return; }
        sb.append("{\"x\":").append(p.getX())
          .append(",\"y\":").append(p.getY())
          .append(",\"z\":").append(p.getZ()).append('}');
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static void clearCurrentModeData(ItemStack stack) {
        CompoundTag tag = readTag(stack);
        Mode m = Mode.fromOrdinal(tag.getInt("Mode"));
        switch (m) {
            case WAYPOINT -> {
                tag.remove("X"); tag.remove("Y"); tag.remove("Z");
                tag.putBoolean("HasA", false);
                tag.remove("InputLabel");
            }
            case ROUTE, AREA -> {
                tag.remove("X"); tag.remove("Y"); tag.remove("Z");
                tag.remove("BX"); tag.remove("BY"); tag.remove("BZ");
                tag.putBoolean("HasA", false);
                tag.putBoolean("HasB", false);
                tag.remove("InputLabel");
                tag.remove("OutputLabel");
            }
            case PATH -> tag.remove("Path");
        }
        writeTag(stack, tag);
    }

    private static CompoundTag readTag(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    private static void writeTag(ItemStack stack, CompoundTag tag) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    // ── Right-click block ──────────────────────────────────────────────

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        BlockPos pos = context.getClickedPos();

        if (player == null) return InteractionResult.PASS;

        // ── Computer block: upload current GPS data to /gps/route.json (B2 + B3 broadcast) ──
        var blockState = level.getBlockState(pos);
        if (blockState.getBlock() instanceof com.apocscode.byteblock.block.ComputerBlock) {
            if (level.isClientSide()) {
                String json = serializeJson(stack);
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new com.apocscode.byteblock.network.UploadGpsToComputerPayload(pos, json));
                feedback(player, "GPS route → /gps/route.json (BT ch 9100)", ChatFormatting.AQUA);
            }
            return InteractionResult.SUCCESS;
        }

        if (level.isClientSide()) return InteractionResult.SUCCESS;

        // ── ByteChest: also capture its label so robots can resolve by name ──
        String chestLabel = null;
        if (level.getBlockEntity(pos) instanceof com.apocscode.byteblock.block.entity.ByteChestBlockEntity chest) {
            String l = chest.getLabel();
            if (!l.isEmpty()) chestLabel = l;
        }

        Mode mode = getMode(stack);
        switch (mode) {
            case WAYPOINT -> {
                setA(stack, pos);
                if (chestLabel != null) setInputLabel(stack, chestLabel);
                feedback(player, "Waypoint set: "
                        + (chestLabel != null ? "\"" + chestLabel + "\" " : "") + formatPos(pos),
                        ChatFormatting.AQUA);
            }
            case ROUTE -> {
                if (!readTag(stack).getBoolean("HasA")) {
                    setA(stack, pos);
                    if (chestLabel != null) setInputLabel(stack, chestLabel);
                    feedback(player, "Input (green): "
                            + (chestLabel != null ? "\"" + chestLabel + "\" " : "") + formatPos(pos),
                            ChatFormatting.GREEN);
                } else {
                    setB(stack, pos);
                    if (chestLabel != null) setOutputLabel(stack, chestLabel);
                    feedback(player, "Output (blue): "
                            + (chestLabel != null ? "\"" + chestLabel + "\" " : "") + formatPos(pos)
                            + "  (shift+R-click drone/robot to apply)", ChatFormatting.BLUE);
                }
            }
            case AREA -> {
                if (!readTag(stack).getBoolean("HasA")) {
                    setA(stack, pos);
                    feedback(player, "Area corner 1: " + formatPos(pos), ChatFormatting.YELLOW);
                } else {
                    setB(stack, pos);
                    feedback(player, "Area corner 2: " + formatPos(pos)
                            + "  (shift+R-click drone/robot to apply)", ChatFormatting.YELLOW);
                }
            }
            case PATH -> {
                appendPath(stack, pos);
                int count = getPath(stack).size();
                feedback(player, "Path node #" + count + ": " + formatPos(pos), ChatFormatting.LIGHT_PURPLE);
            }
        }
        return InteractionResult.SUCCESS;
    }

    // ── Right-click air ────────────────────────────────────────────────

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) return InteractionResultHolder.success(stack);

        if (player.isShiftKeyDown()) {
            clearCurrentModeData(stack);
            feedback(player, "Cleared " + getMode(stack).display + " data", ChatFormatting.GRAY);
        } else {
            dumpStatus(player, stack);
        }
        return InteractionResultHolder.success(stack);
    }

    // ── Right-click entity (apply to drone/robot) ──────────────────────

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (player.level().isClientSide()) return InteractionResult.SUCCESS;
        if (!player.isShiftKeyDown()) return InteractionResult.PASS;

        if (target instanceof RobotEntity robot) {
            applyToRobot(player, stack, robot);
            return InteractionResult.SUCCESS;
        }
        if (target instanceof DroneEntity drone) {
            applyToDrone(player, stack, drone);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    private void applyToDrone(Player player, ItemStack stack, DroneEntity drone) {
        // B1: store a copy of the GPS tool on the drone so programs can read it later.
        drone.setGpsToolStack(stack.copy());
        // B3: also drop the full payload on channel 9100 so any listening program sees it.
        BluetoothNetwork.broadcast(drone.level(), drone.blockPosition(), 9100,
                "gps_tool:" + serializeJson(stack));
        Mode mode = getMode(stack);
        BlockPos a = getA(stack), b = getB(stack);
        List<BlockPos> path = getPath(stack);
        switch (mode) {
            case WAYPOINT -> {
                if (a == null) { feedback(player, "No waypoint set", ChatFormatting.RED); return; }
                BluetoothNetwork.send(drone.level(), drone.blockPosition(), drone.getUUID(),
                        "drone:waypoint:" + a.getX() + ":" + a.getY() + ":" + a.getZ());
                feedback(player, "Drone → waypoint " + formatPos(a), ChatFormatting.AQUA);
            }
            case ROUTE -> {
                if (a == null || b == null) { feedback(player, "Route needs both points", ChatFormatting.RED); return; }
                String msg = "drone:route:" + a.getX() + ":" + a.getY() + ":" + a.getZ()
                        + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
                BluetoothNetwork.send(drone.level(), drone.blockPosition(), drone.getUUID(), msg);
                feedback(player, "Drone → route " + formatPos(a) + " ↔ " + formatPos(b), ChatFormatting.GREEN);
            }
            case AREA -> {
                if (a == null || b == null) { feedback(player, "Area needs both corners", ChatFormatting.RED); return; }
                String msg = "drone:patrol:" + a.getX() + ":" + a.getY() + ":" + a.getZ()
                        + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
                BluetoothNetwork.send(drone.level(), drone.blockPosition(), drone.getUUID(), msg);
                feedback(player, "Drone → patrol area", ChatFormatting.YELLOW);
            }
            case PATH -> {
                if (path.isEmpty()) { feedback(player, "Path is empty", ChatFormatting.RED); return; }
                StringBuilder sb = new StringBuilder("drone:path");
                for (BlockPos p : path) sb.append(':').append(p.getX()).append(':').append(p.getY()).append(':').append(p.getZ());
                BluetoothNetwork.send(drone.level(), drone.blockPosition(), drone.getUUID(), sb.toString());
                feedback(player, "Drone → path (" + path.size() + " nodes)", ChatFormatting.LIGHT_PURPLE);
            }
        }
    }

    private void applyToRobot(Player player, ItemStack stack, RobotEntity robot) {
        // Stash the route in the robot's GPS-tool slot (B1) so its programs can read it later.
        robot.setGpsToolStack(stack.copy());
        // Wireless broadcast on channel 9100 (B3): any nearby program listening picks it up.
        BluetoothNetwork.broadcast(robot.level(), robot.blockPosition(), 9100,
                "gps_tool:" + serializeJson(stack));

        Mode mode = getMode(stack);
        BlockPos a = getA(stack), b = getB(stack);
        List<BlockPos> path = getPath(stack);
        switch (mode) {
            case WAYPOINT -> {
                if (a == null) { feedback(player, "No waypoint set", ChatFormatting.RED); return; }
                robot.queueCommand("goto:" + a.getX() + ":" + a.getY() + ":" + a.getZ());
                feedback(player, "Robot → goto " + formatPos(a), ChatFormatting.AQUA);
            }
            case ROUTE -> {
                if (a == null || b == null) { feedback(player, "Route needs both points", ChatFormatting.RED); return; }
                robot.queueCommand("route:" + a.getX() + ":" + a.getY() + ":" + a.getZ()
                        + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ());
                feedback(player, "Robot → route " + formatPos(a) + " ↔ " + formatPos(b), ChatFormatting.GREEN);
            }
            case AREA -> {
                if (a == null || b == null) { feedback(player, "Area needs both corners", ChatFormatting.RED); return; }
                robot.queueCommand("patrol:" + a.getX() + ":" + a.getY() + ":" + a.getZ()
                        + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ());
                feedback(player, "Robot → patrol area", ChatFormatting.YELLOW);
            }
            case PATH -> {
                if (path.isEmpty()) { feedback(player, "Path is empty", ChatFormatting.RED); return; }
                StringBuilder sb = new StringBuilder("path");
                for (BlockPos p : path) sb.append(':').append(p.getX()).append(':').append(p.getY()).append(':').append(p.getZ());
                robot.queueCommand(sb.toString());
                feedback(player, "Robot → path (" + path.size() + " nodes)", ChatFormatting.LIGHT_PURPLE);
            }
        }
    }

    private static void dumpStatus(Player player, ItemStack stack) {
        Mode mode = getMode(stack);
        BlockPos a = getA(stack), b = getB(stack);
        List<BlockPos> path = getPath(stack);
        StringBuilder sb = new StringBuilder("GPS [" + mode.display + "] ");
        if (mode == Mode.PATH) sb.append(path.size()).append(" nodes");
        else {
            sb.append("A=").append(a == null ? "—" : formatPos(a));
            if (mode != Mode.WAYPOINT) sb.append("  B=").append(b == null ? "—" : formatPos(b));
        }
        feedback(player, sb.toString(), ChatFormatting.AQUA);
    }

    private static void feedback(Player player, String msg, ChatFormatting color) {
        player.displayClientMessage(Component.literal(msg).withStyle(color), true);
    }

    private static String formatPos(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    // ── Tooltip ────────────────────────────────────────────────────────

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        Mode mode = getMode(stack);
        tooltip.add(Component.literal("Mode: " + mode.display).withStyle(ChatFormatting.WHITE));
        BlockPos a = getA(stack), b = getB(stack);
        switch (mode) {
            case WAYPOINT -> {
                if (a != null) tooltip.add(Component.literal("  " + formatPos(a)).withStyle(ChatFormatting.AQUA));
            }
            case ROUTE -> {
                if (a != null) tooltip.add(Component.literal("  Src: " + formatPos(a)).withStyle(ChatFormatting.GREEN));
                if (b != null) tooltip.add(Component.literal("  Dst: " + formatPos(b)).withStyle(ChatFormatting.BLUE));
            }
            case AREA -> {
                if (a != null) tooltip.add(Component.literal("  C1: " + formatPos(a)).withStyle(ChatFormatting.YELLOW));
                if (b != null) tooltip.add(Component.literal("  C2: " + formatPos(b)).withStyle(ChatFormatting.YELLOW));
            }
            case PATH -> {
                int n = getPath(stack).size();
                tooltip.add(Component.literal("  " + n + " node" + (n == 1 ? "" : "s")).withStyle(ChatFormatting.LIGHT_PURPLE));
            }
        }
        tooltip.add(Component.empty());
        tooltip.add(Component.literal("Shift + Scroll: cycle mode").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("R-click block: set point").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("R-click ByteChest: capture label").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("R-click Computer: upload to /gps/route.json").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("Shift + R-click drone/robot: apply").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("Shift + R-click air: clear").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("Broadcast on BT ch 9100").withStyle(ChatFormatting.DARK_AQUA));
    }
}
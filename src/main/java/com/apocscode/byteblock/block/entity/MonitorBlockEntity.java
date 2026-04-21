package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.block.MonitorBlock;
import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class MonitorBlockEntity extends BlockEntity {

    // Formation data
    private BlockPos originPos;
    private int multiWidth = 1;
    private int multiHeight = 1;
    private int offsetX = 0;
    private int offsetY = 0;

    // Linked computer position
    private BlockPos linkedComputerPos;

    // Bluetooth registration
    private UUID deviceId = UUID.randomUUID();

    // Display mode: "mirror" (default) or "test:<pattern>"
    private String displayMode = "mirror";

    private int autoDetectCooldown = 0;

    public MonitorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MONITOR.get(), pos, state);
        this.originPos = pos;
    }

    // ── Tick (server-side, origin only) ──────────────────────

    public void tick() {
        if (level == null || level.isClientSide()) return;
        if (!worldPosition.equals(originPos)) return;

        // Register on Bluetooth
        BluetoothNetwork.register(level, deviceId, worldPosition, 1,
                BluetoothNetwork.DeviceType.MONITOR);

        // Process BT messages (display mode commands from programs)
        BluetoothNetwork.Message msg = BluetoothNetwork.receive(deviceId);
        while (msg != null) {
            processMessage(msg);
            msg = BluetoothNetwork.receive(deviceId);
        }

        // Auto-detect linked computer
        if (linkedComputerPos == null) {
            if (autoDetectCooldown <= 0) {
                autoDetectCooldown = 40;
                findNearbyComputer();
            } else {
                autoDetectCooldown--;
            }
        }

        // Verify linked computer still exists every 2 seconds
        if (linkedComputerPos != null && level.getGameTime() % 40 == 0) {
            if (!(level.getBlockEntity(linkedComputerPos) instanceof ComputerBlockEntity)) {
                linkedComputerPos = null;
                syncToClient();
            }
        }
    }

    private void findNearbyComputer() {
        Direction facing = getBlockState().getValue(MonitorBlock.FACING);
        // Check all formation members' adjacent blocks
        for (int ox = 0; ox < multiWidth; ox++) {
            for (int oy = 0; oy < multiHeight; oy++) {
                BlockPos memberPos = getWorldPosForOffset(ox, oy);
                for (Direction dir : Direction.values()) {
                    BlockPos adj = memberPos.relative(dir);
                    if (level.getBlockEntity(adj) instanceof ComputerBlockEntity) {
                        linkedComputerPos = adj;
                        syncToClient();
                        return;
                    }
                }
            }
        }
    }

    private void processMessage(BluetoothNetwork.Message msg) {
        String content = msg.content();
        if (content.startsWith("display_mode:")) {
            // Format: "display_mode:mirror" or "display_mode:test:bars" etc.
            String mode = content.substring("display_mode:".length());
            setDisplayMode(mode);
        } else if (content.startsWith("link:")) {
            // Format: "link:x,y,z" — sets the linked computer position for mirror mode
            try {
                String[] parts = content.substring(5).split(",");
                if (parts.length == 3) {
                    int x = Integer.parseInt(parts[0].trim());
                    int y = Integer.parseInt(parts[1].trim());
                    int z = Integer.parseInt(parts[2].trim());
                    linkedComputerPos = new BlockPos(x, y, z);
                    syncToClient();
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    // --- Display mode ---

    public String getDisplayMode() { return displayMode; }

    public void setDisplayMode(String mode) {
        if (mode == null || mode.isEmpty()) mode = "mirror";
        this.displayMode = mode;
        syncToClient();
    }

    public UUID getDeviceId() { return deviceId; }

    public BlockPos getWorldPosForOffset(int ox, int oy) {
        if (originPos == null) return worldPosition;
        Direction facing = getBlockState().getValue(MonitorBlock.FACING);
        return switch (facing) {
            case NORTH -> originPos.offset(-ox, oy, 0);
            case SOUTH -> originPos.offset(ox, oy, 0);
            case EAST  -> originPos.offset(0, oy, -ox);
            case WEST  -> originPos.offset(0, oy, ox);
            default    -> originPos.offset(ox, oy, 0);
        };
    }

    // ── Formation management ─────────────────────────────────

    public void setFormation(BlockPos origin, int width, int height, int offX, int offY) {
        this.originPos = origin;
        this.multiWidth = width;
        this.multiHeight = height;
        this.offsetX = offX;
        this.offsetY = offY;
        setChanged();
        syncToClient();
    }

    /**
     * Re-detect formation starting from the given position.
     * Flood-fills connected same-facing monitors, validates rectangle, sets formation data.
     */
    public static void reformFormation(Level level, BlockPos startPos) {
        BlockEntity startBe = level.getBlockEntity(startPos);
        if (!(startBe instanceof MonitorBlockEntity)) return;

        BlockState startState = level.getBlockState(startPos);
        if (!(startState.getBlock() instanceof MonitorBlock)) return;
        Direction facing = startState.getValue(MonitorBlock.FACING);

        // Flood fill connected same-facing monitors
        Set<BlockPos> connected = new LinkedHashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();
        queue.add(startPos);
        connected.add(startPos);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (BlockPos neighbor : getPlaneNeighborPositions(current, facing)) {
                if (connected.contains(neighbor)) continue;
                if (connected.size() >= 100) break; // 10x10 max
                BlockState state = level.getBlockState(neighbor);
                if (state.getBlock() instanceof MonitorBlock
                        && state.getValue(MonitorBlock.FACING) == facing) {
                    connected.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }

        // Preserve linked computer from any old origin in the group
        BlockPos previousLink = null;
        for (BlockPos p : connected) {
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof MonitorBlockEntity m && m.linkedComputerPos != null
                    && p.equals(m.originPos)) {
                previousLink = m.linkedComputerPos;
                break;
            }
        }

        // Compute bounding box
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        for (BlockPos p : connected) {
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
            int h = getHorizontalCoord(p, facing);
            minH = Math.min(minH, h);
            maxH = Math.max(maxH, h);
        }

        int width = maxH - minH + 1;
        int height = maxY - minY + 1;

        // Validate: must be a filled rectangle and within 10x10
        boolean validRect = (connected.size() == width * height
                && width <= 10 && height <= 10);
        if (validRect) {
            int fixedDepth = getDepthCoord(startPos, facing);
            for (int h = minH; h <= maxH && validRect; h++) {
                for (int y = minY; y <= maxY && validRect; y++) {
                    BlockPos check = makeBlockPos(h, y, fixedDepth, facing);
                    if (!connected.contains(check)) {
                        validRect = false;
                    }
                }
            }
        }

        if (validRect) {
            BlockPos origin = computeOrigin(connected, facing);
            for (BlockPos p : connected) {
                BlockEntity be = level.getBlockEntity(p);
                if (be instanceof MonitorBlockEntity monitor) {
                    int ox = computeOffsetX(p, origin, facing);
                    int oy = p.getY() - origin.getY();
                    monitor.setFormation(origin, width, height, ox, oy);
                }
            }
            // Restore linked computer on new origin
            if (previousLink != null) {
                BlockEntity originBe = level.getBlockEntity(origin);
                if (originBe instanceof MonitorBlockEntity originMonitor) {
                    originMonitor.linkedComputerPos = previousLink;
                    originMonitor.syncToClient();
                }
            }
        } else {
            // Not a valid rectangle — each block is its own 1x1 monitor
            for (BlockPos p : connected) {
                BlockEntity be = level.getBlockEntity(p);
                if (be instanceof MonitorBlockEntity monitor) {
                    monitor.setFormation(p, 1, 1, 0, 0);
                }
            }
        }
    }

    /** Returns the 4 neighbor positions in the monitor's plane (up/down + horizontal). */
    public static List<BlockPos> getPlaneNeighborPositions(BlockPos pos, Direction facing) {
        List<BlockPos> neighbors = new ArrayList<>(4);
        neighbors.add(pos.above());
        neighbors.add(pos.below());
        if (facing == Direction.NORTH || facing == Direction.SOUTH) {
            neighbors.add(pos.east());
            neighbors.add(pos.west());
        } else {
            neighbors.add(pos.north());
            neighbors.add(pos.south());
        }
        return neighbors;
    }

    private static int getHorizontalCoord(BlockPos pos, Direction facing) {
        return (facing == Direction.NORTH || facing == Direction.SOUTH) ? pos.getX() : pos.getZ();
    }

    private static int getDepthCoord(BlockPos pos, Direction facing) {
        return (facing == Direction.NORTH || facing == Direction.SOUTH) ? pos.getZ() : pos.getX();
    }

    private static BlockPos makeBlockPos(int horizontal, int y, int depth, Direction facing) {
        if (facing == Direction.NORTH || facing == Direction.SOUTH) {
            return new BlockPos(horizontal, y, depth);
        } else {
            return new BlockPos(depth, y, horizontal);
        }
    }

    /**
     * Compute the origin position (bottom-left when viewed from the front).
     * Bottom = minimum Y. Left depends on facing direction.
     */
    private static BlockPos computeOrigin(Set<BlockPos> blocks, Direction facing) {
        int minY = Integer.MAX_VALUE;
        for (BlockPos p : blocks) {
            minY = Math.min(minY, p.getY());
        }

        BlockPos best = null;
        for (BlockPos p : blocks) {
            if (p.getY() != minY) continue;
            if (best == null) {
                best = p;
                continue;
            }
            int ph = getHorizontalCoord(p, facing);
            int bh = getHorizontalCoord(best, facing);
            // NORTH/EAST: left = higher coordinate; SOUTH/WEST: left = lower coordinate
            boolean isMoreLeft = switch (facing) {
                case NORTH, EAST -> ph > bh;
                case SOUTH, WEST -> ph < bh;
                default -> false;
            };
            if (isMoreLeft) best = p;
        }
        return best;
    }

    private static int computeOffsetX(BlockPos pos, BlockPos origin, Direction facing) {
        return switch (facing) {
            case NORTH -> origin.getX() - pos.getX();
            case SOUTH -> pos.getX() - origin.getX();
            case EAST  -> origin.getZ() - pos.getZ();
            case WEST  -> pos.getZ() - origin.getZ();
            default -> 0;
        };
    }

    // ── Accessors ────────────────────────────────────────────

    public BlockPos getOriginPos() { return originPos; }
    public int getMultiWidth() { return multiWidth; }
    public int getMultiHeight() { return multiHeight; }
    public int getOffsetX() { return offsetX; }
    public int getOffsetY() { return offsetY; }
    public BlockPos getLinkedComputerPos() { return linkedComputerPos; }

    public MonitorBlockEntity getOriginEntity() {
        if (level == null) return null;
        if (worldPosition.equals(originPos)) return this;
        BlockEntity be = level.getBlockEntity(originPos);
        return (be instanceof MonitorBlockEntity m) ? m : null;
    }

    // ── Client sync ──────────────────────────────────────────

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private void syncToClient() {
        if (level != null && !level.isClientSide()) {
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ── NBT persistence ──────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putLong("OriginPos", originPos.asLong());
        tag.putInt("MultiWidth", multiWidth);
        tag.putInt("MultiHeight", multiHeight);
        tag.putInt("OffsetX", offsetX);
        tag.putInt("OffsetY", offsetY);
        if (linkedComputerPos != null) {
            tag.putLong("LinkedComputer", linkedComputerPos.asLong());
        }
        tag.putUUID("DeviceId", deviceId);
        tag.putString("DisplayMode", displayMode);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("OriginPos")) originPos = BlockPos.of(tag.getLong("OriginPos"));
        if (tag.contains("MultiWidth")) multiWidth = tag.getInt("MultiWidth");
        if (tag.contains("MultiHeight")) multiHeight = tag.getInt("MultiHeight");
        if (tag.contains("OffsetX")) offsetX = tag.getInt("OffsetX");
        if (tag.contains("OffsetY")) offsetY = tag.getInt("OffsetY");
        if (tag.contains("LinkedComputer")) {
            linkedComputerPos = BlockPos.of(tag.getLong("LinkedComputer"));
        } else {
            linkedComputerPos = null;
        }
        if (tag.hasUUID("DeviceId")) deviceId = tag.getUUID("DeviceId");
        if (tag.contains("DisplayMode")) displayMode = tag.getString("DisplayMode");
    }
}

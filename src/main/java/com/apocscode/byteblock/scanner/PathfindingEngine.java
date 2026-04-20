package com.apocscode.byteblock.scanner;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * A* pathfinding engine using scanner data.
 * Supports walk (ground robot) and fly (drone) modes.
 */
public class PathfindingEngine {

    public enum PathMode {
        WALK,   // Ground: needs solid floor + 2-block head clearance
        FLY     // 3D flight: any non-solid position is valid
    }

    private static final int MAX_ITERATIONS = 50_000;

    // Walk: cardinal, up/down, step-up, step-down
    private static final int[][] WALK_OFFSETS = {
        { 1, 0, 0}, {-1, 0, 0}, { 0, 0, 1}, { 0, 0,-1},   // flat cardinal
        { 0, 1, 0}, { 0,-1, 0},                              // vertical
        { 1, 1, 0}, {-1, 1, 0}, { 0, 1, 1}, { 0, 1,-1},     // step up
        { 1,-1, 0}, {-1,-1, 0}, { 0,-1, 1}, { 0,-1,-1}      // step down
    };

    // Fly: 6 face + 12 edge neighbors for smooth 3D paths
    private static final int[][] FLY_OFFSETS = {
        { 1, 0, 0}, {-1, 0, 0}, { 0, 1, 0}, { 0,-1, 0},
        { 0, 0, 1}, { 0, 0,-1},
        { 1, 1, 0}, {-1, 1, 0}, { 1,-1, 0}, {-1,-1, 0},
        { 1, 0, 1}, {-1, 0, 1}, { 1, 0,-1}, {-1, 0,-1},
        { 0, 1, 1}, { 0, 1,-1}, { 0,-1, 1}, { 0,-1,-1}
    };

    /**
     * Find path from start to end using A*.
     * Returns list of block positions forming the path, or empty list if none found.
     */
    public static List<BlockPos> findPath(WorldScanData data, Level level,
                                          BlockPos start, BlockPos end, PathMode mode) {
        if (start.equals(end)) return List.of(start);

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<Long, Double> bestG = new HashMap<>();

        Node startNode = new Node(start, 0, heuristic(start, end), null);
        open.add(startNode);
        bestG.put(start.asLong(), 0.0);

        int iterations = 0;
        while (!open.isEmpty() && iterations < MAX_ITERATIONS) {
            iterations++;
            Node current = open.poll();

            if (current.pos.equals(end)) {
                return reconstructPath(current);
            }

            // Skip if we already found a better path to this node
            Double best = bestG.get(current.pos.asLong());
            if (best != null && current.g > best) continue;

            int[][] offsets = mode == PathMode.WALK ? WALK_OFFSETS : FLY_OFFSETS;
            for (int[] off : offsets) {
                BlockPos neighbor = current.pos.offset(off[0], off[1], off[2]);

                if (!isValidMove(data, level, current.pos, neighbor, mode)) continue;

                double cost = Math.sqrt(off[0] * off[0] + off[1] * off[1] + off[2] * off[2]);
                double newG = current.g + cost;
                long key = neighbor.asLong();

                Double existing = bestG.get(key);
                if (existing != null && existing <= newG) continue;

                bestG.put(key, newG);
                open.add(new Node(neighbor, newG, newG + heuristic(neighbor, end), current));
            }
        }

        return List.of(); // no path found
    }

    private static boolean isValidMove(WorldScanData data, Level level,
                                       BlockPos from, BlockPos to, PathMode mode) {
        int tx = to.getX(), ty = to.getY(), tz = to.getZ();

        if (mode == PathMode.FLY) {
            return data.isPassable(level, tx, ty, tz);
        }

        // Walk mode: feet and head must be passable, floor must be solid
        if (data.isSolid(level, tx, ty, tz)) return false;         // feet
        if (data.isSolid(level, tx, ty + 1, tz)) return false;     // head
        if (!data.isSolid(level, tx, ty - 1, tz)) return false;    // floor

        // Stepping up: need ceiling clearance above current position
        if (to.getY() > from.getY()) {
            if (data.isSolid(level, from.getX(), from.getY() + 2, from.getZ())) {
                return false;
            }
        }
        return true;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<BlockPos> reconstructPath(Node end) {
        List<BlockPos> path = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) {
            path.add(n.pos);
        }
        Collections.reverse(path);
        return path;
    }

    private record Node(BlockPos pos, double g, double f, Node parent) {}
}

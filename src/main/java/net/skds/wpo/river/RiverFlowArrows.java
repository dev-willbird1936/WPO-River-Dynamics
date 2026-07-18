package net.skds.wpo.river;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

// Debug overlay toggled by /wpo river_arrows. It displays the same downstream-smoothed
// field used by fluid steering and entity forces; inspecting an area never triggers
// topology work itself.
final class RiverFlowArrows {

    private static final int FINE_RADIUS = 24;
    private static final int RADIUS = 96;
    private static final int COARSE_GRID = 8;
    // Scan center snaps to this grid so the covered set stays stable while the player drifts
    // within one cell - otherwise the budget cutoff ring re-centers every block of movement.
    private static final int SCAN_CENTER_GRID = 8;
    private static final int MAX_MARKERS_PER_PLAYER = 3_200;
    private static volatile boolean enabled;
    private static volatile List<Marker> snapshot = List.of();

    private RiverFlowArrows() {
    }

    static boolean toggle() {
        enabled = !enabled;
        if (!enabled) {
            snapshot = List.of();
        }
        return enabled;
    }

    static boolean enabled() {
        return enabled;
    }

    static void reset() {
        enabled = false;
        snapshot = List.of();
    }

    static List<Marker> snapshot() {
        return snapshot;
    }

    static void tick(ServerLevel level) {
        if (!enabled || (level.getGameTime() % 10L) != 0L) {
            return;
        }
        List<Marker> markers = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            int centerX = Math.floorDiv(player.blockPosition().getX(), SCAN_CENTER_GRID) * SCAN_CENTER_GRID;
            int centerZ = Math.floorDiv(player.blockPosition().getZ(), SCAN_CENTER_GRID) * SCAN_CENTER_GRID;
            List<BlockPos> positions = new ArrayList<>();
            RiverCurrentField.visitFresh(level, player.blockPosition(), RADIUS, (pos, current) -> {
                int dx = pos.getX() - centerX;
                int dz = pos.getZ() - centerZ;
                boolean fine = Math.max(Math.abs(dx), Math.abs(dz)) <= FINE_RADIUS;
                boolean coarse = Math.floorMod(dx, COARSE_GRID) == 0
                        && Math.floorMod(dz, COARSE_GRID) == 0;
                if (fine || coarse) {
                    positions.add(pos.immutable());
                }
            });
            positions.sort(Comparator.comparingLong(pos -> {
                long dx = pos.getX() - player.blockPosition().getX();
                long dz = pos.getZ() - player.blockPosition().getZ();
                return (dx * dx) + (dz * dz);
            }));
            int markerCount = Math.min(MAX_MARKERS_PER_PLAYER, positions.size());
            for (int i = 0; i < markerCount; i++) {
                BlockPos water = positions.get(i);
                RiverCurrentField.Flow flow = RiverCurrentField.smoothedFlowAt(level, water).orElse(null);
                if (flow != null) {
                    markers.add(Marker.flow(water.immutable(), flow.x(), flow.z(), flow.speed()));
                }
            }
        }
        snapshot = List.copyOf(markers);
    }

    /** Immutable render instruction for one water column: a direction, or confirmed idle. */
    record Marker(BlockPos pos, double dirX, double dirZ, double speed, boolean idle) {
        static Marker flow(BlockPos pos, double dirX, double dirZ, double speed) {
            return new Marker(pos, dirX, dirZ, speed, false);
        }

        static Marker idle(BlockPos pos) {
            return new Marker(pos, 0.0D, 0.0D, 0.0D, true);
        }
    }
}

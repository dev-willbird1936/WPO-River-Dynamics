package net.skds.wpo.river;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

final class RiverFlowDiagnostics {

    private static final int RADIUS = 64;

    private RiverFlowDiagnostics() {
    }

    static String report(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Map<Long, RiverCurrentField.Flow> currents = new HashMap<>();
        RiverCurrentField.visitFresh(level, player.blockPosition(), RADIUS, (pos, current) ->
                RiverCurrentField.smoothedFlowAt(level, pos).ifPresent(flow ->
                        currents.put(RiverPotentialSolver.key(pos.getX(), pos.getZ()), flow)));

        int pairs = 0;
        int opposed = 0;
        double dotSum = 0.0D;
        double speedSum = 0.0D;
        double minimumSpeed = Double.POSITIVE_INFINITY;
        double maximumSpeed = 0.0D;
        for (Map.Entry<Long, RiverCurrentField.Flow> entry : currents.entrySet()) {
            RiverCurrentField.Flow current = entry.getValue();
            speedSum += current.speed();
            minimumSpeed = Math.min(minimumSpeed, current.speed());
            maximumSpeed = Math.max(maximumSpeed, current.speed());
            int x = RiverPotentialSolver.x(entry.getKey());
            int z = RiverPotentialSolver.z(entry.getKey());
            for (long neighborKey : new long[] {
                    RiverPotentialSolver.key(x + 1, z),
                    RiverPotentialSolver.key(x, z + 1)
            }) {
                RiverCurrentField.Flow neighbor = currents.get(neighborKey);
                if (neighbor == null) {
                    continue;
                }
                double dot = normalizedDot(current, neighbor);
                if (Double.isFinite(dot)) {
                    pairs++;
                    dotSum += dot;
                    if (dot < -0.25D) {
                        opposed++;
                    }
                }
            }
        }

        double opposingPercent = pairs == 0 ? 0.0D : opposed * 100.0D / pairs;
        double meanDot = pairs == 0 ? 1.0D : dotSum / pairs;
        double meanSpeed = currents.isEmpty() ? 0.0D : speedSum / currents.size();
        if (currents.isEmpty()) {
            minimumSpeed = 0.0D;
        }
        return String.format(Locale.ROOT,
                "river_coherence: columns=%d adjacent=%d opposed=%d (%.2f%%) meanDot=%.3f speed[min/avg/max]=%.3f/%.3f/%.3f b/t",
                currents.size(), pairs, opposed, opposingPercent, meanDot,
                minimumSpeed, meanSpeed, maximumSpeed);
    }

    private static double normalizedDot(
            RiverCurrentField.Flow first,
            RiverCurrentField.Flow second
    ) {
        double firstLength = Math.hypot(first.x(), first.z());
        double secondLength = Math.hypot(second.x(), second.z());
        if (firstLength < 1.0E-7D || secondLength < 1.0E-7D) {
            return Double.NaN;
        }
        return ((first.x() * second.x()) + (first.z() * second.z()))
                / (firstLength * secondLength);
    }
}

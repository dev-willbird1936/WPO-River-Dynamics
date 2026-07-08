package net.skds.wpo.river;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.skds.wpo.WPOConfig;
import net.skds.wpo.api.WPOFluidAccess;

public final class RiverCurrentField {

    private static final Map<ResourceLocation, Map<Long, Current>> CURRENTS = new ConcurrentHashMap<>();

    private RiverCurrentField() {
    }

    static void publishColumn(ServerLevel level, BlockPos surfacePos, FlowDirective flow, double strength) {
        if (flow.direction() == null || !(strength > 0.0D)) {
            clear(level, surfacePos);
            return;
        }

        Current current = new Current(flow.direction(), strength, level.getGameTime(), flow.source());
        put(level, surfacePos, current);

        int depth = Math.max(1, RiverConfig.COMMON.currentColumnDepth.get());
        for (int i = 1; i < depth; i++) {
            BlockPos pos = surfacePos.below(i);
            if (WPOFluidAccess.getWaterAmount(level, pos) <= 0) {
                break;
            }
            put(level, pos, current);
        }
    }

    static void clear(ServerLevel level, BlockPos pos) {
        Map<Long, Current> currents = CURRENTS.get(level.dimension().location());
        if (currents != null) {
            currents.remove(pos.asLong());
        }
    }

    static void sweep(ServerLevel level, long maxAgeTicks) {
        Map<Long, Current> currents = CURRENTS.get(level.dimension().location());
        if (currents == null || currents.isEmpty()) {
            return;
        }
        long cutoff = level.getGameTime() - Math.max(20L, maxAgeTicks);
        currents.entrySet().removeIf(entry -> entry.getValue().updatedAt() < cutoff);
    }

    static void clear(ServerLevel level) {
        CURRENTS.remove(level.dimension().location());
    }

    static Optional<Current> currentAt(Level level, BlockPos pos) {
        Map<Long, Current> currents = CURRENTS.get(level.dimension().location());
        if (currents == null || currents.isEmpty()) {
            return Optional.empty();
        }

        long cutoff = level.getGameTime() - Math.max(20L, RiverConfig.COMMON.currentMaxAgeTicks.get());
        Current current = currents.get(pos.asLong());
        if (current == null || current.updatedAt() < cutoff) {
            current = currents.get(pos.below().asLong());
        }
        if (current == null || current.updatedAt() < cutoff) {
            current = currents.get(pos.above().asLong());
        }
        if (current == null || current.updatedAt() < cutoff) {
            return Optional.empty();
        }
        return Optional.of(current);
    }

    public static Vec3 flowVector(BlockGetter level, BlockPos pos, FluidState state) {
        if (!RiverConfig.COMMON.visualCurrentFlow.get() || !state.getType().isSame(Fluids.WATER)) {
            return Vec3.ZERO;
        }
        if (!(level instanceof Level actualLevel)) {
            return Vec3.ZERO;
        }

        Optional<Current> current = currentAt(actualLevel, pos);
        if (current.isEmpty()) {
            return Vec3.ZERO;
        }
        double depthFactor = Math.max(0.25D, Math.min(1.0D, state.getAmount() / (double) WPOConfig.MAX_FLUID_LEVEL));
        return current.get().vector(RiverConfig.COMMON.visualCurrentMultiplier.get() * depthFactor);
    }

    private static void put(ServerLevel level, BlockPos pos, Current current) {
        CURRENTS.computeIfAbsent(level.dimension().location(), ignored -> new ConcurrentHashMap<>())
                .put(pos.asLong(), current);
    }

    record Current(Direction direction, double strength, long updatedAt, String source) {
        Vec3 vector(double multiplier) {
            if (!(strength > 0.0D) || !(multiplier > 0.0D)) {
                return Vec3.ZERO;
            }
            return new Vec3(
                    direction.getStepX() * strength * multiplier,
                    0.0D,
                    direction.getStepZ() * strength * multiplier
            );
        }
    }
}
